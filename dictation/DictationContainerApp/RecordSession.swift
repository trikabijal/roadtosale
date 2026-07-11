import AVFoundation
import DictationCoreBase
import SwiftUI
import os

private let log = Logger(subsystem: "com.trika.dictation", category: "FlowSession")

/// Thread-safe audio accumulator (mirrors the keyboard's).
private final class AudioBox: @unchecked Sendable {
    private let lock = NSLock()
    private var buffers: [AVAudioPCMBuffer] = []
    func append(_ b: AVAudioPCMBuffer) { lock.lock(); buffers.append(b); lock.unlock() }
    func snapshot() -> [AVAudioPCMBuffer] { lock.lock(); defer { lock.unlock() }; return buffers }
    func reset() { lock.lock(); buffers.removeAll(); lock.unlock() }
}

/// The "Flow Session" — the mechanism that makes dictation feel like Wispr (no app switch after the
/// first launch). A keyboard extension can't touch the mic, so the container app records. But instead
/// of relaunching the app on every dictation, the app is launched ONCE and then keeps its audio engine
/// running in the background (UIBackgroundModes: audio) for a bounded idle window. While that session
/// is alive it heartbeats to the App Group; a keyboard mic tap then only posts `start`/`stop` Darwin
/// signals — no `openURL` — so iOS never foregrounds the app and the user stays in whatever app they're
/// typing in. The engine runs the whole session; we only ACCUMULATE audio while `capturing`.
@MainActor
final class RecordSessionModel: ObservableObject {
    /// `listening` = capturing this dictation. `idle` = session alive, engine running, waiting for the
    /// next keyboard tap (this is the seamless state — app is backgrounded but alive).
    enum Phase: Equatable { case starting, listening, transcribing, idle, ended, failed(String) }
    @Published var phase: Phase = .starting
    @Published var level: Float = 0
    @Published var transcript: String = ""
    @Published var dictationCount = 0

    private let engine = RecordingEngine()
    private let transcriber: any SpeechTranscriber
    private let cleanup: any TextCleanup
    private let cleanupPack: CleanupPack
    private let audio = AudioBox()
    private var capturing = false
    private var captureStart: Date?

    // Session lifetime: engine stays up this long after the last activity, then we tear down so the
    // mic can't stay live forever. Each dictation resets the clock.
    private static let idleTimeoutSeconds: Double = 90
    private static let heartbeatSeconds: Double = 3
    private var idleTimer: Task<Void, Never>?
    private var heartbeat: Task<Void, Never>?
    private var lastActivity = Date()

    init() {
        let pack = CleanupPackLoader.load()
        cleanupPack = pack
        cleanup = TextCleanupFactory.make(CleanupConfig(provider: .ruleBased, level: .light), pack: pack)
        if #available(iOS 26.0, *) {
            transcriber = AppleAnalyzerTranscriber(localeIdentifier: "en-US")
        } else {
            transcriber = AppleSpeechTranscriber(language: "en-US")
        }
        engine.delegate = self
    }

    // MARK: - Session lifecycle

    /// Launch → start the session AND capture the first dictation. Registers the keyboard signal
    /// observers up front so a fast start→stop during warm-up isn't missed.
    func begin() async {
        guard phase == .starting else { return }
        registerObservers()
        do { try await transcriber.load() } catch {
            log.error("load: \(error.localizedDescription, privacy: .public)")
        }
        do {
            try await engine.requestPermission()
            try engine.start()                 // runs for the WHOLE session; we toggle `capturing`
        } catch {
            phase = .failed(error.localizedDescription)
            DictationHandoff.markSessionEnded()
            return
        }
        startHeartbeat()
        startIdleTimer()
        beginCapture()                          // the tap that launched us is the first dictation
    }

    /// Keyboard signalled `start` (session already alive) — begin a new dictation, no relaunch.
    private func beginCapture() {
        audio.reset()
        capturing = true
        captureStart = Date()
        touch()
        phase = .listening
    }

    /// Keyboard signalled `stop` — stop capturing, transcribe, hand the text back. Session stays ALIVE
    /// (engine keeps running) so the next tap is seamless.
    private func endCaptureAndTranscribe() {
        guard capturing else { return }
        capturing = false
        touch()
        phase = .transcribing
        let buffers = audio.snapshot()
        let sd = captureStart ?? Date()
        Task { await finish(buffers, sd) }
    }

    private func finish(_ buffers: [AVAudioPCMBuffer], _ sd: Date) async {
        defer { if phase == .transcribing { phase = .idle } }
        do {
            let r = try await transcriber.transcribe(buffers: buffers, audioStartDate: sd)
            var text = r.text
            if !text.isEmpty,
               text.split(whereSeparator: \.isWhitespace).count >= cleanupPack.minWordsForCleanup {
                text = (await cleanup.clean(CleanupRequest(rawText: text, level: .light))).cleanedText
            }
            guard !text.isEmpty else { return }
            transcript = text
            dictationCount += 1
            DictationHandoff.write(text)                       // keyboard reads this
            DictationHandoff.post(DictationHandoff.doneNotification)   // ...on this signal
        } catch {
            log.error("transcribe: \(error.localizedDescription, privacy: .public)")
        }
    }

    /// Tear the session down: stop the engine, clear the heartbeat so the next keyboard tap relaunches
    /// (cold path). Called on idle timeout or when the view closes.
    func endSession() {
        idleTimer?.cancel(); idleTimer = nil
        heartbeat?.cancel(); heartbeat = nil
        capturing = false
        engine.stop()
        DictationHandoff.markSessionEnded()
        DictationHandoff.writeLevel(0)
        DictationHandoff.removeObserver(Unmanaged.passUnretained(self).toOpaque())
        if case .failed = phase {} else { phase = .ended }
    }

    deinit {
        DictationHandoff.removeObserver(Unmanaged.passUnretained(self).toOpaque())
    }

    // MARK: - Keyboard signals

    private func registerObservers() {
        let me = Unmanaged.passUnretained(self).toOpaque()
        DictationHandoff.observe(DictationHandoff.startNotification, observer: me) { _, obs, _, _, _ in
            guard let obs else { return }
            let m = Unmanaged<RecordSessionModel>.fromOpaque(obs).takeUnretainedValue()
            Task { @MainActor in m.beginCapture() }
        }
        DictationHandoff.observe(DictationHandoff.stopNotification, observer: me) { _, obs, _, _, _ in
            guard let obs else { return }
            let m = Unmanaged<RecordSessionModel>.fromOpaque(obs).takeUnretainedValue()
            Task { @MainActor in m.endCaptureAndTranscribe() }
        }
    }

    // MARK: - Heartbeat + idle

    private func touch() { lastActivity = Date(); DictationHandoff.markSessionAlive() }

    private func startHeartbeat() {
        DictationHandoff.markSessionAlive()
        heartbeat = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(Self.heartbeatSeconds))
                guard self != nil, !Task.isCancelled else { return }
                DictationHandoff.markSessionAlive()   // keep the keyboard's "session alive" read fresh
            }
        }
    }

    private func startIdleTimer() {
        idleTimer = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(5))
                guard let self, !Task.isCancelled else { return }
                if !self.capturing, Date().timeIntervalSince(self.lastActivity) > Self.idleTimeoutSeconds {
                    self.endSession()
                    return
                }
            }
        }
    }
}

