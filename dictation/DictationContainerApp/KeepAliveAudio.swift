import AVFoundation
import os

private let log = Logger(subsystem: "com.trika.dictation", category: "KeepAlive")

/// Keeps the container app alive in the background BETWEEN dictations without holding the microphone —
/// so there's no orange mic indicator and minimal battery, yet the app stays resident and a keyboard
/// mic tap reaches it via a Darwin signal (no relaunch, no app switch). The trick: a silent audio
/// buffer plays on loop through the output; iOS keeps a `UIBackgroundModes: audio` app alive while it's
/// actively playing audio.
///
/// This object OWNS the shared `AVAudioSession`. The mic `RecordingEngine` runs with
/// `managesAudioSession = false` and only during actual capture; we flip the session category to
/// `.playAndRecord` for the duration of a dictation, then back to `.playback` (dropping the mic
/// reservation → the orange dot goes away).
@MainActor
final class KeepAliveAudio {
    private let engine = AVAudioEngine()
    private let player = AVAudioPlayerNode()
    private(set) var running = false

    /// Begin silent playback → the app stays alive in the background.
    func start() {
        guard !running else { return }
        configureSession(recording: false)
        guard let fmt = AVAudioFormat(standardFormatWithSampleRate: 44_100, channels: 2),
              let buf = AVAudioPCMBuffer(pcmFormat: fmt, frameCapacity: 44_100) else { return }
        buf.frameLength = buf.frameCapacity          // all-zero samples = silence
        engine.attach(player)
        engine.connect(player, to: engine.mainMixerNode, format: fmt)
        do {
            try engine.start()
            player.scheduleBuffer(buf, at: nil, options: .loops, completionHandler: nil)
            player.play()
            running = true
            log.notice("keep-alive started (silent playback)")
        } catch {
            log.error("keep-alive start failed: \(error.localizedDescription, privacy: .public)")
        }
    }

    /// Flip to record mode so the mic engine can capture. Call right before starting a dictation.
    func beginRecordingMode() { configureSession(recording: true) }

    /// Return to silent keep-alive after a dictation — drops the mic reservation (orange dot off).
    func endRecordingMode() { configureSession(recording: false) }

    /// Tear down entirely (session end): stop playback + release the audio session.
    func stop() {
        guard running else { return }
        player.stop(); engine.stop(); running = false
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        log.notice("keep-alive stopped")
    }

    private func configureSession(recording: Bool) {
        let s = AVAudioSession.sharedInstance()
        do {
            if recording {
                try s.setCategory(.playAndRecord, mode: .default,
                                  options: [.mixWithOthers, .allowBluetooth, .defaultToSpeaker])
            } else {
                try s.setCategory(.playback, mode: .default, options: [.mixWithOthers])
            }
            try s.setActive(true, options: .notifyOthersOnDeactivation)
        } catch {
            log.error("session configure (recording=\(recording)) failed: \(error.localizedDescription, privacy: .public)")
        }
    }
}
