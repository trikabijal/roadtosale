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
    /// The exact interprocess footprint a state implies. Entering a state writes EXACTLY this, so the
    /// App-Group variables are consistent with the state by construction — the State pattern's guarantee.
    struct Vars: Equatable {
        /// A dictation is being captured (keyboard shows "speaking"; the audio thread forwards buffers).
        let capturing: Bool
        /// Warm & usable — keyboard takes the seamless signal path; otherwise it cold-launches.
        let alive: Bool
    }

    /// The app's session states. Each OWNS its variable footprint (`vars`) — the State-pattern entry
    /// action lives on the state, not scattered across call sites. `commit(_:)` is the single "enter".
    enum Phase: Equatable {
        case warming, capturing, transcribing, ready, ended, failed(String)
        var vars: Vars {
            switch self {
            case .capturing:                return Vars(capturing: true,  alive: true)
            case .ready, .transcribing:     return Vars(capturing: false, alive: true)
            case .warming, .ended, .failed: return Vars(capturing: false, alive: false)
            }
        }
        var isAlive: Bool { vars.alive }
    }
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

    // Bound a SINGLE dictation's length so accumulated audio can't grow without limit. At 16 kHz mono
    // Float32 the buffer grows ~64 KB/s, so 10 min ≈ 38 MB — comfortably within the app's headroom, but
    // we auto-finish there so a forgotten mic can't accumulate for hours. (RAM is not the real limit;
    // this is a safety valve, and it also means a very long take still transcribes rather than being lost.)
    private static let maxCaptureSeconds: Double = 600   // 10 minutes
    private var maxCaptureTimer: Task<Void, Never>?

    /// Observes `AVAudioSession` interruptions (system dictation, an incoming call, another app grabbing
    /// the mic). Without this, an interruption silently stops our engine and the in-flight take is lost.
    private var interruptionObserver: NSObjectProtocol?

    init() {
        let pack = CleanupPackLoader.load()
        cleanupPack = pack
        // Foundation Models cleanup (Apple's on-device LLM) to match the Mac's accuracy — the raw STT is
        // the same SpeechAnalyzer, but rule-based cleanup left the phone looking far worse. The factory
        // falls back to rule-based if FM isn't available (older device / no Apple Intelligence). The
        // container app has the memory headroom for it (unlike the 70 MB keyboard extension).
        cleanup = TextCleanupFactory.make(CleanupConfig(provider: .foundationModels, level: .light), pack: pack)
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

    // MARK: - Single source of truth → App Group projection
    //
    // The mirror image of the keyboard's one-snapshot rule. The keyboard READS the interprocess
    // variables into a single `KeyboardPresentation`; the app WRITES them from a single `phase`. Every
    // variable the keyboard depends on (`capturing`, the `heartbeat` alive/ended, the level gate) is a
    // PURE FUNCTION of `phase`, written together HERE. Nothing else in this file may touch
    // `DictationHandoff.setCapturing` / `markSessionAlive` / `markSessionEnded` or assign `self.capturing`
    // — that scattering is exactly what let the app's own state drift from the variables (engine dead but
    // `capturing` still true) and caused the lost-take + stuck-keyboard bugs.

    /// The single "enter a state" — the State pattern's entry point. Client code only ever says *which*
    /// state we're in; applying its `vars` to the App Group happens here, so the variables can never be
    /// left inconsistent with the state.
    private func commit(_ p: Phase) {
        phase = p                                       // @Published — the app's own UI
        let v = p.vars
        capturing = v.capturing                         // audio-thread forwarding gate (same fact)
        DictationHandoff.setCapturing(v.capturing)      // keyboard: "is a dictation live?"
        v.alive ? DictationHandoff.markSessionAlive()   // keyboard: seamless-signal vs cold-launch
                : DictationHandoff.markSessionEnded()
        if !v.capturing { DictationHandoff.writeLevel(0) }    // no live level unless capturing
    }

    // MARK: - Session lifecycle

    /// Warm the session WITHOUT recording: load the model, take mic permission, start the silent
    /// keep-alive so the app stays resident. Called during onboarding (and on app foreground) so the
    /// FIRST real dictation is already hot — no cold launch, no app switch. Idempotent.
    func warm() async {
        guard !didWarm else { return }
        didWarm = true
        commit(.warming)                       // clears any stale capturing/alive from a killed session
        registerObservers()
        DictationHandoff.trace("app", "warm: loading transcriber…")
        do { try await transcriber.load() } catch {
            log.error("load: \(error.localizedDescription, privacy: .public)")
        }
        DictationHandoff.trace("app", "warm: requesting mic permission…")
        guard await Self.requestMicPermission() else {
            DictationHandoff.trace("app", "mic permission denied")
            didWarm = false
            return
        }
        DictationHandoff.trace("app", "warm: starting flow audio…")
        do {
            try flowAudio.start()   // one engine: silent keep-alive + always-installed mic tap
        } catch {
            DictationHandoff.trace("app", "flow audio start FAILED: \(error.localizedDescription)")
            didWarm = false
            return
        }
        DictationHandoff.trace("app", "warm — keep-alive started, session alive")
        registerInterruptionObserver()
        startHeartbeat()
        startIdleTimer()
        if phase == .warming { commit(.ready) }
    }

    /// Cold launch via `justtalk://record` (session wasn't warm): warm, then capture the first dictation.
    func begin() async {
        DictationHandoff.resetTrace()
        DictationHandoff.trace("app", "begin — cold launch")
        await warm()
        guard didWarm else { commit(.failed("Microphone access needed")); return }
        beginCapture()
    }

    /// Keyboard signalled `start` (session already alive) — begin a new dictation, no relaunch.
    private func beginCapture() {
        guard !capturing else { return }
        // Self-heal: the engine may have been stopped by an audio interruption (Apple grabbed the mic).
        // `running` is derived from `engine.isRunning`, so this is the TRUTH, not a stale flag. If it's
        // down, resume it here — no relaunch needed. This is the "Apple leaves, we pick the mic back up"
        // path: our app stayed alive, we just re-take the mic.
        if !flowAudio.running {
            DictationHandoff.trace("app", "beginCapture — engine down (post-interruption?) → resuming")
            do { try flowAudio.start() } catch {
                DictationHandoff.trace("app", "resume FAILED: \(error.localizedDescription)")
                commit(.failed("Couldn't start the mic")); return
            }
        }
        DictationHandoff.trace("app", "beginCapture (start signal received)")
        audio.reset()
        flowAudio.beginCapture()                  // engage the mic tap forwarding
        captureStart = Date()
        touch()
        commit(.capturing)                        // single write: phase + capturing + alive, together
        startMaxCaptureTimer()
    }

    /// Auto-finish a dictation that runs past `maxCaptureSeconds` so accumulated audio can't grow
    /// unbounded (and the take still gets transcribed rather than lost).
    private func startMaxCaptureTimer() {
        maxCaptureTimer?.cancel()
        maxCaptureTimer = Task { [weak self] in
            try? await Task.sleep(for: .seconds(Self.maxCaptureSeconds))
            guard let self, !Task.isCancelled, self.capturing else { return }
            DictationHandoff.trace("app", "max capture reached (\(Int(Self.maxCaptureSeconds))s) → auto-finish")
            self.endCaptureAndTranscribe()
        }
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
        maxCaptureTimer?.cancel(); maxCaptureTimer = nil
        flowAudio.endCapture()                    // stop forwarding; engine keeps running (keep-alive)
        touch()
        commit(.transcribing)                     // single write: phase + capturing=false + still alive
        let buffers = audio.snapshot()
        let sd = captureStart ?? Date()
        Task { await finish(buffers, sd) }
    }

    // MARK: - Audio interruptions (system dictation / calls / another app grabs the mic)

    private func registerInterruptionObserver() {
        guard interruptionObserver == nil else { return }
        interruptionObserver = NotificationCenter.default.addObserver(
            forName: AVAudioSession.interruptionNotification, object: nil, queue: .main) { [weak self] note in
            let info = note.userInfo
            Task { @MainActor in self?.handleInterruption(info) }
        }
    }

    /// Apple (or a call, or another app) took the mic. Our app and Apple's dictation are SEPARATE and
    /// take turns on the one mic — we do NOT tear ourselves down. iOS stops our engine (`running` goes
    /// false, derived from the engine), but the process stays alive and warm. We only:
    ///   1. salvage the in-flight take (transcribe what we buffered, hand it back) so nothing is lost, and
    ///   2. keep heartbeating so the keyboard still sees us alive.
    /// When Apple releases the mic (`.ended`) we resume the engine; and even if `.ended` never arrives,
    /// the next keyboard tap self-heals via `beginCapture` (which resumes a stopped engine). No relaunch,
    /// no `markSessionEnded` — that was the bug that force-cold-launched a still-alive app.
    private func handleInterruption(_ userInfo: [AnyHashable: Any]?) {
        guard let raw = userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt,
              let type = AVAudioSession.InterruptionType(rawValue: raw) else { return }
        switch type {
        case .began:
            DictationHandoff.trace("app", "audio interruption BEGAN (mic seized) — salvage, stay alive")
            if capturing {
                endCaptureAndTranscribe()   // transcribe what we have; writes pendingText for the keyboard
            }
            // Deliberately do NOT markSessionEnded / stop the app. We stay warm and wait our turn.
        case .ended:
            DictationHandoff.trace("app", "audio interruption ENDED — resuming engine")
            resumeAfterInterruption()
        @unknown default:
            break
        }
    }

    /// Apple released the mic — resume our engine so the session is immediately seamless again. Best
    /// effort: if resume fails (rare), `beginCapture` will retry on the next tap.
    private func resumeAfterInterruption() {
        guard didWarm, !flowAudio.running else { return }
        do {
            try flowAudio.start()
            touch(); commit(.ready)   // engine back → re-assert alive through the single writer
            DictationHandoff.trace("app", "resumed after interruption — session alive again")
        } catch {
            DictationHandoff.trace("app", "resume after interruption FAILED: \(error.localizedDescription)")
        }
    }

    private func finish(_ buffers: [AVAudioPCMBuffer], _ sd: Date) async {
        // Hold a background-task assertion so the transcribe completes even if iOS is backgrounding us
        // (e.g. right after an interruption seized the mic) — otherwise a long take is lost mid-transcribe.
        let bgTask = UIApplication.shared.beginBackgroundTask(withName: "just-talk-transcribe")
        defer {
            if phase == .transcribing { commit(.ready) }
            if bgTask != .invalid { UIApplication.shared.endBackgroundTask(bgTask) }
        }
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
        maxCaptureTimer?.cancel(); maxCaptureTimer = nil
        if let obs = interruptionObserver { NotificationCenter.default.removeObserver(obs); interruptionObserver = nil }
        didWarm = false
        flowAudio.stop()
        DictationHandoff.removeObserver(Unmanaged.passUnretained(self).toOpaque())
        // One write for the terminal state (preserving a failure message if we're already failed).
        if case .failed = phase { commit(phase) } else { commit(.ended) }
    }

    deinit {
        DictationHandoff.removeObserver(Unmanaged.passUnretained(self).toOpaque())
        if let obs = interruptionObserver { NotificationCenter.default.removeObserver(obs) }
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

    /// Records activity for the idle watchdog. Liveness itself is asserted by `commit` (which every
    /// caller here runs right after) + the heartbeat timer — never scattered.
    private func touch() { lastActivity = Date() }

    private func startHeartbeat() {
        heartbeat = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(Self.heartbeatSeconds))
                guard let self, !Task.isCancelled else { return }
                // Periodic liveness refresh — only while the state is actually alive (never asserts a
                // liveness the committed `phase` doesn't have).
                if self.phase.isAlive { DictationHandoff.markSessionAlive() }
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
            // No "End session" button — it killed the keep-alive and left the app unrevivable. The
            // session lives on its own (idle timeout / iOS reclaim); this screen just shows the cold-
            // launch moment and dismisses when you swipe back.
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