extension RecordSessionModel: RecordingEngineDelegate {
    nonisolated func recordingEngine(_ e: RecordingEngine, didReceiveBuffer b: AVAudioPCMBuffer) {
        Task { @MainActor in
            guard self.capturing else { return }   // engine runs all session; accumulate only while capturing
            self.audio.append(b)
        }
    }
    nonisolated func recordingEngineDidDetectSilence(_ e: RecordingEngine) {}
    nonisolated func recordingEngine(_ e: RecordingEngine, didUpdateLevel l: Float) {
        Task { @MainActor in
            self.level = l
            DictationHandoff.writeLevel(self.capturing ? l : 0)   // keyboard waveform reads this
        }
    }
}

// MARK: - View

struct RecordSessionView: View {
    @StateObject private var model = RecordSessionModel()
    let onClose: () -> Void

    var body: some View {
        VStack(spacing: 24) {
            Spacer()
            switch model.phase {
            case .starting:
                ProgressView().controlSize(.large)
                Text("Getting ready…").foregroundStyle(.secondary)
            case .listening:
                MicPulse(level: model.level)
                Text("Listening…").font(.title).bold()
                Text("Swipe back to your app and keep talking.\nTap the keyboard mic to finish.")
                    .multilineTextAlignment(.center).foregroundStyle(.secondary).padding(.horizontal)
            case .transcribing:
                ProgressView().controlSize(.large)
                Text("Transcribing…").foregroundStyle(.secondary)
            case .idle:
                Image(systemName: "checkmark.circle.fill").font(.system(size: 48)).foregroundStyle(.green)
                Text("Ready — tap the keyboard mic to dictate again.")
                    .multilineTextAlignment(.center).foregroundStyle(.secondary).padding(.horizontal)
                if !model.transcript.isEmpty {
                    Text(model.transcript).font(.callout).multilineTextAlignment(.center)
                        .foregroundStyle(.primary).padding(.horizontal)
                }
            case .ended:
                Text("Session ended.").foregroundStyle(.secondary)
            case .failed(let msg):
                Image(systemName: "exclamationmark.triangle.fill").font(.system(size: 48)).foregroundStyle(.orange)
                Text(msg).multilineTextAlignment(.center).foregroundStyle(.secondary).padding(.horizontal)
            }
            Spacer()
            Button("End session", action: { model.endSession(); onClose() }).padding(.bottom)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .task { await model.begin() }
        .onChange(of: model.phase) { _, p in
            if p == .ended { onClose() }
        }
    }
}

private struct MicPulse: View {
    let level: Float
    var body: some View {
        ZStack {
            Circle().fill(Color.red.opacity(0.15))
                .frame(width: 140 + CGFloat(min(1, level * 6)) * 60, height: 140 + CGFloat(min(1, level * 6)) * 60)
                .animation(.easeOut(duration: 0.1), value: level)
            Image(systemName: "mic.fill").font(.system(size: 44)).foregroundStyle(.red)
        }.frame(height: 220)
    }
}
