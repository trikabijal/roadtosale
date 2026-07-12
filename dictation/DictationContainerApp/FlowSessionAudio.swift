import AVFoundation
import os
import DictationCoreBase

private let log = Logger(subsystem: "com.trika.dictation", category: "FlowSessionAudio")

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
    private(set) var running = false
    /// Gates buffer forwarding. Read on the audio thread; set on the main actor around a dictation.
    nonisolated(unsafe) private var capturing = false

    nonisolated(unsafe) var onBuffer: ((AVAudioPCMBuffer) -> Void)?
    nonisolated(unsafe) var onLevel: ((Float) -> Void)?

    func start() throws {
        guard !running else { return }
        let session = AVAudioSession.sharedInstance()
        try session.setCategory(.playAndRecord, mode: .default,
                                options: [.mixWithOthers, .allowBluetooth, .defaultToSpeaker])
        try session.setActive(true, options: .notifyOthersOnDeactivation)

        // Silent playback (keep-alive) — fixed 44.1k format; mixer converts to hardware.
        guard let outFmt = AVAudioFormat(standardFormatWithSampleRate: 44_100, channels: 2) else {
            throw NSError(domain: "FlowSessionAudio", code: 1)
        }
        engine.attach(player)
        engine.connect(player, to: engine.mainMixerNode, format: outFmt)

        // Mic tap — installed ONCE. This engages the input node so capture is reliable, and the engine
        // never has to restart (no 'what'). Forwarding is gated by `capturing`.
        let input = engine.inputNode
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

        let frames = AVAudioFrameCount(outFmt.sampleRate * 0.5)
        if let silence = AVAudioPCMBuffer(pcmFormat: outFmt, frameCapacity: frames) {
            silence.frameLength = frames
            player.scheduleBuffer(silence, at: nil, options: .loops, completionHandler: nil)
            player.play()
        }
        running = true
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
        player.stop(); engine.stop(); running = false
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
