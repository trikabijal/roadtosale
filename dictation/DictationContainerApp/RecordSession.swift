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
    /// Names match docs/ios-dictation-architecture.md. `capturing` deliberately shares its name with
    /// the `capturing` App-Group variable (same event); `ready` = warm & alive, waiting.
    enum Phase: Equatable { case warming, capturing, transcribing, ready, ended, failed(String) }
    @Published var phase: Phase = .warming
    @Published var level: Float = 0
    @Published var transcript: String = ""
    @Published var dictationCount = 0

    private let flowAudio = FlowSessionAudio()   // one engine: silent keep-alive + mic capture
    private let transcriber: any SpeechTranscriber
    private let cleanup: any TextCleanup
    private let cleanupPack: CleanupPack
    private let audio = AudioBox()
    private var capturing = false
    private var captureStart: Date?

    // Safety-valve only: how long the silent keep-alive may run with NO dictation before we release it
    // (avoids pointless background audio for days if the user forgets). Long, because staying warm is
    // the whole point — in practice iOS reclaims the app first. Each dictation resets the clock.
    // TODO: expose as a setting (Wispr offers 5 min / 15 min / 1 hr / never).
    private static let idleTimeoutSeconds: Double = 7200   // 2 hours
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
        // Audio-thread callbacks: accumulate only while capturing; publish the live level for the
        // keyboard waveform. Hop to the main actor for the model's state.
        flowAudio.onBuffer = { [weak self] buf in
            Task { @MainActor in guard let self, self.capturing else { return }; self.audio.append(buf) }
        }
        flowAudio.onLevel = { [weak self] lvl in
            Task { @MainActor in
                guard let self else { return }
                self.level = lvl
                DictationHandoff.writeLevel(self.capturing ? lvl : 0)
            }
        }
    }

    private var didWarm = false

    // MARK: - Session lifecycle

    /// Warm the session WITHOUT recording: load the model, take mic permission, start the silent
    /// keep-alive so the app stays resident. Called during onboarding (and on app foreground) so the
    /// FIRST real dictation is already hot — no cold launch, no app switch. Idempotent.
    func warm() async {
        guard !didWarm else { return }
        didWarm = true
        DictationHandoff.setCapturing(false)   // clear any stale flag left by a killed session
        registerObservers()
        do { try await transcriber.load() } catch {
            log.error("load: \(error.localizedDescription, privacy: .public)")
        }
        guard await Self.requestMicPermission() else {
            DictationHandoff.trace("app", "mic permission denied")
            didWarm = false
            return
        }
        do {
            try flowAudio.start()   // one engine: silent keep-alive now; mic tap added per dictation
        } catch {
            DictationHandoff.trace("app", "flow audio start FAILED: \(error.localizedDescription)")
            didWarm = false
            return
        }
        DictationHandoff.trace("app", "warm — keep-alive started, session alive")
        startHeartbeat()
        startIdleTimer()
        if phase == .warming { phase = .ready }
    }

    /// Cold launch via `justtalk://record` (session wasn't warm): warm, then capture the first dictation.
    func begin() async {
        DictationHandoff.resetTrace()
        DictationHandoff.trace("app", "begin — cold launch")
        await warm()
        guard didWarm else { phase = .failed("Microphone access needed"); return }
        beginCapture()
    }

    /// Keyboard signalled `start` (session already alive) — begin a new dictation, no relaunch.
    private func beginCapture() {
        guard flowAudio.running, !capturing else { return }
        DictationHandoff.trace("app", "beginCapture (start signal received)")
        audio.reset()
        flowAudio.beginCapture()                  // install the mic tap (no engine restart → no 'what')
        capturing = true
        DictationHandoff.setCapturing(true)       // keyboard reads this to know a dictation is live
        captureStart = Date()
        touch()
        phase = .capturing
    }

    /// Keyboard signalled `stop` — stop capturing, transcribe, hand the text back. Session stays ALIVE
    /// (engine keeps running) so the next tap is seamless.
    private func endCaptureAndTranscribe() {
        guard capturing else {
            DictationHandoff.trace("app", "stop signal but NOT capturing (ignored)")
            return
        }
        let count = audio.snapshot().count
        DictationHandoff.trace("app", "stop signal — transcribing \(count) buffers")
        flowAudio.endCapture()                    // remove the mic tap; engine keeps running (keep-alive)
        capturing = false
        DictationHandoff.setCapturing(false)
        touch()
        phase = .transcribing
        let buffers = audio.snapshot()
        let sd = captureStart ?? Date()
        Task { await finish(buffers, sd) }
    }

    private func finish(_ buffers: [AVAudioPCMBuffer], _ sd: Date) async {
        defer { if phase == .transcribing { phase = .ready } }
        do {
            let r = try await transcriber.transcribe(buffers: buffers, audioStartDate: sd)
            var text = r.text
            if !text.isEmpty,
               text.split(whereSeparator: \.isWhitespace).count >= cleanupPack.minWordsForCleanup {
                text = (await cleanup.clean(CleanupRequest(rawText: text, level: .light))).cleanedText
            }
            guard !text.isEmpty else {
                DictationHandoff.trace("app", "transcribe → EMPTY (nothing heard)")
                return
            }
            transcript = text
            dictationCount += 1
            // Write pendingText only. The app NEVER signals the keyboard (per the architecture doc) —
            // the keyboard polls this. A `done` Darwin signal used to live here; it caused a stale-
            // instance race and is deliberately gone.
            DictationHandoff.write(text)
            DictationHandoff.trace("app", "transcribe → \(text.count) chars, wrote pendingText")
        } catch {
            DictationHandoff.trace("app", "transcribe ERROR: \(error.localizedDescription)")
            log.error("transcribe: \(error.localizedDescription, privacy: .public)")
        }
    }

    /// Tear the session down: stop the engine, clear the heartbeat so the next keyboard tap relaunches
    /// (cold path). Called on idle timeout or when the view closes.
    func endSession() {
        idleTimer?.cancel(); idleTimer = nil
        heartbeat?.cancel(); heartbeat = nil
        capturing = false
        didWarm = false
        flowAudio.stop()
        DictationHandoff.setCapturing(false)
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

    /// The keep-alive runs indefinitely once warmed — we deliberately do NOT auto-end on idle, so the
    /// app stays warm all day and dictation never re-hops. iOS is what eventually reclaims the app
    /// (memory pressure / reboot); the next dictation then cold-launches once and re-warms. This keeps
    /// a light idle watchdog only as a safety valve for a very long absence.
    private func startIdleTimer() {
        idleTimer = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(30))
                guard let self, !Task.isCancelled else { return }
                if !self.capturing, Date().timeIntervalSince(self.lastActivity) > Self.idleTimeoutSeconds {
                    self.endSession()
                    return
                }
            }
        }
    }
}

