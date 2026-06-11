import AppKit
import AVFoundation
import SwiftUI
import DictationCore

// MARK: - DictationState

public enum DictationState {
    case idle, recording, transcribing
}

public enum HotkeyMode: String, CaseIterable {
    case hold, toggle
    public var displayName: String {
        switch self {
        case .hold:   return "Hold to talk"
        case .toggle: return "Sticky — tap to start, tap to stop"
        }
    }
}

/// Per-app cleanup override: e.g. turn cleanup Off in code editors/terminals.
public struct AppCleanupProfile: Codable, Identifiable, Equatable {
    public var bundleId: String
    public var name: String
    public var level: CleanupLevel
    public var id: String { bundleId }
}

// MARK: - BufferAccumulator

/// Thread-safe, ORDER-PRESERVING store for captured audio buffers. The audio tap delivers
/// buffers serially on its render thread, so appending under a lock keeps them in temporal
/// order. This replaces a `Task { @MainActor in append }` per buffer, which did NOT preserve
/// order — independent tasks can run out of sequence on the actor, scrambling long recordings
/// into garbage audio (short ones happened to stay ordered, hence "short worked, long failed").
final class BufferAccumulator: @unchecked Sendable {
    private let lock = NSLock()
    private var buffers: [AVAudioPCMBuffer] = []

    func append(_ buffer: AVAudioPCMBuffer) {
        lock.lock(); buffers.append(buffer); lock.unlock()
    }
    func snapshot() -> [AVAudioPCMBuffer] {
        lock.lock(); defer { lock.unlock() }; return buffers
    }
    func reset() {
        lock.lock(); buffers.removeAll(); lock.unlock()
    }
}

// MARK: - AppState

@MainActor
public final class AppState: NSObject, ObservableObject {

    // MARK: - Published

    @Published public var dictationState: DictationState = .idle
    @Published public var recentTranscripts: [TranscriptRecord] = []
    @Published public var weeklyStats: WeeklyStats = .empty
    @Published public var usageTotals: UsageTotals = .empty
    @Published public var engineLoaded: Bool = false
    @Published public var statusMessage: String = "Ready"
    @Published public var autoPaste: Bool = true
    @Published public private(set) var sttConfig: STTConfig = .default
    @Published public private(set) var cleanupConfig: CleanupConfig = .default
    @Published public private(set) var vocabulary: [String] = []
    @Published public private(set) var hotkeyMode: HotkeyMode = .toggle
    @Published public var soundEnabled: Bool = false
    @Published public private(set) var launchAtLogin: Bool = false
    @Published public private(set) var appProfiles: [AppCleanupProfile] = []

    // Onboarding / permissions (drives the setup wizard's live ticks)
    @Published private(set) var hotkeyConfig: HotkeyConfig = .fn
    @Published public var micGranted: Bool = false
    @Published public var accessibilityGranted: Bool = false
    /// Input Monitoring — required for the keyboard event tap on modern macOS, distinct
    /// from Accessibility. Without it the tap can't enable and the hotkey degrades.
    @Published public var inputMonitoringGranted: Bool = false
    /// Set true the moment the configured key is received during the wizard "test" step.
    @Published public var hotkeyTestPassed: Bool = false

    // MARK: - Permissions / onboarding

    let permissions = PermissionsService()
    private let onboardingWindow = OnboardingWindow()
    private var permissionTimer: Timer?

    /// The permissions Just Talk genuinely needs to function: mic to hear you, plus
    /// Accessibility (to paste) and Input Monitoring (to read the activation key).
    var requiredPermissionsGranted: Bool { micGranted && accessibilityGranted && inputMonitoringGranted }

    private var hasCompletedOnboarding: Bool {
        get { UserDefaults.standard.bool(forKey: "hasCompletedOnboarding") }
        set { UserDefaults.standard.set(newValue, forKey: "hasCompletedOnboarding") }
    }

    private var readyMessage: String {
        let verb = hotkeyMode == .toggle ? "tap" : "hold"
        return "Ready — \(verb) \(hotkeyConfig.shortName) to talk"
    }

    // MARK: - Engines

