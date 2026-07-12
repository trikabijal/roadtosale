import AVFoundation
import os
import DictationCoreBase

private let log = Logger(subsystem: DictationHandoff.logSubsystem, category: "FlowSessionAudio")

/// The iOS Flow Session's audio, in ONE `AVAudioEngine` that starts once and never restarts (two
/// engines fighting one session threw `'what'` 2003329396 after a cold relaunch).
///
/// - A player node loops **silent audio** through the output → keeps the app alive in the background
///   (`UIBackgroundModes: audio`).
/// - The mic **tap is installed once at start** (engaging the input reliably — installing it later on
///   an output-only engine yields 0 buffers). Buffers are only **forwarded while `capturing`**, so audio
///   is accumulated only during an actual dictation.
///
/// NOTE: because the tap is always installed, the mic is engaged for the life of the session (the iOS
/// orange indicator shows while warm). That's the reliable trade-off; turning the mic fully off between
/// dictations without breaking capture needs more work (tracked separately).
@MainActor
final class FlowSessionAudio {
    private let engine = AVAudioEngine()
    private let player = AVAudioPlayerNode()
    private let targetFormat = AVAudioFormat(
        commonFormat: .pcmFormatFloat32, sampleRate: 16_000, channels: 1, interleaved: false)!
    /// Liveness is DERIVED from the engine itself, never cached: iOS silently stops the engine on an
    /// audio interruption (system dictation, a call), and a cached `running = true` would then let us
    /// "capture" on a dead engine → 0 audio → lost transcript. `engine.isRunning` is always the truth.
    var running: Bool { engine.isRunning }
    private var attached = false   // one-time: player node attached + connected to this engine's graph
    /// Gates buffer forwarding. Read on the audio thread; set on the main actor around a dictation.
    nonisolated(unsafe) private var capturing = false

    nonisolated(unsafe) var onBuffer: ((AVAudioPCMBuffer) -> Void)?
    nonisolated(unsafe) var onLevel: ((Float) -> Void)?

    /// Start OR resume the session. Idempotent and safe to call again after an iOS audio interruption
    /// stopped the engine: it re-activates the session, re-installs the mic tap, and re-starts the
    /// engine/keep-alive WITHOUT re-attaching the player node (that would crash). This is the "resume"
    /// path the user's model needs — Apple takes the mic, we pause; Apple leaves, we start() again.
    func start() throws {
        guard !engine.isRunning else { return }
        let session = AVAudioSession.sharedInstance()
        try session.setCategory(.playAndRecord, mode: .default,
                                options: [.mixWithOthers, .allowBluetooth, .defaultToSpeaker])
        try session.setActive(true, options: .notifyOthersOnDeactivation)

        // Silent playback (keep-alive) — fixed 44.1k format; mixer converts to hardware.
        guard let outFmt = AVAudioFormat(standardFormatWithSampleRate: 44_100, channels: 2) else {
            throw NSError(domain: "FlowSessionAudio", code: 1)
        }
        // Attach the player ONCE — attaching twice traps. Everything below is safe to redo on a resume.
        if !attached {
            engine.attach(player)
            engine.connect(player, to: engine.mainMixerNode, format: outFmt)
            attached = true
        }

        // (Re)install the mic tap. Remove any stale tap first so a resume can't double-install (traps).
        // Installing engages the input node so capture is reliable. Forwarding is gated by `capturing`.
        let input = engine.inputNode
        input.removeTap(onBus: 0)
        let inFmt = input.outputFormat(forBus: 0)
        DictationHandoff.trace("audio", "start · inFmt=\(inFmt.sampleRate)Hz \(inFmt.channelCount)ch")
        if inFmt.sampleRate > 0, inFmt.channelCount > 0,
           let conv = AVAudioConverter(from: inFmt, to: targetFormat) {
            let target = targetFormat
            let bufSize = max(AVAudioFrameCount(inFmt.sampleRate * 0.1), 1024)
            input.installTap(onBus: 0, bufferSize: bufSize, format: inFmt) { [weak self] buffer, _ in
                self?.handle(buffer, to: target, using: conv)
            }
        } else {
            DictationHandoff.trace("audio", "no valid input format — mic tap NOT installed")
        }

        engine.prepare()
        try engine.start()

        // (Re)schedule the silent keep-alive loop and play. Clear any prior schedule first so a resume
        // doesn't stack loops.
        player.stop()
        let frames = AVAudioFrameCount(outFmt.sampleRate * 0.5)
        if let buf = AVAudioPCMBuffer(pcmFormat: outFmt, frameCapacity: frames) {
            buf.frameLength = frames
            // A whisper-quiet tone, NOT pure zeros: iOS can suspend an app "playing" pure silence during
            // a long session (which killed a 66s dictation). ~-78 dB is inaudible but keeps the output
            // treated as real background audio.
            let amp: Float = 0.00012
            let sr = Float(outFmt.sampleRate)
            if let data = buf.floatChannelData {
                for c in 0..<Int(outFmt.channelCount) {
                    for i in 0..<Int(frames) { data[c][i] = amp * sin(2 * .pi * 440 * Float(i) / sr) }
                }
            }
            player.scheduleBuffer(buf, at: nil, options: .loops, completionHandler: nil)
            player.play()
        }
        DictationHandoff.trace("audio", "started · running=\(engine.isRunning) playing=\(player.isPlaying)")
        log.notice("flow audio started · running=\(self.engine.isRunning) playing=\(self.player.isPlaying)")
    }

    /// Begin forwarding mic buffers (a dictation started).
    func beginCapture() { capturing = true }
    /// Stop forwarding (dictation ended); the tap + engine keep running for keep-alive.
    func endCapture() { capturing = false }

    func stop() {
        capturing = false
        engine.inputNode.removeTap(onBus: 0)
        player.stop(); engine.stop()   // `running` is derived from engine.isRunning → now false
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        log.notice("flow audio stopped")
    }

    // MARK: - Audio thread

    private nonisolated func handle(_ input: AVAudioPCMBuffer, to target: AVAudioFormat, using conv: AVAudioConverter) {
        guard capturing else { return }   // only accumulate during a dictation
        let outCap = AVAudioFrameCount(Double(input.frameLength) * target.sampleRate / input.format.sampleRate + 1)
        guard let out = AVAudioPCMBuffer(pcmFormat: target, frameCapacity: outCap) else { return }
        var err: NSError?
        var fed = false
        conv.convert(to: out, error: &err) { _, status in
            if fed { status.pointee = .noDataNow; return nil }
            fed = true; status.pointee = .haveData; return input
        }
        guard err == nil, out.frameLength > 0 else { return }
        onLevel?(Self.rms(out))
        onBuffer?(out)
    }

    private nonisolated static func rms(_ buffer: AVAudioPCMBuffer) -> Float {
        guard let ch = buffer.floatChannelData?[0] else { return 0 }
        let n = Int(buffer.frameLength); guard n > 0 else { return 0 }
        var sum: Float = 0
        for i in 0..<n { let s = ch[i]; sum += s * s }
        return (sum / Float(n)).squareRoot()
    }
}