extension RecordSessionModel {
    /// Request microphone permission (the one-engine FlowSessionAudio needs it before it can tap the mic).
    static func requestMicPermission() async -> Bool {
        switch AVAudioApplication.shared.recordPermission {
        case .granted: return true
        case .denied:  return false
        default:       return await AVAudioApplication.requestRecordPermission()
        }
    }
}

// MARK: - View

struct RecordSessionView: View {
    @ObservedObject var model: RecordSessionModel   // app-level session (owned by the App), not per-view
    let onClose: () -> Void

    var body: some View {
        VStack(spacing: 24) {
            Spacer()
            switch model.phase {
            case .warming:
                ProgressView().controlSize(.large)
                Text("Getting ready…").foregroundStyle(.secondary)
            case .capturing:
                MicPulse(level: model.level)
                Text("Listening…").font(.title).bold()
                Text("Swipe back to your app and keep talking.\nTap the keyboard mic to finish.")
                    .multilineTextAlignment(.center).foregroundStyle(.secondary).padding(.horizontal)
            case .transcribing:
                ProgressView().controlSize(.large)
                Text("Transcribing…").foregroundStyle(.secondary)
            case .ready:
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
        // `begin()` is kicked off by the App's onOpenURL (cold launch); the model is app-level and may
        // already be warm, so we don't start it here.
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