    private let recordingEngine = RecordingEngine()
    private var transcriber: any SpeechTranscriber
    private var cleanup: any TextCleanup
    private let cleanupPack: CleanupPack
    private let clipboardPaster = ClipboardPaster()
    private let recordingHUD = RecordingHUD()
    // Persists the last few raw recordings so a bad/garbled transcription can be re-run
    // without re-speaking (see RecordingStore — a cross-platform contract).
    private let recordingStore: RecordingStore? = try? FileRecordingStore.macOS(maxRecordings: 5)
    private var telemetryStore: TelemetryStore?
    private var hotkeyManager: HotkeyManager?

    // Audio buffer accumulation — order-preserving + thread-safe (see BufferAccumulator).
    // nonisolated so the audio-thread delegate can append without an actor hop.
    private nonisolated let audio = BufferAccumulator()
    private var recordingStartDate: Date?
    // Loudest mic level seen during the current recording — drives the live "too quiet" HUD
    // warning. If even the peak stays below this after a couple seconds, the mic is too low.
    private var recordingPeakLevel: Float = 0
    private static let lowInputPeakThreshold: Float = 0.04
    // Frontmost app at the moment recording started — the dictation target. Captured up
    // front so per-app cleanup + telemetry resolve against the right app even if the user
    // switches windows during the async transcribe.
    private var recordingFrontmostApp: String?
    // The actual app object frontmost at record start — re-activated before paste so the
    // text lands where the user intended even if focus moved (e.g. the menu-bar panel).
    private var recordingTargetApp: NSRunningApplication?
    // Audio preserved when transcription fails, so the dictation can be retried instead of
    // silently lost (Wispr-style). Cleared on success or explicit discard.
    private var pendingAudio: (buffers: [AVAudioPCMBuffer], startDate: Date)?

    // Live HUD preview (E1): a separate tiny model transcribes accumulated audio while
    // recording, for display only. The batch path remains the source of truth.
    private var previewTranscriber: WhisperKitTranscriber?
    private var previewTask: Task<Void, Never>?

    // Correction window: after a transcript lands, ⌘⇧Z marks it corrected for 5s
    private var correctionWindowTask: Task<Void, Never>?
    private var correctionWindowOpen = false
    // Monotonic token so a superseded STT load can't apply state for an old switch.
    private var sttLoadGeneration = 0

    // MARK: - Init

    public override init() {
        let defaults = UserDefaults.standard
        let provider = STTProvider(rawValue: defaults.string(forKey: "sttProvider") ?? "") ?? .whisperKit
        // Default to small.en: measured ~4.5× faster than large-v3-turbo (0.9s vs 4s on a 10s
        // clip) AND more accurate on English vocab (6/6 vs 5/6 — it got "Wispr Flow" right where
        // the multilingual large model produced "Wisp of Flow"). Users who need non-English can
        // still pick a multilingual tier in Settings.
        let model = defaults.string(forKey: "sttModel")
            ?? defaults.string(forKey: "modelTier")          // legacy key from PRD 0003
            ?? ModelTier.smallEn.rawValue
        let config = STTConfig(provider: provider, model: model)
        self.sttConfig = config
        self.transcriber = SpeechTranscriberFactory.make(config)

        // Cleanup layer (the on-device LLM brain).
        let pack = CleanupPackLoader.load()
        let cleanupProvider = CleanupProvider(rawValue: defaults.string(forKey: "cleanupProvider") ?? "")
            ?? CleanupConfig.default.provider
        let cleanupLevel = CleanupLevel(rawValue: defaults.string(forKey: "cleanupLevel") ?? "")
            ?? CleanupConfig.default.level
        let cleanupConfig = CleanupConfig(provider: cleanupProvider, level: cleanupLevel)
        self.cleanupPack = pack
        self.cleanupConfig = cleanupConfig
        self.cleanup = TextCleanupFactory.make(cleanupConfig, pack: pack)

        self.autoPaste = defaults.object(forKey: "autoPaste") as? Bool ?? true
        self.vocabulary = defaults.stringArray(forKey: "vocabulary") ?? []
        self.hotkeyMode = HotkeyMode(rawValue: defaults.string(forKey: "hotkeyMode") ?? "") ?? .toggle
        self.hotkeyConfig = HotkeyConfig.load(from: defaults)
        self.soundEnabled = defaults.object(forKey: "soundEnabled") as? Bool ?? false
        self.launchAtLogin = LoginItem.isEnabled
        if let data = defaults.data(forKey: "appProfiles"),
           let profiles = try? JSONDecoder().decode([AppCleanupProfile].self, from: data) {
            self.appProfiles = profiles
        }
        super.init()
        recordingEngine.delegate = self
        transcriber.setVocabularyBias(vocabulary)

        Task { await setup() }
    }

