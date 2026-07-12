import AVFoundation
import DictationCoreBase
import SwiftUI
import os

private let log = Logger(subsystem: DictationHandoff.logSubsystem, category: "FlowSession")

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
    /// A nonisolated projection of `phase.isAlive`, written ONLY in `commit` (single writer, can't drift).
    /// The heartbeat runs detached and reads this, so a long main-actor transcribe can never starve the
    /// liveness beat (docs warn the heartbeat must not share a thread a transcribe can block).
    nonisolated(unsafe) private var sessionAlive = false

    // Bound a SINGLE dictation's length so accumulated audio can't grow without limit. At 16 kHz mono
    // Float32 the buffer grows ~64 KB/s, so 10 min ≈ 38 MB — comfortably within the app's headroom, but
    // we auto-finish there so a forgotten mic can't accumulate for hours. (RAM is not the real limit;
    // this is a safety valve, and it also means a very long take still transcribes rather than being lost.)
    private static let maxCaptureSeconds = DictationLimits.maxSingleTakeSeconds   // shared with macOS
    private var maxCaptureTimer: Task<Void, Never>?

    /// Observes `AVAudioSession` interruptions (system dictation, an incoming call, another app grabbing
    /// the mic). Without this, an interruption silently stops our engine and the in-flight take is lost.
    private var interruptionObserver: NSObjectProtocol?

    /// Telemetry DB (App Group, shared with the Stats/Home tab). Opened lazily — see `telemetryStore()`.
    private var telemetry: TelemetryStore?

    /// Durable custom-vocabulary store (App Group, shared with the Words tab). Read fresh per dictation
    /// so edits in the app take effect immediately.
    private let vocabStore = (try? VocabularyStore.iOSURL()).map { VocabularyStore(url: $0) }
    private func userVocab() -> [String] { vocabStore?.load() ?? [] }

    init() {
        let pack = CleanupPackLoader.load()
        cleanupPack = pack
        // Foundation Models cleanup (Apple's on-device LLM) to match the Mac's accuracy — the raw STT is
        // the same SpeechAnalyzer, but rule-based cleanup left the phone looking far worse. The factory
        // falls back to rule-based if FM isn't available (older device / no Apple Intelligence). The
        // container app has the memory headroom for it (unlike the 70 MB keyboard extension).
        // Shared default cleanup config (.foundationModels, .full) — same aggressiveness as macOS.
        cleanup = TextCleanupFactory.make(.default, pack: pack)
        if #available(iOS 26.0, *) {
            transcriber = AppleAnalyzerTranscriber(localeIdentifier: SpeechDefaults.locale)
        } else {
            transcriber = AppleSpeechTranscriber(language: SpeechDefaults.locale)
        }
        // Audio-thread callbacks. FlowSessionAudio only fires these WHILE capturing (it gates on its own
        // `capturing` flag before calling out), so there's no second gate here — one source of truth.
        // Buffers append directly on the audio thread: `AudioBox` is lock-protected, so no main-actor hop
        // (which allocated a Task per buffer and gave no cross-Task ordering guarantee). Level touches a
        // @Published, so it hops. `writeLevel(0)` on stop is handled by `commit`.
        flowAudio.onBuffer = { [audio] buf in audio.append(buf) }
        flowAudio.onLevel = { [weak self] lvl in
            Task { @MainActor in
                guard let self else { return }
                self.level = lvl
                DictationHandoff.writeLevel(lvl)
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
        sessionAlive = v.alive                          // nonisolated mirror for the detached heartbeat
        DictationHandoff.setCapturing(v.capturing)      // keyboard: "is a dictation live?"
        v.alive ? DictationHandoff.markSessionAlive()   // keyboard: seamless-signal vs cold-launch
                : DictationHandoff.markSessionEnded()
        if !v.capturing { DictationHandoff.writeLevel(0) }    // no live level unless capturing
    }

    // MARK: - The single transition (State pattern: decide → move → entry action)
    //
    // This is the ONE place the app's phase changes. Inputs are an `Event` (a Darwin signal from the
    // keyboard, an audio interruption, or a lifecycle tick) plus the CURRENT phase. From those it decides
    // the next state, performs that state's action (start/stop the mic, kick off transcription), and
    // `commit`s the new phase — and `commit` applies the state's IPC-variable footprint. `commit` is
    // called from NOWHERE else, so "which state we're in" and "what the shared variables say" can never
    // disagree. This is the exact mirror of the keyboard, whose single transition is `render`
    // (variables → one `KeyboardPresentation`).

    enum Event {
        case warming                // begin warming (clear any stale state from a killed session)
        case warmed                 // warm() finished: model loaded, mic granted, engine up
        case warmFailed(String)     // warm() couldn't get the mic / engine
        case start                  // keyboard START signal (or cold begin) — begin a dictation
        case stop                   // keyboard STOP signal (or max-length auto-finish) — end + transcribe
        case transcribed            // transcription finished (text handed back)
        case interruptionBegan      // another process took the mic
        case interruptionEnded      // the mic was released
        case end                    // idle timeout / view closed — tear the session down
    }

    /// The sole transition function. See the note above — the only caller of `commit`.
    private func transition(on event: Event) {
        switch (phase, event) {
        case (_, .warming):
            commit(.warming)

        case (_, .warmed):
            commit(.ready)

        case (_, .warmFailed(let msg)):
            commit(.failed(msg))

        case (_, .start):
            guard phase != .capturing else { return }          // already recording — ignore
            guard resumeEngineIfNeeded() else { commit(.failed("Couldn't start the mic")); return }
            startCaptureAudio()
            commit(.capturing)

        case (.capturing, .stop):
            stopCaptureAudio()
            commit(.transcribing)
            let buffers = audio.snapshot()
            let sd = captureStart ?? Date()
            Task { await transcribe(buffers, from: sd) }

        case (_, .stop):
            DictationHandoff.trace("app", "stop but not capturing (ignored)")

        case (_, .interruptionBegan):
            // Apple / another app grabbed the mic. Salvage an in-flight take (re-enter via .stop), then
            // STAY alive — no teardown, no markSessionEnded. We wait our turn on the shared mic.
            DictationHandoff.trace("app", "interruption BEGAN — salvage, stay alive")
            if phase == .capturing { transition(on: .stop) }

        case (_, .interruptionEnded):
            DictationHandoff.trace("app", "interruption ENDED — resume engine")
            // Best-effort resume; if it fails, `beginCapture` self-heals on the next tap, so a swallow is fine.
            if !flowAudio.running { try? flowAudio.start() }
            if didWarm { commit(.ready) }

        case (_, .transcribed):
            if phase == .transcribing { commit(.ready) }

        case (_, .end):
            teardownSession()
            if case .failed = phase { commit(phase) } else { commit(.ended) }
        }
    }

    // MARK: - State actions (side effects only — never write state; `transition` commits it)

    /// Ensure the engine is actually running before capturing (an interruption may have stopped it).
    /// `running` is derived from `engine.isRunning`, so this checks reality, not a cached flag.
    private func resumeEngineIfNeeded() -> Bool {
        if flowAudio.running { return true }
        DictationHandoff.trace("app", "engine down (post-interruption?) → resuming")
        do { try flowAudio.start(); return true }
        catch { DictationHandoff.trace("app", "resume FAILED: \(error.localizedDescription)"); return false }
    }

    private func startCaptureAudio() {
        DictationHandoff.clearNoResult()  // fresh take — drop any prior "nothing came of that" flag
        audio.reset()
        flowAudio.beginCapture()          // engage mic-tap forwarding
        captureStart = Date()
        touch()
        startMaxCaptureTimer()
    }

    private func stopCaptureAudio() {
        maxCaptureTimer?.cancel(); maxCaptureTimer = nil
        flowAudio.endCapture()            // stop forwarding; engine keeps running (keep-alive)
        touch()
    }

    // MARK: - Session lifecycle

    /// Warm the session WITHOUT recording: load the model, take mic permission, start the silent
    /// keep-alive so the app stays resident. Called during onboarding (and on app foreground) so the
    /// FIRST real dictation is already hot — no cold launch, no app switch. Idempotent. All state moves
    /// go through `transition`.
    func warm() async {
        guard !didWarm else { return }
        didWarm = true
        transition(on: .warming)               // clears any stale capturing/alive from a killed session
        registerObservers()
        DictationHandoff.trace("app", "warm: loading transcriber…")
        do { try await transcriber.load() } catch {
            log.error("load: \(error.localizedDescription, privacy: .public)")
        }
        // Custom vocabulary: seed on first run, then bias the recognizer. (AppleAnalyzer ignores the
        // bias on iOS 26, so the real win is the cleanup spelling map applied in `transcribe`.)
        if let vs = vocabStore { Vocabulary.ensureSeeded(vs) }
        transcriber.setVocabularyBias(Vocabulary.biasTerms(userVocab()))
        DictationHandoff.trace("app", "warm: requesting mic permission…")
        guard await Self.requestMicPermission() else {
            DictationHandoff.trace("app", "mic permission denied")
            didWarm = false
            transition(on: .warmFailed("Microphone access needed"))
            return
        }
        DictationHandoff.trace("app", "warm: starting flow audio…")
        do {
            try flowAudio.start()   // one engine: silent keep-alive + always-installed mic tap
        } catch {
            DictationHandoff.trace("app", "flow audio start FAILED: \(error.localizedDescription)")
            didWarm = false
            transition(on: .warmFailed("Couldn't start the mic"))
            return
        }
        DictationHandoff.trace("app", "warm — keep-alive started, session alive")
        registerInterruptionObserver()
        startHeartbeat()
        startIdleTimer()
        // Privacy retention parity with macOS: purge transcripts older than the shared window (iOS was
        // never purging — history grew unbounded). Runs once per warm, off the hot path.
        if let store = telemetryStore() {
            Task { try? await store.purge(olderThanDays: TelemetryStore.retentionDays) }
        }
        transition(on: .warmed)
    }

    /// Cold launch via `justtalk://record` (session wasn't warm): warm, then capture the first dictation.
    func begin() async {
        DictationHandoff.resetTrace()
        DictationHandoff.trace("app", "begin — cold launch")
        await warm()
        guard didWarm else { return }   // warm already emitted .warmFailed
        transition(on: .start)
    }

    /// Auto-finish a dictation that runs past `maxCaptureSeconds` so accumulated audio can't grow
    /// unbounded (and the take still gets transcribed rather than lost).
    private func startMaxCaptureTimer() {
        maxCaptureTimer?.cancel()
        maxCaptureTimer = Task { [weak self] in
            try? await Task.sleep(for: .seconds(Self.maxCaptureSeconds))
            guard let self, !Task.isCancelled, self.phase == .capturing else { return }
            DictationHandoff.trace("app", "max capture reached (\(Int(Self.maxCaptureSeconds))s) → auto-finish")
            self.transition(on: .stop)
        }
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

    /// Apple (or a call, or another app) took/released the mic — feed it into the single `transition`.
    /// Our app and Apple's dictation are separate and take turns on the one mic; we never tear down here.
    private func handleInterruption(_ userInfo: [AnyHashable: Any]?) {
        guard let raw = userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt,
              let type = AVAudioSession.InterruptionType(rawValue: raw) else { return }
        switch type {
        case .began: transition(on: .interruptionBegan)
        case .ended: transition(on: .interruptionEnded)
        @unknown default: break
        }
    }

    /// The `.transcribing` state's action: turn buffered audio into text and hand it back, then emit
    /// `.transcribed` so `transition` returns us to `.ready`.
    private func transcribe(_ buffers: [AVAudioPCMBuffer], from sd: Date) async {
        // Hold a background-task assertion so the transcribe completes even if iOS is backgrounding us
        // (e.g. right after an interruption seized the mic) — otherwise a long take is lost mid-transcribe.
        let bgTask = UIApplication.shared.beginBackgroundTask(withName: "just-talk-transcribe")
        defer {
            transition(on: .transcribed)
            if bgTask != .invalid { UIApplication.shared.endBackgroundTask(bgTask) }
        }
        do {
            let r = try await transcriber.transcribe(buffers: buffers, audioStartDate: sd)
            let raw = r.text
            var text = raw
            var didClean = false
            if !text.isEmpty,
               text.split(whereSeparator: \.isWhitespace).count >= cleanupPack.minWordsForCleanup {
                // Forced-spelling map from the user's vocabulary — where custom terms actually land on
                // iOS (names/jargon get the right casing/spelling even when the recognizer mishears).
                let req = CleanupRequest(rawText: text, level: CleanupConfig.default.level,
                                         vocab: Vocabulary.spellingMap(userVocab()))
                text = (await cleanup.clean(req)).cleanedText
                didClean = true
            }
            guard !text.isEmpty else {
                DictationHandoff.trace("app", "transcribe → EMPTY (nothing heard)")
                DictationHandoff.signalNoResult()   // tell the keyboard fast (no 20s "Transcribing…" hang)
                return
            }
            transcript = text
            dictationCount += 1
            // Write pendingText only. The app NEVER signals the keyboard (per the architecture doc) —
            // the keyboard polls this. A `done` Darwin signal used to live here; it caused a stale-
            // instance race and is deliberately gone.
            DictationHandoff.write(text)
            DictationHandoff.trace("app", "transcribe → \(text.count) chars, wrote pendingText")
            await recordTelemetry(result: r, finalText: text, rawText: raw, didClean: didClean)
        } catch {
            DictationHandoff.trace("app", "transcribe ERROR: \(error.localizedDescription)")
            DictationHandoff.signalNoResult()   // don't leave the keyboard hanging on a failed take
            log.error("transcribe: \(error.localizedDescription, privacy: .public)")
        }
    }

    /// Persist this dictation to the shared telemetry DB so the app's Home/Stats tab has real data.
    /// iOS was never writing telemetry (only macOS did), so every stat showed zero. We write to the
    /// SAME App-Group database the Stats tab reads (`iOSDatabaseURL`). The container app has the room
    /// for GRDB (unlike the 70 MB keyboard), and this runs off the hot path (after handoff).
    private func recordTelemetry(result r: TranscriptionResult, finalText: String,
                                 rawText: String, didClean: Bool) async {
        guard let store = telemetryStore() else { return }
        let record = TranscriptRecord(
            platform: "ios",
            audioDurationMs: r.audioDurationMs,
            transcriptText: finalText,
            whisperkitConfidence: r.confidence,
            latencyMs: r.latencyMs,
            modelTier: "\(r.provider.rawValue)/\(r.model)",
            frontmostApp: nil,                              // a keyboard can't know the host app
            rawText: rawText,
            cleanupLevel: didClean ? CleanupLevel.light.rawValue : CleanupLevel.off.rawValue,
            cleanupProvider: didClean ? "foundationModels" : nil
        )
        do { try await store.save(record) }
        catch { DictationHandoff.trace("app", "telemetry save failed: \(error.localizedDescription)") }

        // Publish the compact stats summary for the keyboard's idle carousel (words / WPM / streak).
        if let totals = try? await store.fetchUsageTotals(),
           let streak = try? await store.currentStreakDays() {
            DictationHandoff.writeStats(words: totals.totalWords, wpm: totals.wordsPerMinute, streak: streak)
        }
    }

    /// Lazily opened telemetry store (App Group DB shared with the Stats tab). Opened once, reused.
    private func telemetryStore() -> TelemetryStore? {
        if let telemetry { return telemetry }
        do {
            telemetry = try TelemetryStore(databaseURL: TelemetryStore.iOSDatabaseURL())
            return telemetry
        } catch {
            DictationHandoff.trace("app", "telemetry open failed: \(error.localizedDescription)")
            return nil
        }
    }

    /// Tear the session down: stop the engine, clear the heartbeat so the next keyboard tap relaunches
    /// (cold path). Called on idle timeout or when the view closes. Routes through the single transition.
    func endSession() { transition(on: .end) }

    /// The `.end` state's action: stop everything. State itself is committed by `transition`.
    private func teardownSession() {
        idleTimer?.cancel(); idleTimer = nil
        heartbeat?.cancel(); heartbeat = nil
        maxCaptureTimer?.cancel(); maxCaptureTimer = nil
        if let obs = interruptionObserver { NotificationCenter.default.removeObserver(obs); interruptionObserver = nil }
        didWarm = false
        flowAudio.stop()
        DictationHandoff.removeObserver(Unmanaged.passUnretained(self).toOpaque())
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
            Task { @MainActor in m.transition(on: .start) }
        }
        DictationHandoff.observe(DictationHandoff.stopNotification, observer: me) { _, obs, _, _, _ in
            guard let obs else { return }
            let m = Unmanaged<RecordSessionModel>.fromOpaque(obs).takeUnretainedValue()
            Task { @MainActor in m.transition(on: .stop) }
        }
    }

    // MARK: - Heartbeat + idle

    /// Records activity for the idle watchdog. Liveness itself is asserted by `commit` (which every
    /// caller here runs right after) + the heartbeat timer — never scattered.
    private func touch() { lastActivity = Date() }

    private func startHeartbeat() {
        // DETACHED so a long transcribe on the main actor can't starve the beat. It only reads the
        // nonisolated `sessionAlive` mirror and calls the nonisolated `markSessionAlive`.
        heartbeat = Task.detached { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(Self.heartbeatSeconds))
                guard let self, !Task.isCancelled else { return }
                if self.sessionAlive { DictationHandoff.markSessionAlive() }   // refresh only while alive
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
                if self.phase != .capturing, Date().timeIntervalSince(self.lastActivity) > Self.idleTimeoutSeconds {
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
    /// Brand gold (shared BrandPalette) — matches the keyboard + Mac pill instead of a lone red.
    private let gold = Color(red: BrandPalette.goldRGB.red, green: BrandPalette.goldRGB.green, blue: BrandPalette.goldRGB.blue)
    var body: some View {
        ZStack {
            Circle().fill(gold.opacity(0.15))
                .frame(width: 140 + CGFloat(min(1, level * 6)) * 60, height: 140 + CGFloat(min(1, level * 6)) * 60)
                .animation(.easeOut(duration: 0.1), value: level)
            Image(systemName: "mic.fill").font(.system(size: 44)).foregroundStyle(gold)
        }.frame(height: 220)
    }
}
