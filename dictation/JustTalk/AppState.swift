import AppKit
import AVFoundation
import SwiftUI
import DictationCore
import os

/// Subsystem logger — surfaces history/telemetry failures that were previously swallowed by
/// `try?`, so "my dictation never reached History" is diagnosable from Console.app instead of
/// invisible. Filter Console with subsystem `com.trika.dictation`.
private let log = Logger(subsystem: "com.trika.dictation", category: "AppState")

// MARK: - DictationState

public enum DictationState {
    case idle, recording, transcribing
}

public enum HotkeyMode: String, CaseIterable {
    case hold, toggle, holdLatch
    public var displayName: String {
        switch self {
        case .hold:      return "Hold to talk"
        case .toggle:    return "Sticky — tap to start, tap to stop"
        case .holdLatch: return "Hold to talk — double-tap to lock, tap to stop"
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

// MARK: - Captured audio

// Captured audio is held in `CapturedAudioStream` (DictationCore) — an os_unfair_lock-backed,
// order-preserving, audio-thread-friendly buffer. See that type for the threading rationale.

// MARK: - AppState

@MainActor
public final class AppState: NSObject, ObservableObject {

    /// Single app-wide instance. The menu-bar status item (AppKit, see AppDelegate) and the
    /// SwiftUI Settings scene both need the SAME AppState; a singleton is the one source of truth.
    public static let shared = AppState()

    // MARK: - Published

    @Published public var dictationState: DictationState = .idle
    @Published public var recentTranscripts: [TranscriptRecord] = []
    @Published public var weeklyStats: WeeklyStats = .empty
    @Published public var usageTotals: UsageTotals = .empty
    @Published public var engineLoaded: Bool = false
    @Published public var statusMessage: String = "Ready"
    /// Non-nil when the activation key is likely to misbehave — shown persistently in the menu
    /// (not just onboarding) so the user isn't left guessing why Fn opens the emoji picker.
    @Published public private(set) var hotkeyWarning: String?
    @Published public var autoPaste: Bool = true
    @Published public private(set) var sttConfig: STTConfig = .default
    @Published public private(set) var cleanupConfig: CleanupConfig = .default
    @Published public private(set) var vocabulary: [String] = []
    /// Durable, rename-proof store for the vocabulary (NOT UserDefaults — a bundle-id rename once
    /// orphaned the word list stored in UserDefaults; this lives in the stable App-Support dir).
    private let vocabularyStore = (try? VocabularyStore.macOSURL()).map { VocabularyStore(url: $0) }
    @Published public private(set) var hotkeyMode: HotkeyMode = .holdLatch
    @Published public var soundEnabled: Bool = false
    /// Per-word roll-up live pill via LocalAgreement streaming STT (PRD 0008). When on, the pill
    /// grows word-by-word from a streaming session; when off (or the provider can't stream), it
    /// falls back to the per-segment preview. The PASTED text is the batch pass either way.
    @Published public var streamingPillEnabled: Bool = true
    @Published public private(set) var launchAtLogin: Bool = false
    @Published public private(set) var appProfiles: [AppCleanupProfile] = []

    // Onboarding / permissions (drives the setup wizard's live ticks)
    @Published private(set) var hotkeyConfig: HotkeyConfig = .fn
    @Published public var micGranted: Bool = false
    @Published public var accessibilityGranted: Bool = false
    /// Set true the moment the configured key is received during the wizard "test" step.
    @Published public var hotkeyTestPassed: Bool = false

    // MARK: - Onboarding wizard test hooks
    /// Live mic RMS (0…1) while the wizard mic test is running — drives the "we hear you" meter.
    @Published public var micInputLevel: Float = 0
    /// True while the wizard is actively metering the mic.
    @Published public var micTestActive: Bool = false
    /// Flips true once the user's voice crosses the audible threshold during the mic test.
    @Published public var micTestPassed: Bool = false
    /// The transcript from the wizard's final "say this sentence" dry-run, shown back to the user.
    @Published public var onboardingTranscript: String = ""
    /// While true, a completed dictation is routed to `onboardingTranscript` instead of being pasted.
    var onboardingCaptureActive: Bool = false
    private var micTestPeak: Float = 0

    // MARK: - Install contact (wizard "stay in touch")
    @Published public var contactName: String = ""
    @Published public var contactEmail: String = ""
    @Published public var contactPhone: String = ""
    @Published public var contactSubmitted: Bool = false

    // MARK: - Semantic state (derived — the `onState` rename)

    /// The current dictation's lifecycle, derived from `dictationState`. The menu bar reads this
    /// (not `dictationState`) for its status glyph; `dictationState` remains the underlying source
    /// until the `DictationEngine` facade emits `DictationPhase` directly (incl. `.inserted`/`.failed`).
    public var phase: DictationPhase {
        switch dictationState {
        case .idle:         return .idle
        case .recording:    return .capturing
        case .transcribing: return .finishing
        }
    }

    /// Whether the system can dictate at all right now — composed from the two things that gate it:
    /// permissions (a capture fact) and model load (an engine fact). This is what the UI reads
    /// instead of poking `engineLoaded` directly (see `EngineAvailability`).
    public var availability: EngineAvailability {
        if !micGranted { return .blocked(.microphoneDenied) }
        if !accessibilityGranted { return .blocked(.accessibilityDenied) }
        if !sttConfig.provider.isAvailable { return .blocked(.modelUnavailable) }
        return engineLoaded ? .ready : .warmingUp
    }

    // MARK: - Permissions / onboarding

    let permissions = PermissionsService()
    /// Result of the launch pre-flight (Apple-Silicon / OS / disk + recommended provider). Computed
    /// once in init; `setup()` blocks with a requirements screen if `!canRun`.
    public let systemCapabilities: SystemCapabilities
    private let onboardingWindow = OnboardingWindow()
    private let requirementsWindow = RequirementsWindow()
    // History is an AppKit-managed window (like onboarding) rather than a SwiftUI scene, so the
    // menu-bar popover — which is hosted outside the SwiftUI scene graph via NSStatusItem — can
    // open it directly. `openWindow(id:)` does not reach an NSPopover's hosting controller.
    private let historyWindow = HistoryWindow()
    // Settings is likewise an AppKit-managed window, not the SwiftUI `Settings` scene — the
    // scene + `showSettingsWindow:` selector don't work reliably from the status-item popover.
    private let settingsWindow = SettingsWindow()
    private var permissionTimer: Timer?

    /// The permissions Just Talk genuinely needs to function: mic to hear you, plus
    /// Accessibility (to paste) and Input Monitoring (to read the activation key).
    var requiredPermissionsGranted: Bool { micGranted && accessibilityGranted }

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
    /// Whether a real CGEventTap installed on the last start(). For a suppressing key (Fn /
    /// function keys) this must be true, or the key leaks to the OS (Fn → emoji picker) because
    /// the app is on the observe-only NSEvent fallback. False until proven otherwise.
    private var suppressingTapActive = false

    // Audio buffer accumulation — order-preserving + thread-safe (see CapturedAudioStream).
    // nonisolated so the audio-thread delegate can append without an actor hop.
    private nonisolated let audio = CapturedAudioStream()
    // Streaming dictation state (PRD 0007). `streamSession` is live only while a streaming
    // recording is in flight; `streamFlushedCount` marks how many accumulated buffers have already
    // been handed to it, so each VAD flush ingests only the new tail. `streamPump` chains ingests
    // so overlapping silence events can't run the (non-reentrant) session concurrently.
    private var streamSession: StreamingDictationSession?
    private var streamFlushedCount = 0
    private var streamPump: Task<Void, Never>?
    // Per-word roll-up pill (PRD 0008). `streamingPill` is a LocalAgreement streaming session vended
    // by the loaded transcriber (reuses its model); `streamTickTask` re-transcribes the growing
    // buffer on a throttle and drives the confirmed/hypothesis pill. Non-nil only while a streaming
    // recording is live. When nil, the pill falls back to the per-segment `streamSession` preview.
    private var streamingPill: (any StreamingTranscriber)?
    private var streamTickTask: Task<Void, Never>?
    /// How often we feed audio to the streaming session + refresh the pill. Fast (100ms) so Apple
    /// gets audio near-continuously and its live text appears with minimal lag. WhisperKit throttles
    /// its own expensive re-decode INTERNALLY, so a fast tick doesn't make it heavier (PRD 0008).
    private static let streamTickInterval: Duration = .milliseconds(100)
    private var recordingStartDate: Date?
    // Loudest mic level seen during the current recording — drives the live "too quiet" HUD
    // warning. If even the peak stays below this after a couple seconds, the mic is too low.
    private var recordingPeakLevel: Float = 0
    // Set once the transcriber produces any live text this recording. If we're getting words we're
    // clearly hearing the user, so the raw-RMS "too quiet" warning is a false alarm — suppress it.
    // (RMS of normal speech can sit under `audibleThreshold` on quiet mics while STT works fine.)
    private var heardTranscript: Bool = false
    /// Transcript text retention window (privacy) — records older than this are purged on launch.
    private static let transcriptRetentionDays = 30
    /// Hard safety stop: a missed hotkey release / long toggle session can't grow the in-memory
    /// audio unbounded — recording auto-stops at this length.
    private static let maxRecordingSeconds: Double = 600
    // `.holdLatch` gesture tuning. A press shorter than `tapThreshold` counts as a "quick tap";
    // two quick taps whose DOWN edges fall within `doubleTapWindow` latch recording hands-free.
    // A longer press is an ordinary hold (push-to-talk).
    private static let tapThreshold: TimeInterval = 0.25
    private static let doubleTapWindow: TimeInterval = 0.40
    // `.holdLatch` gesture state (main-actor only). `latchDownTime` = when the current press went
    // down; `lastQuickTapTime` = DOWN time of the last quick tap awaiting a possible second tap;
    // `latched` = recording is held on hands-free; `swallowNextRelease` eats the key-up that
    // follows a latch/stop press so it isn't mistaken for a hold-release.
    private var latchDownTime: TimeInterval = 0
    private var lastQuickTapTime: TimeInterval = 0
    private var latched = false
    private var swallowNextRelease = false
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

    // Correction window: after a transcript lands, ⌘⇧Z marks it corrected for 5s
    private var correctionWindowTask: Task<Void, Never>?
    private var correctionWindowOpen = false
    // Monotonic token so a superseded STT load can't apply state for an old switch.
    private var sttLoadGeneration = 0

    // MARK: - Init

    public override init() {
        let defaults = UserDefaults.standard
        // Pre-flight: pick the best provider this machine supports (Apple SpeechAnalyzer on a capable
        // Mac — Apple Silicon + macOS 26 — else WhisperKit Large Turbo, multilingual) and capture any
        // hard blockers (Intel, OS too old, no disk) so setup() can show a clear "requirements" screen
        // instead of a broken onboarding. The user can still switch provider (WhisperKit does the
        // Hinglish/Gujarati Apple can't). No model-tier picker — the tier is fixed per provider.
        let caps = SystemPreflight.check()
        self.systemCapabilities = caps
        let provider: STTProvider
        let model: String
        if let saved = defaults.string(forKey: "sttProvider"), let p = STTProvider(rawValue: saved) {
            provider = p
            model = defaults.string(forKey: "sttModel")
                ?? (p == .appleSpeech ? "en-US" : ModelTier.defaultWhisper.rawValue)
        } else {                                          // first launch — auto by capability
            provider = caps.recommendedProvider
            model = provider == .appleSpeech ? "en-US" : ModelTier.defaultWhisper.rawValue
        }
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

        // Auto-paste, start/stop sounds, and the word-by-word live pill are always on now (no
        // toggles) — opinionated defaults.
        self.autoPaste = true
        // Vocabulary now lives in a durable, rename-proof file (see `vocabularyStore`). Migrate any
        // legacy UserDefaults value the first time, so an existing list is rescued rather than dropped.
        let storedVocabulary = vocabularyStore?.load() ?? []
        let legacyVocabulary = defaults.stringArray(forKey: "vocabulary") ?? []
        self.vocabulary = storedVocabulary.isEmpty ? legacyVocabulary : storedVocabulary
        if storedVocabulary.isEmpty, !legacyVocabulary.isEmpty { vocabularyStore?.save(legacyVocabulary) }
        self.hotkeyMode = HotkeyMode(rawValue: defaults.string(forKey: "hotkeyMode") ?? "") ?? .holdLatch
        self.hotkeyConfig = HotkeyConfig.load(from: defaults)
        self.soundEnabled = true
        self.streamingPillEnabled = true
        self.launchAtLogin = LoginItem.isEnabled
        if let data = defaults.data(forKey: "appProfiles"),
           let profiles = try? JSONDecoder().decode([AppCleanupProfile].self, from: data) {
            self.appProfiles = profiles
        }
        super.init()
        recordingEngine.delegate = self
        transcriber.setVocabularyBias(biasTerms)

        // HUD open/close audio cues (Wispr Flow-style): a soft chime when the pill appears and
        // another when it disappears. Centralised on real visibility transitions, so phase changes
        // (recording→processing→done) while the pill stays up don't re-fire. Gated by soundEnabled.
        recordingHUD.onAppear = { [weak self] in self?.playSound("Tink") }
        recordingHUD.onDisappear = { [weak self] in self?.playSound("Pop") }

        Task { await setup() }
    }

    // MARK: - Setup

    private func setup() async {
        // 0. Hard requirements gate. If this Mac can't run the app (Intel, macOS too old, no disk),
        //    show a clear requirements screen and stop — never let a user hit a broken onboarding or
        //    a stuck model download.
        guard systemCapabilities.canRun else {
            statusMessage = "This Mac doesn't meet Just Talk's requirements"
            requirementsWindow.show(capabilities: systemCapabilities)
            return
        }

        // 1. Read permission status WITHOUT prompting (the fix for "Settings opens out of
        //    the blue"). Prompts now happen only from explicit onboarding buttons.
        refreshPermissions()

        // 2. Build the hotkey listener; only install the event tap once Accessibility is
        //    granted (refreshPermissions starts it on the grant transition).
        hotkeyManager = HotkeyManager(delegate: self, config: hotkeyConfig)
        if accessibilityGranted { startHotkeyListener() }

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
            // Apple's SpeechAnalyzer needs Speech Recognition permission; if the user denies it (or
            // the model is otherwise unavailable), don't dead-end onboarding — fall back to WhisperKit,
            // which needs no Speech Recognition. setSTTConfig rebuilds + loads it and persists the
            // choice, so the Speech Recognition prompt won't return next launch.
            if sttConfig.provider == .appleSpeech {
                log.notice("Apple Speech unavailable (\(error.localizedDescription, privacy: .public)) — falling back to WhisperKit")
                statusMessage = "Setting up the multilingual model…"
                // Fall back for THIS session only — do NOT persist. A transient failure (network
                // hiccup during the language-asset download, a momentary SpeechAnalyzer error) must
                // not permanently downgrade the user to the slower path forever. Next launch retries
                // Apple; if the cause was a genuine Speech-Recognition denial it fails fast (already
                // denied → no prompt) and falls back again — cheap and self-healing. The user can
                // still pick a provider explicitly in Settings, which DOES persist.
                setSTTConfig(STTConfig(provider: .whisperKit, model: ModelTier.defaultWhisper.rawValue), persist: false)
            } else {
                statusMessage = "Model load failed: \(error.localizedDescription)"
            }
            return
        }

        // 6. Open telemetry store. App still dictates+pastes without it, but History is fully
        //    DB-backed: if this throws, NOTHING is ever persisted. Previously swallowed silently —
        //    the root of "I could see it transcribe but it never reached History". Now logged and
        //    surfaced so the failure is diagnosable instead of invisible.
        do {
            let url = try TelemetryStore.macOSDatabaseURL()
            telemetryStore = try TelemetryStore(databaseURL: url)
            // Privacy retention: drop transcripts older than 30 days on launch (audio is bounded
            // to the last 5 by RecordingStore). Dictated text isn't hoarded indefinitely.
            try? await telemetryStore?.purge(olderThanDays: Self.transcriptRetentionDays)
            await refreshTranscripts()
        } catch {
            telemetryStore = nil
            log.error("Telemetry store failed to open — History will stay empty: \(error.localizedDescription, privacy: .public)")
        }

        // 7. Warm the cold paths so the FIRST dictation is instant, not a ~3s stall (the pill appeared
        //    late + didn't record until then). macOS cold-inits the audio HAL on the first
        //    AVAudioEngine.start(); the LLM cleanup warms lazily too. Pay both now, at launch, in the
        //    background — the cost moves off the user's first key-press.
        await warmUpForFirstDictation()
    }

    /// Pre-pay the first-dictation cold costs at launch. Safe/no-op if the mic isn't granted yet (the
    /// permission-polling loop re-runs it once granted).
    private func warmUpForFirstDictation() async {
        guard permissions.micStatus == .granted, engineLoaded, dictationState == .idle, !micTestActive else { return }
        cleanup.prewarm()
        do {
            // The audio HAL cold-start cost is paid inside this first start(); stop immediately — the
            // mic is live only momentarily. Subsequent (real) starts are then instant.
            try recordingEngine.start()
            recordingEngine.stop()
            audio.reset()   // discard the microscopic warm-up capture
            log.notice("first-dictation warm-up complete")
        } catch {
            log.notice("engine warm-up skipped: \(error.localizedDescription, privacy: .public)")
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
        let couldInstall = accessibilityGranted
        accessibilityGranted = permissions.accessibilityGranted
        // The active tap needs only Accessibility — install it on the grant transition.
        if accessibilityGranted && !couldInstall {
            startHotkeyListener()
        }
    }

    @objc private func appDidBecomeActive() {
        refreshPermissions()
        // Re-check the Globe setting / competitors so the warning clears once the user fixes it.
        recomputeHotkeyWarning()
    }

    /// Start the hotkey listener and record whether the suppressing tap actually installed, then
    /// recompute the activation-key warning. Centralized so every start path (setup, permission
    /// grant, test) tracks the same state instead of firing start() and discarding the result.
    private func startHotkeyListener() {
        suppressingTapActive = hotkeyManager?.start() ?? false
        recomputeHotkeyWarning()
    }

    /// Diagnose the activation key. We warn ONLY when the key is genuinely leaking — i.e. the
    /// suppressing CGEventTap failed to install and we're on the observe-only fallback, so Fn
    /// reaches macOS and opens the emoji picker. When the tap IS active it swallows Fn before the
    /// OS sees it, so the Globe/"Show Emoji" setting is irrelevant and no advisory is shown (that
    /// belt-and-suspenders nag was pure noise in the common, working case).
    func recomputeHotkeyWarning() {
        guard hotkeyConfig.suppresses, !suppressingTapActive else { hotkeyWarning = nil; return }
        // On the observe-only fallback: the key fires recording but is NOT swallowed, so Fn
        // reaches macOS and opens the emoji picker; a later paste can land in that panel.
        let others = HotkeyConflict.runningCompetitors().compactMap { $0.localizedName }
        var msg = "\(hotkeyConfig.shortName) is leaking to macOS (opens the emoji picker). "
            + "Fix: grant Just Talk Accessibility so it can swallow the key — or set "
            + "System Settings ▸ Keyboard ▸ “Press 🌐 key to” ▸ Do Nothing."
        if !others.isEmpty {
            msg += " Also quit other Fn dictation apps (\(others.joined(separator: ", ")))."
        }
        hotkeyWarning = msg
    }

    func showOnboardingWindow() {
        refreshPermissions()
        onboardingWindow.show(appState: self)
    }

    /// Open the searchable History window (menu-bar "History" button). AppKit-managed so it works
    /// from the status-item popover.
    func showHistoryWindow() {
        historyWindow.show(appState: self)
    }

    /// Open the Settings window (menu-bar "Settings" button). AppKit-managed for the same reason
    /// as History — the SwiftUI `Settings` scene doesn't open reliably from the popover.
    func showSettingsWindow() {
        settingsWindow.show(appState: self)
    }

    /// Trigger the system mic prompt (only on a wizard button tap). Guarded so rapid taps
    /// can't stack multiple system prompts while one is already pending.
    /// True once the user has explicitly denied the mic (vs not-yet-asked) — the wizard shows a
    /// "required, re-enable in Settings" state and stays blocked. Polled via the wizard ticker.
    var micIsDenied: Bool { permissions.micStatus == .denied }

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

    /// Open Keyboard settings so the user can turn off macOS Dictation's Globe/Fn shortcut — the
    /// "second yellow microphone" that collides with our Fn key.
    func openKeyboardSettings() {
        permissions.openKeyboardSettings()
    }

    /// Manual fallback when the Accessibility prompt didn't appear (already responded once).
    func openAccessibilitySettings() {
        permissions.openAccessibilitySettings()
    }

    /// Begin the wizard "press your key to test" step: route presses to a confirmation
    /// signal only (no recording) so we can prove the key reaches us — the definitive
    /// conflict check, since macOS won't tell us who else holds the key.
    func beginHotkeyTest() {
        hotkeyTestPassed = false
        hotkeyManager?.isTesting = true
        if accessibilityGranted { startHotkeyListener() }
    }

    func endHotkeyTest() {
        hotkeyManager?.isTesting = false
    }

    // MARK: - Wizard mic test

    /// Start a light, transcription-free mic capture so the wizard can show a live level meter the
    /// instant the user grants Microphone — proving "we can hear you." Levels arrive via the
    /// `didUpdateLevel` delegate and are published to `micInputLevel`.
    func beginMicTest() {
        guard permissions.micStatus == .granted, dictationState == .idle, !micTestActive else { return }
        micTestPassed = false
        micInputLevel = 0
        micTestPeak = 0
        audio.reset()
        do {
            try recordingEngine.start()
            micTestActive = true
        } catch {
            statusMessage = "Couldn't start the mic test: \(error.localizedDescription)"
        }
    }

    func endMicTest() {
        guard micTestActive else { return }
        micTestActive = false
        recordingEngine.stop()
        micInputLevel = 0
        audio.reset()
    }

    // MARK: - Wizard "try it" capture

    /// Arm/disarm onboarding capture: while armed, the next completed dictation lands in
    /// `onboardingTranscript` (shown in the wizard) instead of being pasted into another app.
    func armOnboardingCapture(_ on: Bool) {
        onboardingCaptureActive = on
        if on { onboardingTranscript = "" }
    }

    // MARK: - Install contact ping

    /// Fire-and-forget install signal. Voice never leaves the device; this is an explicit,
    /// user-entered contact so we know someone installed and can send updates/support.
    /// Endpoint is set via `JUSTTALK_INSTALL_PING_URL` (Info.plist / env); no-ops if unset.
    func submitContact() {
        contactSubmitted = true
        let name = contactName.trimmingCharacters(in: .whitespacesAndNewlines)
        let email = contactEmail.trimmingCharacters(in: .whitespacesAndNewlines)
        let phone = contactPhone.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !email.isEmpty || !phone.isEmpty else { return }
        guard let urlString = Bundle.main.object(forInfoDictionaryKey: "JustTalkInstallPingURL") as? String,
              !urlString.isEmpty, let url = URL(string: urlString) else {
            log.notice("install ping skipped — no JustTalkInstallPingURL configured")
            return
        }
        let payload: [String: String] = [
            "name": name, "email": email, "phone": phone,
            "version": (Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String) ?? "",
            "platform": "mac",
        ]
        guard let body = try? JSONSerialization.data(withJSONObject: payload) else { return }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = body
        URLSession.shared.dataTask(with: request) { _, _, error in
            if let error { log.error("install ping failed: \(error.localizedDescription, privacy: .public)") }
        }.resume()
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
        guard dictationState == .idle, engineLoaded else {
            log.notice("startRecording ignored: state=\(String(describing: self.dictationState), privacy: .public) engineLoaded=\(self.engineLoaded, privacy: .public) correctionWindow=\(self.correctionWindowOpen, privacy: .public)")
            return
        }
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
        // A wizard mic test may still be metering (its engine running) — stop it so this real
        // recording owns the audio engine and its levels reach the HUD.
        if micTestActive { endMicTest() }
        audio.reset()
        recordingPeakLevel = 0
        heardTranscript = false
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
            recordingHUD.show(phase: .recording, label: "Listening…")  // open cue via HUD.onAppear
            // ONE capture→transcribe path. The live pill (growing HUD text) always comes from the
            // streaming session's own STT — the single `transcriber` model. The old second
            // (tiny preview) model was deleted: running two models starved the Neural Engine and
            // dropped mic-tap buffers ("no live text + slow", "middle got dropped").
            startStreamingSession()
        } catch {
            statusMessage = "Failed to start: \(error.localizedDescription)"
        }
    }

    func stopRecordingAndTranscribe() {
        guard dictationState == .recording else { return }
        // ONE stop path: `stopStreaming` tears down the live-preview session and hands the FULL
        // captured audio to the batch `performTranscription` (which owns the pasted output plus the
        // 3× retry + timeout safety net). The streaming session was only ever the live pill.
        stopStreaming()
    }

    /// Diagnostic for the "middle got skipped" report: compare the WALL-CLOCK recording time
    /// against the amount of audio actually captured. If we captured materially less audio than
    /// the recording lasted, the audio thread dropped buffers (capture-side loss). If they match
    /// but the transcript is still sparse, the loss is in STT, not capture. Appended to a CSV in
    /// Application Support (os_log doesn't surface reliably on this build); paired with the raw
    /// audio the recording store already keeps, so a drop can be diagnosed after the fact.
    private func logCaptureMetric(buffers: [AVAudioPCMBuffer], startDate: Date) {
        let frames = buffers.reduce(0) { $0 + Int($1.frameLength) }
        let capturedSec = Double(frames) / RecordingEngine.targetSampleRate
        let wallSec = Date().timeIntervalSince(startDate)
        let ratio = wallSec > 0 ? capturedSec / wallSec : 1
        let line = String(format: "%@,wall=%.2f,captured=%.2f,ratio=%.3f\n",
                          ISO8601DateFormatter().string(from: startDate), wallSec, capturedSec, ratio)
        guard let base = try? FileManager.default.url(for: .applicationSupportDirectory,
                                                      in: .userDomainMask, appropriateFor: nil, create: true) else { return }
        let url = base.appendingPathComponent("com.trika.dictation/capture-metrics.csv")
        let data = Data(line.utf8)
        if let handle = try? FileHandle(forWritingTo: url) {
            defer { try? handle.close() }
            try? handle.seekToEnd()
            try? handle.write(contentsOf: data)
        } else {
            try? data.write(to: url)
        }
    }

    // MARK: - Streaming dictation (PRD 0007, beta)

    /// Spin up a streaming session for this recording. Transcribes each VAD segment during speech
    /// (growing the live pill) and runs ONE cleanup pass over the whole transcript at stop.
    private func startStreamingSession() {
        let level = effectiveLevel(forBundleId: recordingFrontmostApp)
        let session = StreamingDictationSession(
            transcriber: transcriber,
            cleanup: cleanup,
            level: level,
            vocab: vocabularyMap,
            commandGrammar: cleanupPack.commandGrammar,
            profile: "dictation",
            minWordsForCleanup: cleanupPack.minWordsForCleanup
        )
        session.prewarm()
        streamSession = session
        streamFlushedCount = 0
        streamPump = nil

        // Per-word roll-up pill (PRD 0008): vend a streaming session from the LOADED transcriber
        // (reuses its model). If the provider can't stream (nil), the per-segment `streamSession`
        // preview above remains the pill source — a clean fallback. The pasted text is the batch
        // pass at stop either way, so the streaming pill can never corrupt output.
        streamingPill = nil
        streamTickTask = nil
        if streamingPillEnabled, let pill = transcriber.makeStreamingSession() {
            // Drive the pill from the transcript's `confirmed` field only. For WhisperKit that is
            // strictly append-only LocalAgreement text (never rewrites → no chatter); its volatile
            // re-decode tail was the chatter source and is intentionally not shown. For Apple,
            // `confirmed` carries finalized + its display-grade volatile tail, which can revise a
            // word or two at the end (acceptable — that's what Apple Dictation shows live). Either
            // way the reveal driver smooths growth, and the pasted text is the batch pass.
            streamingPill = pill
            startStreamTick()
        }
    }

    /// Throttled loop that re-transcribes the growing audio and drives the confirmed/hypothesis pill.
    /// Runs OFF the audio thread; non-reentrant by construction (one awaited step per tick). The
    /// buffer append still happens on the audio render thread via `CapturedAudioStream`, so these
    /// passes can't starve capture.
    private func startStreamTick() {
        streamTickTask = Task { @MainActor in
            while dictationState == .recording, let pill = streamingPill {
                try? await Task.sleep(for: Self.streamTickInterval)
                guard dictationState == .recording, streamingPill === pill else { break }
                let samples = AudioSampleBridge.flatten(audio.snapshot())
                guard !samples.isEmpty else { continue }
                let t = await pill.step(samples: samples)
                guard dictationState == .recording, streamingPill === pill else { break }
                if !t.confirmed.isEmpty { heardTranscript = true }   // words flowing → we hear you
                recordingHUD.update(previewText: t.confirmed)   // confirmed only — append-only, no chatter
            }
        }
    }

    /// On each VAD silence, hand the buffers captured since the last flush to the streaming session.
    /// Ingests are chained through `streamPump` so overlapping silences can't run the non-reentrant
    /// session concurrently. Never auto-stops — the activation key still controls stop.
    private func flushStreamingSegment() {
        guard dictationState == .recording, let session = streamSession else { return }
        // When the per-word streaming pill is active it owns the pill and re-transcribes on its own
        // tick — running the per-segment preview too would put two transcribe loops on the one model
        // (the Neural-Engine-starvation failure). So the per-segment path is the fallback only.
        guard streamingPill == nil else { return }
        // `drain(after:)` returns the buffers captured since the last flush plus the new total —
        // one place owns the cursor math (no hand-rolled slicing).
        let (segment, newCount) = audio.drain(after: streamFlushedCount)
        guard !segment.isEmpty else { return }
        streamFlushedCount = newCount
        let startDate = recordingStartDate ?? Date()
        let prev = streamPump
        streamPump = Task { @MainActor in
            await prev?.value
            _ = await session.ingest(segment: segment, audioStartDate: startDate)
            // Drive the growing HUD pill from the session's own raw transcription (no second model).
            guard self.dictationState == .recording else { return }
            let shown = session.confirmedText
            if !shown.isEmpty { self.heardTranscript = true; self.recordingHUD.update(previewText: shown) }
        }
    }

    /// Stop recording. The streaming session was only ever the LIVE PILL PREVIEW — the final pasted
    /// text comes from ONE full-audio batch transcription, which is accurate. Transcribing the whole
    /// clip in one piece avoids the segment-boundary word drops AND the short-segment hallucinations
    /// ("random words") that per-segment streaming STT produces.
    private func stopStreaming() {
        recordingEngine.stop()
        dictationState = .transcribing
        statusMessage = "Transcribing…"
        recordingHUD.setPhase(.processing, label: "Transcribing…")
        let all = audio.snapshot()
        let startDate = recordingStartDate ?? Date()
        logCaptureMetric(buffers: all, startDate: startDate)
        persistRecording(buffers: all, recordedAt: startDate)
        // Tear down BOTH preview paths; the batch path owns the output. The streaming pill must be
        // fully stopped before the batch pass so two transcribes don't hit the one model at once.
        let prev = streamPump
        let tick = streamTickTask
        let tornPill = streamingPill
        streamSession = nil
        streamFlushedCount = 0
        streamPump = nil
        streamTickTask = nil
        streamingPill = nil   // the tick loop sees this and exits; its `pill` ref keeps it alive to finish
        // Release the streaming session (finishes its input + stops the Apple analyzer so its Tasks
        // complete and it can deallocate — otherwise it leaks per dictation). Safe mid-tick: the
        // in-flight step's yield becomes a no-op once the continuation is finished.
        tornPill?.reset()
        Task { @MainActor in
            await prev?.value   // let any in-flight preview flush settle (its result is discarded)
            _ = await tick?.value  // let any in-flight streaming step finish before batch touches the model
            await performTranscription(buffers: all, audioStartDate: startDate)
        }
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

    /// `.holdLatch` gesture: hold to talk (push-to-talk — stops the instant you release, no matter
    /// how briefly you held), double-tap to latch recording on hands-free, then a single press to
    /// stop. A press too short to contain speech (< `tapThreshold`) is discarded, not transcribed,
    /// and only arms double-tap detection. Driven purely by the configured key's DOWN/UP edges, so
    /// it works on whatever activation key the user selected. Runs on the main actor.
    func handleHoldLatch(down: Bool) {
        let now = ProcessInfo.processInfo.systemUptime
        if down {
            if latched {
                // Latched, and a fresh press arrived — this is the "stop" press.
                latched = false
                swallowNextRelease = true
                stopRecordingAndTranscribe()
                return
            }
            // Second quick tap close behind the first → latch on and keep recording hands-free.
            if lastQuickTapTime != 0, now - lastQuickTapTime <= Self.doubleTapWindow {
                latched = true
                lastQuickTapTime = 0
                swallowNextRelease = true
                if dictationState == .idle { startRecording() }  // first tap discarded — restart
                return
            }
            // Ordinary press start (also the first of a potential double-tap).
            latchDownTime = now
            startRecording()
        } else {
            if swallowNextRelease { swallowNextRelease = false; return }
            if latched { return }            // latched: releases do nothing
            if now - latchDownTime >= Self.tapThreshold {
                lastQuickTapTime = 0
                stopRecordingAndTranscribe() // real hold → instant push-to-talk stop
            } else {
                // Too short to be speech: discard (no junk transcript) and arm double-tap detection.
                lastQuickTapTime = latchDownTime
                discardRecording()
            }
        }
    }

    /// Stop the engine and return to idle WITHOUT transcribing — used for a sub-`tapThreshold`
    /// press (the first half of a double-tap, or a stray tap) so it produces no junk transcript.
    private func discardRecording() {
        guard dictationState == .recording else { return }
        recordingEngine.stop()
        streamSession = nil
        streamFlushedCount = 0
        streamPump?.cancel()
        streamPump = nil
        // Tear down the streaming pill too — otherwise a discarded tap (e.g. the first of a
        // double-tap latch) leaks its session/analyzer and leaves a stale tick running.
        streamTickTask?.cancel()
        streamTickTask = nil
        streamingPill?.reset()
        streamingPill = nil
        audio.reset()
        finishIdle()
    }

    private func playSound(_ name: String) {
        guard soundEnabled, let sound = NSSound(named: name) else { return }
        sound.play()
    }

    // MARK: - Transcription

    /// A model call timed out — release the (possibly stuck) STT + cleanup resources so nothing
    /// orphaned lingers, then reload the transcriber fresh for the next dictation.
    private func recoverAfterTimeout() {
        transcriber.reset()
        cleanup.reset()
        engineLoaded = false
        let t = transcriber
        statusMessage = "Reloading the model…"
        Task {
            try? await t.load()
            engineLoaded = t.isLoaded
            if dictationState == .idle, engineLoaded { statusMessage = readyMessage }
        }
    }

    private func performTranscription(buffers: [AVAudioPCMBuffer], audioStartDate: Date) async {
        // Transcribe with automatic retries — STT can fail transiently. The audio is preserved
        // so a failure is never silently lost (Wispr-style: keep audio, retry, then discard).
        let result: TranscriptionResult
        do {
            result = try await transcribeWithRetry(buffers: buffers, audioStartDate: audioStartDate)
        } catch TranscriptionError.emptyResult, TranscriptionError.noAudioData {
            handleEmpty(buffers: buffers, audioStartDate: audioStartDate)
            return
        } catch is TimeoutError {
            // A stuck model call: release everything so orphaned work can't linger, then preserve
            // the audio for retry on a freshly-reloaded engine.
            recoverAfterTimeout()
            failWithRetry(buffers: buffers, audioStartDate: audioStartDate, message: "Timed out — retry")
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

        // TIMING INSTRUMENTATION (temporary — measuring where the end-to-end latency goes).
        // stt = WhisperKit transcribe; cleanup = Apple Foundation Models (or fallback) rewrite.
        // Read with: log show --predicate 'subsystem == "com.trika.dictation"' --info | grep TIMING
        log.notice("TIMING stt=\(result.latencyMs, privacy: .public)ms cleanup=\(cleanupResult?.latencyMs ?? 0, privacy: .public)ms words=\(wordCount, privacy: .public) audioMs=\(result.audioDurationMs, privacy: .public) level=\(level.rawValue, privacy: .public) fallback=\(cleanupResult?.usedFallback ?? false, privacy: .public)")

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
        await finalizeInsertion(finalText: finalText, record: record)
    }

    /// Shared insertion tail for BOTH the batch and streaming paths: paste into the target app,
    /// persist to History (surfacing any save failure), update the recents list, arm the 5-second
    /// correction window, and return to idle. Callers build their own `TranscriptRecord` and hand it
    /// in — keeping paste/History/correction logic in ONE place so the two paths can't drift.
    private func finalizeInsertion(finalText: String, record: TranscriptRecord) async {
        // Wizard "try it" dry-run: show the text back in the onboarding window to prove the whole
        // pipeline works — do NOT paste into another app, and don't touch History.
        if onboardingCaptureActive {
            onboardingCaptureActive = false
            onboardingTranscript = finalText
            dictationState = .idle
            statusMessage = "Ready"
            recordingHUD.hide()
            return
        }
        // Write to clipboard and optionally paste into the app frontmost at record start.
        clipboardPaster.writeAndPaste(text: finalText, autoPaste: autoPaste, targetApp: recordingTargetApp) { [weak self] in
            // Target app wasn't frontmost — we didn't paste into the wrong place; tell the user
            // the text is waiting on the clipboard.
            self?.statusMessage = "Couldn't paste into the target — text is on your clipboard (⌘V)"
        }
        // (Close cue is fired by HUD.onDisappear when the pill goes away — no per-paste sound here.)

        // Persist to History. A nil store and a failed write are both logged + surfaced, so a
        // persistence failure is visible instead of looking like success (was a silent `try?`).
        var historyWarning: String?
        if let store = telemetryStore {
            do {
                try await store.save(record)
            } catch {
                log.error("Failed to save transcript to History: \(error.localizedDescription, privacy: .public)")
                historyWarning = "Saved to clipboard, but couldn't write to History"
            }
        } else {
            log.error("Transcript pasted but History store is unavailable — not saved")
            historyWarning = "Pasted — History unavailable (text not saved)"
        }

        recentTranscripts.insert(record, at: 0)
        if recentTranscripts.count > 5 { recentTranscripts.removeLast() }
        await refreshStats()

        // Open the 5-second correction window (recentTranscripts.first is this record).
        correctionWindowTask?.cancel()
        correctionWindowOpen = true
        correctionWindowTask = Task { [weak self] in
            do { try await Task.sleep(for: .seconds(5)) } catch { return }
            self?.correctionWindowOpen = false
            if self?.dictationState == .idle { self?.recordingHUD.hide() }
        }

        // Success — drop preserved audio, go idle, show the post-insert correction prompt.
        pendingAudio = nil
        dictationState = .idle
        statusMessage = historyWarning ?? readyMessage
        recordingHUD.showCorrectionPrompt { [weak self] in self?.markLastTranscriptCorrected() }
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
        // Wizard "try it": nothing recognized — nudge the user to try again, stay armed.
        if onboardingCaptureActive {
            onboardingTranscript = ""
            dictationState = .idle
            statusMessage = "Didn't catch that — press your key and try again"
            recordingHUD.hide()
            return
        }
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
        let id = record.id   // capture the ID — index 0 may differ by the time the write returns
        Task {
            do {
                try await telemetryStore?.markCorrected(id: id, note: nil)
            } catch {
                statusMessage = "Couldn't save correction"
            }
            // Update the matching record by ID, not a stale index.
            if let idx = recentTranscripts.firstIndex(where: { $0.id == id }) {
                recentTranscripts[idx].wasCorrected = true
            }
        }
        // Dismiss the correction prompt now that the user has acted (if still showing).
        if dictationState == .idle { recordingHUD.hide() }
    }

    // MARK: - Settings

    /// Switch STT provider and/or model. Rebuilds the transcriber via the factory and reloads with
    /// progress. `persist` controls whether the choice is written to UserDefaults: user-driven
    /// switches persist (default); an automatic runtime fallback passes `persist: false` so a
    /// transient failure can't permanently trap the user off the recommended provider.
    func setSTTConfig(_ config: STTConfig, persist: Bool = true) {
        guard config != sttConfig else { return }
        // Never switch to a provider that isn't implemented yet — its transcriber's load()
        // throws, engineLoaded stays false, and dictation is blocked until the user switches
        // back (a self-brick from Settings). The picker binding snaps back to the current value.
        guard config.provider.isAvailable else {
            statusMessage = "\(config.provider.displayName) isn't available yet"
            return
        }
        sttConfig = config
        if persist {
            let defaults = UserDefaults.standard
            defaults.set(config.provider.rawValue, forKey: "sttProvider")
            defaults.set(config.model, forKey: "sttModel")
        }

        engineLoaded = false
        // Capture THIS switch's transcriber instance locally — the Task below must load/read
        // exactly this object. Using the mutable `self.transcriber` let a stale task from an
        // earlier switch call load() on (and cancel the load of) whatever the current
        // transcriber happened to be. The generation token still guards UI state writes.
        let newTranscriber = SpeechTranscriberFactory.make(config)
        newTranscriber.setVocabularyBias(biasTerms)
        transcriber = newTranscriber
        sttLoadGeneration += 1
        let token = sttLoadGeneration
        let modelName = config.modelDisplayName
        statusMessage = "Preparing \(modelName)…"
        Task {
            do {
                try await newTranscriber.load { [weak self] fraction in
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
                engineLoaded = newTranscriber.isLoaded
                statusMessage = engineLoaded
                    ? readyMessage
                    : "\(config.provider.displayName) unavailable"
            } catch {
                guard sttLoadGeneration == token else { return }
                statusMessage = "Load failed: \(error.localizedDescription)"
            }
        }
    }

    /// Switch cleanup provider and/or level. Persists and rebuilds the cleanup engine.
    func setCleanupConfig(_ config: CleanupConfig) {
        guard config != cleanupConfig else { return }
        // Mirror the STT guard: don't switch to a cleanup provider this build/OS can't run.
        // (Runtime fallback — e.g. Apple Intelligence off — is still possible and surfaced via
        // the result's usedFallback flag; this just blocks selecting an outright-unavailable one.)
        guard config.provider.isAvailable else {
            statusMessage = "\(config.provider.displayName) isn't available on this Mac"
            return
        }
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
        vocabularyStore?.save(cleaned)   // durable, rename-proof (NOT UserDefaults — that lost the list once)
        transcriber.setVocabularyBias(biasTerms)
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
        suppressingTapActive = hotkeyManager?.setConfig(config) ?? false
        recomputeHotkeyWarning()
        hotkeyTestPassed = false
        if dictationState == .idle, engineLoaded { statusMessage = readyMessage }
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
    /// Always-on brand terms so the app spells its own name (and the company) correctly, even before
    /// the user has added any custom vocabulary — "just talk" → "Just Talk". Merged UNDER the user's
    /// terms, so a user override always wins on collision.
    static let brandVocabulary = ["Just Talk", "Trika"]

    /// STT recognition bias = built-in brand terms + the user's custom vocabulary.
    private var biasTerms: [String] { Self.brandVocabulary + vocabulary }

    private var vocabularyMap: [String: String] {
        var map = Dictionary(Self.brandVocabulary.map { ($0.lowercased(), $0) },
                             uniquingKeysWith: { _, b in b })
        for term in vocabulary { map[term.lowercased()] = term }   // user terms win on collision
        return map
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
        // A pause is NOT an auto-stop (that chopped sentences mid-thought) — it's a natural segment
        // boundary: flush the audio since the last flush for incremental STT (the live pill). Cleanup
        // still runs once at stop, not here. The activation key controls stop.
        Task { @MainActor in self.flushStreamingSegment() }
    }

    /// Live mic level — drives the recording HUD meter.
    public nonisolated func recordingEngine(_ engine: RecordingEngine, didUpdateLevel level: Float) {
        Task { @MainActor in
            // Route to the wizard meter ONLY when metering AND not in a real dictation — otherwise a
            // leaked micTestActive flag would starve the HUD of levels ("can't hear you" during a
            // normal recording).
            if self.micTestActive, self.dictationState != .recording {
                self.micInputLevel = level
                self.micTestPeak = max(self.micTestPeak, level)
                if self.micTestPeak >= AudioLevels.audibleThreshold { self.micTestPassed = true }
                return
            }
            self.recordingHUD.update(level: level)
            self.evaluateInputLevel(level)
        }
    }

    /// Watch the live mic level and warn on the HUD if it stays too low to transcribe well.
    /// Uses the running peak so brief pauses don't trip it; gives ~2s before judging; clears
    /// (and stays cleared) as soon as the user is loud enough once.
    private func evaluateInputLevel(_ level: Float) {
        guard dictationState == .recording, let start = recordingStartDate else { return }
        // Hard safety stop so a stuck/forgotten recording can't balloon RAM/CPU.
        if Date().timeIntervalSince(start) > Self.maxRecordingSeconds {
            statusMessage = "Reached the \(Int(Self.maxRecordingSeconds / 60))-minute limit — stopping"
            stopRecordingAndTranscribe()
            return
        }
        recordingPeakLevel = max(recordingPeakLevel, level)
        guard Date().timeIntervalSince(start) > 2 else { return }
        // If the transcriber has produced any text, we're plainly hearing the user — the raw-RMS
        // peak can still read "quiet" on a low-gain mic, so trust the transcript over the meter.
        if heardTranscript { recordingHUD.setLowInput(false); return }
        recordingHUD.setLowInput(recordingPeakLevel < AudioLevels.audibleThreshold)
    }
}

// MARK: - HotkeyManagerDelegate

extension AppState: HotkeyManagerDelegate {
    func hotkeyDidPress() {
        switch hotkeyMode {
        case .hold:      startRecording()
        case .toggle:    toggleRecording()
        case .holdLatch: handleHoldLatch(down: true)
        }
    }

    func hotkeyDidRelease() {
        switch hotkeyMode {
        case .hold:      stopRecordingAndTranscribe()
        case .toggle:    break
        case .holdLatch: handleHoldLatch(down: false)
        }
    }

    /// The configured key reached us — used by the onboarding test step.
    func hotkeyDidReceiveConfiguredKey() {
        hotkeyTestPassed = true
    }
}