    // MARK: - Setup

    private func setup() async {
        // 1. Read permission status WITHOUT prompting (the fix for "Settings opens out of
        //    the blue"). Prompts now happen only from explicit onboarding buttons.
        refreshPermissions()

        // 2. Build the hotkey listener; only install the event tap once Accessibility is
        //    granted (refreshPermissions starts it on the grant transition).
        hotkeyManager = HotkeyManager(delegate: self, config: hotkeyConfig)
        if accessibilityGranted && inputMonitoringGranted { hotkeyManager?.start() }

        // 3. Poll permissions so the wizard's ticks update live as the user grants them in
        //    System Settings, and so a later revoke is noticed. Also re-check on activation.
        startPermissionPolling()
        NotificationCenter.default.addObserver(
            self, selector: #selector(appDidBecomeActive),
            name: NSApplication.didBecomeActiveNotification, object: nil)

        // 4. Show onboarding if it's never been completed or a required permission is missing.
        if !hasCompletedOnboarding || !requiredPermissionsGranted {
            showOnboardingWindow()
        }

        // 5. Load the speech model (downloads on first run — show progress).
        let modelName = sttConfig.modelDisplayName
        statusMessage = "Preparing \(modelName)…"
        do {
            try await transcriber.load { [weak self] fraction in
                guard let self else { return }
                if fraction < 1.0 {
                    self.statusMessage = "Downloading \(modelName)… \(Int(fraction * 100))%"
                } else {
                    self.statusMessage = "Loading \(modelName)…"
                }
            }
            engineLoaded = transcriber.isLoaded
            statusMessage = readyMessage
        } catch {
            statusMessage = "Model load failed: \(error.localizedDescription)"
            return
        }

        // 6. Open telemetry store (non-fatal).
        do {
            let url = try TelemetryStore.macOSDatabaseURL()
            telemetryStore = try TelemetryStore(databaseURL: url)
            await refreshTranscripts()
        } catch {
            // Non-fatal — app still works without telemetry
        }

        // 7. Load the tiny live-preview model in the background (best-effort).
        Task { [weak self] in
            let preview = WhisperKitTranscriber(modelTier: .tinyEn)
            try? await preview.load()
            if preview.isLoaded { self?.previewTranscriber = preview }
        }
    }

    // MARK: - Permissions & onboarding

