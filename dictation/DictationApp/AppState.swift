import AppKit
import AVFoundation
import SwiftUI
import DictationCore

// MARK: - DictationState

public enum DictationState {
    case idle, recording, transcribing
}

// MARK: - AppState

@MainActor
public final class AppState: NSObject, ObservableObject {

    // MARK: - Published

    @Published public var dictationState: DictationState = .idle
    @Published public var recentTranscripts: [TranscriptRecord] = []
    @Published public var weeklyStats: WeeklyStats = .empty
    @Published public var engineLoaded: Bool = false
    @Published public var statusMessage: String = "Ready"
    @Published public var autoPaste: Bool = true
    @Published public private(set) var sttConfig: STTConfig = .default
    @Published public private(set) var cleanupConfig: CleanupConfig = .default

    // MARK: - Engines

    private let recordingEngine = RecordingEngine()
    private var transcriber: any SpeechTranscriber
    private var cleanup: any TextCleanup
    private let cleanupPack: CleanupPack
    private let clipboardPaster = ClipboardPaster()
    private var telemetryStore: TelemetryStore?
    private var hotkeyManager: HotkeyManager?

    // Audio buffer accumulation
    private var audioBuffers: [AVAudioPCMBuffer] = []
    private var recordingStartDate: Date?

    // Correction window: after a transcript lands, ⌘⇧Z marks it corrected for 5s
    private var correctionWindowTask: Task<Void, Never>?

    // MARK: - Init

    public override init() {
        let defaults = UserDefaults.standard
        let provider = STTProvider(rawValue: defaults.string(forKey: "sttProvider") ?? "") ?? .whisperKit
        let model = defaults.string(forKey: "sttModel")
            ?? defaults.string(forKey: "modelTier")          // legacy key from PRD 0003
            ?? ModelTier.largeV3Turbo.rawValue
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
        super.init()
        recordingEngine.delegate = self

        Task { await setup() }
    }

    // MARK: - Setup

    private func setup() async {
        // 1. Request mic permission
        do {
            try await recordingEngine.requestPermission()
        } catch {
            statusMessage = "Mic permission denied — check System Settings"
            return
        }

        // 2. Load the speech model (downloads on first run — show progress)
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
            statusMessage = "Ready — hold Fn to dictate"
        } catch {
            statusMessage = "Model load failed: \(error.localizedDescription)"
            return
        }

        // 3. Open telemetry store (non-fatal)
        do {
            let url = try TelemetryStore.macOSDatabaseURL()
            telemetryStore = try TelemetryStore(databaseURL: url)
            await refreshTranscripts()
        } catch {
            // Non-fatal — app still works without telemetry
        }

