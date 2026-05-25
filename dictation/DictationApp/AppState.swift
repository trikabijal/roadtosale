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

    // MARK: - Engines

    private let recordingEngine = RecordingEngine()
    private let transcriptionEngine: TranscriptionEngine
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
        let tier = ModelTier(rawValue: UserDefaults.standard.string(forKey: "modelTier") ?? "") ?? .largeV3Turbo
        self.transcriptionEngine = TranscriptionEngine(modelTier: tier)
        self.autoPaste = UserDefaults.standard.object(forKey: "autoPaste") as? Bool ?? true
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

        // 2. Load WhisperKit model
        statusMessage = "Loading \(transcriptionEngine.modelTier.displayName) model…"
        do {
            try await transcriptionEngine.loadModel()
            engineLoaded = true
            statusMessage = "Ready — hold F5 to dictate"
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
            statusMessage = "Ready — hold F5 to dictate"
        }

        do {
            let result = try await transcriptionEngine.transcribe(buffers: buffers, audioStartDate: audioStartDate)

            guard !result.text.isEmpty else { return }

            // Write to clipboard and optionally paste
            let ap = autoPaste
            clipboardPaster.writeAndPaste(text: result.text, autoPaste: ap)

            // Build record — wordCount is computed automatically from transcriptText
            let frontmostApp = NSWorkspace.shared.frontmostApplication?.bundleIdentifier
            let record = TranscriptRecord(
                platform: "mac",
                audioDurationMs: result.audioDurationMs,
                transcriptText: result.text,
                whisperkitConfidence: result.confidence,
                latencyMs: result.latencyMs,
                modelTier: result.modelTier.rawValue,
                frontmostApp: frontmostApp
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

    func setModelTier(_ tier: ModelTier) {
        UserDefaults.standard.set(tier.rawValue, forKey: "modelTier")
        engineLoaded = false
        statusMessage = "Loading \(tier.displayName)…"
        Task {
            do {
                try await transcriptionEngine.setModelTier(tier)
                engineLoaded = true
                statusMessage = "Ready — hold F5 to dictate"
            } catch {
                statusMessage = "Load failed: \(error.localizedDescription)"
            }
        }
    }

    func setAutoPaste(_ value: Bool) {
        autoPaste = value
        UserDefaults.standard.set(value, forKey: "autoPaste")
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