    private func startPermissionPolling() {
        permissionTimer?.invalidate()
        let timer = Timer(timeInterval: 1.2, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.refreshPermissions() }
        }
        RunLoop.main.add(timer, forMode: .common)
        permissionTimer = timer
    }

    /// Non-prompting status read. Installs the hotkey tap the moment BOTH tap permissions
    /// (Accessibility + Input Monitoring) are present — installing before then creates a tap
    /// that can't enable and drives a rebuild/prompt loop.
    func refreshPermissions() {
        micGranted = permissions.micStatus == .granted
        let couldInstall = accessibilityGranted && inputMonitoringGranted
        accessibilityGranted = permissions.accessibilityGranted
        inputMonitoringGranted = permissions.inputMonitoringGranted
        if accessibilityGranted && inputMonitoringGranted && !couldInstall {
            hotkeyManager?.start()
        }
    }

    @objc private func appDidBecomeActive() { refreshPermissions() }

    func showOnboardingWindow() {
        refreshPermissions()
        onboardingWindow.show(appState: self)
    }

    /// Trigger the system mic prompt (only on a wizard button tap). Guarded so rapid taps
    /// can't stack multiple system prompts while one is already pending.
    private var micRequestInFlight = false
    func requestMicrophone() {
        guard !micRequestInFlight else { return }
        micRequestInFlight = true
        Task {
            _ = await permissions.requestMic()
            micRequestInFlight = false
            refreshPermissions()
        }
    }

    /// Trigger the Accessibility prompt + open the pane (only on a wizard button tap).
    func requestAccessibility() {
        permissions.promptAccessibility()
    }

    /// Trigger the Input Monitoring prompt / open the pane (only on a wizard button tap).
    func requestInputMonitoring() {
        _ = permissions.requestInputMonitoring()
        refreshPermissions()
    }

    /// Begin the wizard "press your key to test" step: route presses to a confirmation
    /// signal only (no recording) so we can prove the key reaches us — the definitive
    /// conflict check, since macOS won't tell us who else holds the key.
    func beginHotkeyTest() {
        hotkeyTestPassed = false
        hotkeyManager?.isTesting = true
        if accessibilityGranted && inputMonitoringGranted { hotkeyManager?.start() }
    }

    func endHotkeyTest() {
        hotkeyManager?.isTesting = false
    }

    func completeOnboarding() {
        hasCompletedOnboarding = true
        endHotkeyTest()
        onboardingWindow.close()
    }

    /// Relaunch the app. `AXIsProcessTrusted()` frequently does not refresh inside a running
    /// (agent) process after the user enables Accessibility — a fresh process always reads
    /// the current grant. This is the reliable escape hatch the wizard offers.
    func relaunch() {
        let path = Bundle.main.bundlePath
        let task = Process()
        task.executableURL = URL(fileURLWithPath: "/usr/bin/open")
        task.arguments = ["-n", path]
        try? task.run()
        NSApp.terminate(nil)
    }

    // MARK: - Recording control (called by HotkeyManager)

    func startRecording() {
        guard dictationState == .idle, engineLoaded else { return }
        // Never start the audio engine without mic permission — doing so re-triggers the
        // system mic prompt on EVERY activation-key press (the "mic window 4 times" bug).
        // Surface onboarding so the user grants it once; the engine is the only thing that
        // touches the mic, so gating here is the single chokepoint.
        guard permissions.micStatus == .granted else {
            micGranted = false
            statusMessage = "Microphone access needed — grant it to dictate"
            showOnboardingWindow()
            return
        }
        audio.reset()
        recordingPeakLevel = 0
        recordingStartDate = Date()
        // Warm the cleanup model now, while the user talks, so the cleanup at stop is fast
        // (~355ms warm vs ~1.3s cold). No-op for non-LLM cleanup providers.
        cleanup.prewarm()
        let frontApp = NSWorkspace.shared.frontmostApplication
        recordingFrontmostApp = frontApp?.bundleIdentifier
        recordingTargetApp = frontApp
        do {
            try recordingEngine.start()
            dictationState = .recording
            statusMessage = "Recording…"
            recordingHUD.show(phase: .recording, label: "Listening…")
            playSound("Tink")
            startPreviewLoop()
        } catch {
            statusMessage = "Failed to start: \(error.localizedDescription)"
        }
    }

    func stopRecordingAndTranscribe() {
        guard dictationState == .recording else { return }
        recordingEngine.stop()
        previewTask?.cancel()
        previewTask = nil
        dictationState = .transcribing
        statusMessage = "Transcribing…"
        recordingHUD.setPhase(.processing, label: "Transcribing…")
        let buffers = audio.snapshot()
        let startDate = recordingStartDate ?? Date()
        persistRecording(buffers: buffers, recordedAt: startDate)
        Task { await performTranscription(buffers: buffers, audioStartDate: startDate) }
    }

    /// Persist the raw audio (last 5 kept) so a junk/failed transcription is recoverable via
    /// "Re-transcribe last recording" — the user never has to re-speak. File I/O runs off the
    /// main actor; the store is Sendable.
    private func persistRecording(buffers: [AVAudioPCMBuffer], recordedAt: Date) {
        guard let store = recordingStore else { return }
        let samples = AudioSampleBridge.flatten(buffers)
        guard !samples.isEmpty else { return }
        let sampleRate = RecordingEngine.targetSampleRate
        Task.detached { try? store.save(samples: samples, sampleRate: sampleRate, recordedAt: recordedAt) }
    }

    /// Re-run transcription on the most recent saved recording (menu action). Recovers a
    /// dictation that produced junk — now through the fixed long-audio (VAD-chunked) path.
    func reTranscribeLastRecording() {
        guard dictationState == .idle, engineLoaded else { return }
        guard let store = recordingStore, let last = store.recent().first else {
            statusMessage = "No recent recording to re-transcribe"
            return
        }
        dictationState = .transcribing
        statusMessage = "Re-transcribing last recording…"
        recordingHUD.show(phase: .processing, label: "Re-transcribing…")
        Task {
            let loaded = await Task.detached { try? store.loadSamples(id: last.id) }.value
            guard let loaded,
                  let buffer = AudioSampleBridge.makeBuffer(samples: loaded.samples,
                                                            sampleRate: loaded.sampleRate) else {
                statusMessage = "Couldn't load the last recording"
                finishIdle()
                return
            }
            await performTranscription(buffers: [buffer], audioStartDate: last.recordedAt)
        }
    }

    /// Toggle-mode entry: tap to start, tap to stop.
    func toggleRecording() {
        switch dictationState {
        case .idle:         startRecording()
        case .recording:    stopRecordingAndTranscribe()
        case .transcribing: break
        }
    }

    private func playSound(_ name: String) {
        guard soundEnabled, let sound = NSSound(named: name) else { return }
        sound.play()
    }

    // MARK: - Live HUD preview (E1)

    /// While recording, periodically transcribe the accumulated audio with the tiny
    /// preview model and show it in the HUD. Display only — never touches the paste path.
    private func startPreviewLoop() {
        guard previewTranscriber != nil else { return }
        previewTask?.cancel()
        previewTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .milliseconds(1200))
                if Task.isCancelled { return }
                await self?.runPreview()
            }
        }
    }

    private func runPreview() async {
        guard dictationState == .recording,
              let preview = previewTranscriber, preview.isLoaded else { return }
        let buffers = audio.snapshot()
        // Need ~0.6s of audio before a preview is meaningful.
        let frames = buffers.reduce(0) { $0 + Int($1.frameLength) }
        guard Double(frames) > RecordingEngine.targetSampleRate * 0.6 else { return }

        let result = try? await preview.transcribe(buffers: buffers, audioStartDate: recordingStartDate ?? Date())
        // Ignore a result that arrives after the loop was cancelled (recording ended).
        if let text = result?.text, !Task.isCancelled, dictationState == .recording {
            recordingHUD.update(previewText: text)
        }
    }

    // MARK: - Transcription

    private func performTranscription(buffers: [AVAudioPCMBuffer], audioStartDate: Date) async {
        // Transcribe with automatic retries — STT can fail transiently. The audio is preserved
        // so a failure is never silently lost (Wispr-style: keep audio, retry, then discard).
        let result: TranscriptionResult
        do {
            result = try await transcribeWithRetry(buffers: buffers, audioStartDate: audioStartDate)
        } catch TranscriptionError.emptyResult, TranscriptionError.noAudioData {
            handleEmpty(buffers: buffers, audioStartDate: audioStartDate)
            return
        } catch {
            failWithRetry(buffers: buffers, audioStartDate: audioStartDate, message: "Transcription failed")
            return
        }

        let rawText = result.text
        guard !rawText.isEmpty else {
            handleEmpty(buffers: buffers, audioStartDate: audioStartDate)
            return
        }

        // Resolve the effective cleanup level against the app frontmost at record start.
        let frontmostApp = recordingFrontmostApp
        let level = effectiveLevel(forBundleId: frontmostApp)

        // Cleanup pass (the Wispr brain). Never throws — falls back internally.
        let wordCount = rawText.split(whereSeparator: { $0.isWhitespace }).count
        let shouldClean = level != .off && wordCount >= cleanupPack.minWordsForCleanup
        let cleanupResult: CleanupResult?
        if shouldClean {
            statusMessage = "Cleaning…"
            recordingHUD.setPhase(.processing, label: "Cleaning…")
            cleanupResult = await cleanup.clean(
                CleanupRequest(
                    rawText: rawText,
                    level: level,
                    vocab: vocabularyMap,
                    commandGrammar: cleanupPack.commandGrammar,
                    profile: cleanupPack.profile
                )
            )
        } else {
            cleanupResult = nil
        }
        let finalText = cleanupResult?.cleanedText ?? rawText
        guard !finalText.isEmpty else { finishIdle(); return }

        // Write to clipboard and optionally paste
        let ap = autoPaste
        clipboardPaster.writeAndPaste(text: finalText, autoPaste: ap, targetApp: recordingTargetApp)
        playSound("Pop")

        // Build record — transcriptText is what was pasted; rawText keeps the pre-cleanup
        // STT output for the cross-platform learnings dataset.
        let record = TranscriptRecord(
            platform: "mac",
            audioDurationMs: result.audioDurationMs,
            transcriptText: finalText,
            whisperkitConfidence: result.confidence,
            latencyMs: result.latencyMs,
            modelTier: "\(result.provider.rawValue)/\(result.model)",
            frontmostApp: frontmostApp,
            rawText: rawText,
            cleanupLevel: shouldClean ? level.rawValue : CleanupLevel.off.rawValue,
            cleanupProvider: cleanupResult.map { $0.usedFallback ? "\($0.provider.rawValue)+fallback" : $0.provider.rawValue }
        )
        try? await telemetryStore?.save(record)

        recentTranscripts.insert(record, at: 0)
        if recentTranscripts.count > 5 { recentTranscripts.removeLast() }
        await refreshStats()

        // Open 5-second correction window (recentTranscripts.first is this record; nothing
        // between the insert and here mutates recentTranscripts).
        correctionWindowTask?.cancel()
        correctionWindowOpen = true
        correctionWindowTask = Task { [weak self] in
            do { try await Task.sleep(for: .seconds(5)) } catch { return }
            self?.correctionWindowOpen = false
        }

        // Success — drop the preserved audio and reset.
        pendingAudio = nil
        finishIdle()
    }

    /// Per-attempt cap on the STT engine. On-device transcription is normally faster than
    /// realtime; if it hangs past this, treat the attempt as failed so the dictation surfaces
    /// the retry-from-box affordance instead of leaving the HUD stuck on "Transcribing…".
    private static let transcribeTimeout: Double = 60

    /// Transcribe with automatic retries on transient failure. Genuine empty / no-audio is
    /// rethrown immediately (no point retrying silence); a timeout (likely a real hang) fails
    /// fast without burning the remaining retries.
    private func transcribeWithRetry(buffers: [AVAudioPCMBuffer], audioStartDate: Date,
                                     attempts: Int = 3) async throws -> TranscriptionResult {
        let transcriber = self.transcriber
        var lastError: Error?
        for attempt in 1...attempts {
            do {
                return try await withTimeout(seconds: Self.transcribeTimeout) {
                    try await transcriber.transcribe(buffers: buffers, audioStartDate: audioStartDate)
                }
            } catch TranscriptionError.emptyResult {
                throw TranscriptionError.emptyResult
            } catch TranscriptionError.noAudioData {
                throw TranscriptionError.noAudioData
            } catch is TimeoutError {
                lastError = TimeoutError()
                break
            } catch {
                lastError = error
                if attempt < attempts { try? await Task.sleep(for: .milliseconds(300)) }
            }
        }
        throw lastError ?? TranscriptionError.emptyResult
    }

    /// Empty transcript. If there was real audio, the user likely spoke and STT dropped it —
    /// surface a retry; if it was basically silence, reset quietly.
    private func handleEmpty(buffers: [AVAudioPCMBuffer], audioStartDate: Date) {
        let frames = buffers.reduce(0) { $0 + Int($1.frameLength) }
        if Double(frames) > RecordingEngine.targetSampleRate * 1.0 {
            failWithRetry(buffers: buffers, audioStartDate: audioStartDate, message: "Didn't catch that")
        } else {
            pendingAudio = nil
            finishIdle()
        }
    }

    /// Preserve the audio and show a retry affordance in the HUD instead of losing the dictation.
    private func failWithRetry(buffers: [AVAudioPCMBuffer], audioStartDate: Date, message: String) {
        pendingAudio = (buffers, audioStartDate)
        dictationState = .idle
        statusMessage = "\(message) — retry from the box"
        recordingHUD.showFailed(
            message: message,
            onRetry: { [weak self] in self?.retryPendingDictation() },
            onDismiss: { [weak self] in self?.discardPendingDictation() }
        )
    }

    /// Re-run transcription on the preserved audio (HUD "Retry").
    func retryPendingDictation() {
        guard let pending = pendingAudio else { return }
        dictationState = .transcribing
        statusMessage = "Retrying…"
        recordingHUD.setPhase(.processing, label: "Retrying…")
        Task { await performTranscription(buffers: pending.buffers, audioStartDate: pending.startDate) }
    }

    /// Discard the preserved audio (HUD "✕").
    func discardPendingDictation() {
        pendingAudio = nil
        finishIdle()
    }

    private func finishIdle() {
        dictationState = .idle
        statusMessage = readyMessage
        recordingHUD.hide()
    }

    // MARK: - Correction

    func markLastTranscriptCorrected() {
        // Only honour within the 5-second correction window
        guard correctionWindowOpen else { return }
        guard let record = recentTranscripts.first else { return }
        Task {
            try? await telemetryStore?.markCorrected(id: record.id, note: nil)
            if !recentTranscripts.isEmpty {
                recentTranscripts[0].wasCorrected = true
            }
        }
    }

    // MARK: - Settings

    /// Switch STT provider and/or model. Persists the choice, rebuilds the transcriber
    /// via the factory, and reloads with progress.
    func setSTTConfig(_ config: STTConfig) {
        guard config != sttConfig else { return }
        sttConfig = config
        let defaults = UserDefaults.standard
        defaults.set(config.provider.rawValue, forKey: "sttProvider")
        defaults.set(config.model, forKey: "sttModel")

        engineLoaded = false
        transcriber = SpeechTranscriberFactory.make(config)
        transcriber.setVocabularyBias(vocabulary)
        sttLoadGeneration += 1
        let token = sttLoadGeneration
        let modelName = config.modelDisplayName
        statusMessage = "Preparing \(modelName)…"
        Task {
            do {
                try await transcriber.load { [weak self] fraction in
                    // Ignore progress from a superseded switch.
                    guard let self, self.sttLoadGeneration == token else { return }
                    if fraction < 1.0 {
                        self.statusMessage = "Downloading \(modelName)… \(Int(fraction * 100))%"
                    } else {
                        self.statusMessage = "Loading \(modelName)…"
                    }
                }
                // A newer setSTTConfig may have superseded this one mid-load — don't
                // stomp its state. Keyed on the load token, not the config value, so
                // rapid same-value switches (A→B→A→B) are disambiguated.
                guard sttLoadGeneration == token else { return }
                engineLoaded = transcriber.isLoaded
                statusMessage = engineLoaded
                    ? readyMessage
                    : "\(config.provider.displayName) unavailable"
            } catch {
                guard sttLoadGeneration == token else { return }
                statusMessage = "Load failed: \(error.localizedDescription)"
            }
        }
    }

    func setAutoPaste(_ value: Bool) {
        autoPaste = value
        UserDefaults.standard.set(value, forKey: "autoPaste")
    }

    /// Switch cleanup provider and/or level. Persists and rebuilds the cleanup engine.
    func setCleanupConfig(_ config: CleanupConfig) {
        guard config != cleanupConfig else { return }
        cleanupConfig = config
        let defaults = UserDefaults.standard
        defaults.set(config.provider.rawValue, forKey: "cleanupProvider")
        defaults.set(config.level.rawValue, forKey: "cleanupLevel")
        cleanup = TextCleanupFactory.make(config, pack: cleanupPack)
    }

    /// Custom vocabulary (names/jargon): biases WhisperKit and forces spelling in cleanup.
    func setVocabulary(_ terms: [String]) {
        let cleaned = terms.map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
        vocabulary = cleaned
        UserDefaults.standard.set(cleaned, forKey: "vocabulary")
        transcriber.setVocabularyBias(cleaned)
    }

    func setHotkeyMode(_ mode: HotkeyMode) {
        hotkeyMode = mode
        UserDefaults.standard.set(mode.rawValue, forKey: "hotkeyMode")
    }

    /// Switch the activation key. Persists, reconfigures the live listener, and resets the
    /// wizard test state so the user re-confirms the new key.
    func setHotkey(_ config: HotkeyConfig) {
        guard config != hotkeyConfig else { return }
        hotkeyConfig = config
        config.save()
        hotkeyManager?.setConfig(config)
        hotkeyTestPassed = false
        if dictationState == .idle, engineLoaded { statusMessage = readyMessage }
    }

    func setSoundEnabled(_ value: Bool) {
        soundEnabled = value
        UserDefaults.standard.set(value, forKey: "soundEnabled")
    }

    func setLaunchAtLogin(_ value: Bool) {
        do {
            try LoginItem.setEnabled(value)
            launchAtLogin = LoginItem.isEnabled
        } catch {
            statusMessage = "Login item failed: \(error.localizedDescription)"
            launchAtLogin = LoginItem.isEnabled
        }
    }

    /// Forced-spelling map for cleanup: each vocab term maps to itself so the cleanup
    /// engine restores the exact casing/spelling after the LLM pass.
    private var vocabularyMap: [String: String] {
        Dictionary(vocabulary.map { ($0.lowercased(), $0) }, uniquingKeysWith: { _, b in b })
    }

    // MARK: - Per-app cleanup profiles (E2)

    /// Cleanup level for the given app: a per-app override if set, else the global level.
    func effectiveLevel(forBundleId bundleId: String?) -> CleanupLevel {
        guard let bundleId, let profile = appProfiles.first(where: { $0.bundleId == bundleId }) else {
            return cleanupConfig.level
        }
        return profile.level
    }

    func setAppProfile(bundleId: String, name: String, level: CleanupLevel) {
        if let idx = appProfiles.firstIndex(where: { $0.bundleId == bundleId }) {
            appProfiles[idx].level = level
        } else {
            appProfiles.append(AppCleanupProfile(bundleId: bundleId, name: name, level: level))
        }
        persistAppProfiles()
    }

    func removeAppProfile(bundleId: String) {
        appProfiles.removeAll { $0.bundleId == bundleId }
        persistAppProfiles()
    }

    private func persistAppProfiles() {
        if let data = try? JSONEncoder().encode(appProfiles) {
            UserDefaults.standard.set(data, forKey: "appProfiles")
        }
    }

    // MARK: - History (E3)

    func searchHistory(matching query: String) async -> [TranscriptRecord] {
        (try? await telemetryStore?.search(matching: query, limit: 200)) ?? []
    }

    /// Copy text to the clipboard for re-paste (used by the history window).
    func copyToClipboard(_ text: String) {
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(text, forType: .string)
    }

    // MARK: - Refresh helpers

    func refreshTranscripts() async {
        recentTranscripts = (try? await telemetryStore?.fetchRecent(limit: 5)) ?? []
    }

    func refreshStats() async {
        weeklyStats = (try? await telemetryStore?.fetchWeeklyStats()) ?? .empty
        usageTotals = (try? await telemetryStore?.fetchUsageTotals()) ?? .empty
    }
}