        // 4. Start hotkey listener
        hotkeyManager = HotkeyManager(delegate: self)
        hotkeyManager?.start()
    }

    // MARK: - Recording control (called by HotkeyManager)

    func startRecording() {
        guard dictationState == .idle, engineLoaded else { return }
        audioBuffers = []
        recordingStartDate = Date()
        do {
            try recordingEngine.start()
            dictationState = .recording
            statusMessage = "Recording…"
        } catch {
            statusMessage = "Failed to start: \(error.localizedDescription)"
        }
    }

    func stopRecordingAndTranscribe() {
        guard dictationState == .recording else { return }
        recordingEngine.stop()
        dictationState = .transcribing
        statusMessage = "Transcribing…"
        let buffers = audioBuffers
        let startDate = recordingStartDate ?? Date()
        Task { await performTranscription(buffers: buffers, audioStartDate: startDate) }
    }

    // MARK: - Transcription

    private func performTranscription(buffers: [AVAudioPCMBuffer], audioStartDate: Date) async {
        defer {
            dictationState = .idle
            statusMessage = "Ready — hold Fn to dictate"
        }

        do {
            let result = try await transcriber.transcribe(buffers: buffers, audioStartDate: audioStartDate)

            let rawText = result.text
            guard !rawText.isEmpty else { return }

            // Cleanup pass (the Wispr brain). Skipped when level is off or the clip is
            // too short to benefit. Never throws — falls back internally.
            let wordCount = rawText.split(whereSeparator: { $0.isWhitespace }).count
            let shouldClean = cleanupConfig.level != .off && wordCount >= cleanupPack.minWordsForCleanup
            let cleanupResult: CleanupResult?
            if shouldClean {
                statusMessage = "Cleaning…"
                cleanupResult = await cleanup.clean(
                    CleanupRequest(
                        rawText: rawText,
                        level: cleanupConfig.level,
                        commandGrammar: cleanupPack.commandGrammar,
                        profile: cleanupPack.profile
                    )
                )
            } else {
                cleanupResult = nil
            }
            let finalText = cleanupResult?.cleanedText ?? rawText
            guard !finalText.isEmpty else { return }

            // Write to clipboard and optionally paste
            let ap = autoPaste
            clipboardPaster.writeAndPaste(text: finalText, autoPaste: ap)

            // Build record — transcriptText is what was pasted; rawText keeps the
            // pre-cleanup STT output for the cross-platform learnings dataset.
            let frontmostApp = NSWorkspace.shared.frontmostApplication?.bundleIdentifier
            let record = TranscriptRecord(
                platform: "mac",
                audioDurationMs: result.audioDurationMs,
                transcriptText: finalText,
                whisperkitConfidence: result.confidence,
                latencyMs: result.latencyMs,
                modelTier: "\(result.provider.rawValue)/\(result.model)",
                frontmostApp: frontmostApp,
                rawText: rawText,
                cleanupLevel: shouldClean ? cleanupConfig.level.rawValue : CleanupLevel.off.rawValue,
                cleanupProvider: cleanupResult.map { $0.usedFallback ? "\($0.provider.rawValue)+fallback" : $0.provider.rawValue }
            )

            // Save telemetry (actor method — synchronous throw, async via actor isolation)
            try? await telemetryStore?.save(record)

            // Update UI
            recentTranscripts.insert(record, at: 0)
            if recentTranscripts.count > 5 { recentTranscripts.removeLast() }
            await refreshStats()

            // Open 5-second correction window
            correctionWindowTask?.cancel()
            correctionWindowTask = Task {
                try? await Task.sleep(for: .seconds(5))
            }

        } catch TranscriptionError.emptyResult {
            // Silent — nothing was said
        } catch TranscriptionError.noAudioData {
            // Silent — empty recording
        } catch {
            statusMessage = "Error: \(error.localizedDescription)"
            try? await Task.sleep(for: .seconds(2))
        }
    }

    // MARK: - Correction

    func markLastTranscriptCorrected() {
        // Only honour within the 5-second correction window
        guard let task = correctionWindowTask, !task.isCancelled else { return }
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
        let modelName = config.modelDisplayName
        statusMessage = "Preparing \(modelName)…"
        Task {
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
                statusMessage = engineLoaded
                    ? "Ready — hold Fn to dictate"
                    : "\(config.provider.displayName) unavailable"
            } catch {
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

    // MARK: - Refresh helpers

    func refreshTranscripts() async {
        recentTranscripts = (try? await telemetryStore?.fetchRecent(limit: 5)) ?? []
    }

    func refreshStats() async {
        weeklyStats = (try? await telemetryStore?.fetchWeeklyStats()) ?? .empty
    }
}

// MARK: - RecordingEngineDelegate

extension AppState: RecordingEngineDelegate {
    /// Called on an arbitrary audio queue — bridge back to MainActor.
    public nonisolated func recordingEngine(_ engine: RecordingEngine, didReceiveBuffer buffer: AVAudioPCMBuffer) {
        Task { @MainActor in
            self.audioBuffers.append(buffer)
        }
    }

    /// Called when VAD detects silence — bridge back to MainActor.
    public nonisolated func recordingEngineDidDetectSilence(_ engine: RecordingEngine) {
        Task { @MainActor in
            self.stopRecordingAndTranscribe()
        }
    }
}

// MARK: - HotkeyManagerDelegate

extension AppState: HotkeyManagerDelegate {
    func hotkeyDidPress() { startRecording() }
    func hotkeyDidRelease() { stopRecordingAndTranscribe() }
}