// MARK: - RecordingEngineDelegate

extension AppState: RecordingEngineDelegate {
    /// Called on an arbitrary audio queue — bridge back to MainActor.
    public nonisolated func recordingEngine(_ engine: RecordingEngine, didReceiveBuffer buffer: AVAudioPCMBuffer) {
        // Append synchronously, in arrival order, on the (serial) audio thread. No per-buffer
        // hop to the main actor — that reordered buffers and scrambled long recordings.
        audio.append(buffer)
    }

    /// VAD silence is intentionally NOT used to auto-stop. Recording is controlled by the
    /// activation key — release in hold mode, a second tap in sticky/toggle mode. Auto-stopping
    /// on a pause chopped sentences off mid-thought while the user was still holding the key.
    public nonisolated func recordingEngineDidDetectSilence(_ engine: RecordingEngine) {
        // no-op (kept for the delegate contract; VAD signal retained for future use)
    }

    /// Live mic level — drives the recording HUD meter.
    public nonisolated func recordingEngine(_ engine: RecordingEngine, didUpdateLevel level: Float) {
        Task { @MainActor in
            self.recordingHUD.update(level: level)
            self.evaluateInputLevel(level)
        }
    }

    /// Watch the live mic level and warn on the HUD if it stays too low to transcribe well.
    /// Uses the running peak so brief pauses don't trip it; gives ~2s before judging; clears
    /// (and stays cleared) as soon as the user is loud enough once.
    private func evaluateInputLevel(_ level: Float) {
        guard dictationState == .recording, let start = recordingStartDate else { return }
        recordingPeakLevel = max(recordingPeakLevel, level)
        guard Date().timeIntervalSince(start) > 2 else { return }
        recordingHUD.setLowInput(recordingPeakLevel < Self.lowInputPeakThreshold)
    }
}

// MARK: - HotkeyManagerDelegate

extension AppState: HotkeyManagerDelegate {
    func hotkeyDidPress() {
        switch hotkeyMode {
        case .hold:   startRecording()
        case .toggle: toggleRecording()
        }
    }

    func hotkeyDidRelease() {
        if hotkeyMode == .hold { stopRecordingAndTranscribe() }
    }

    /// The configured key reached us — used by the onboarding test step.
    func hotkeyDidReceiveConfiguredKey() {
        hotkeyTestPassed = true
    }
}
