import AVFoundation
import Combine
import DictationCore
import UIKit

public enum DictationState {
    case idle, recording, transcribing
}

@MainActor
public final class KeyboardViewModel: NSObject, ObservableObject {

    @Published public var state: DictationState = .idle
    @Published public var lastTranscript: String = ""
    @Published public var engineLoaded = false
    @Published public var statusMessage = "Loading model…"
    @Published public var lastWasCorrected = false

    // Called by KeyboardViewController to insert text
    var insertTextCallback: ((String) -> Void)?
    var deleteBackCallback: (() -> Void)?

    private let recordingEngine = RecordingEngine()
    // tinyEn — ~40MB, fits iOS keyboard extension memory budget (~50MB active).
    // WhisperKit is one implementation of the SpeechTranscriber contract.
    private let transcriber: any SpeechTranscriber = WhisperKitTranscriber(modelTier: .tinyEn)
    private var telemetryStore: TelemetryStore?

    // AI cleanup — the SAME contract + data pack as macOS. Apple Foundation Models runs as an
    // on-device system service (not loaded into the extension's own memory budget), with a
    // deterministic rule-based fallback. Default level Full. The <transcript>-tag sanitizer and
    // prompt fixes live in DictationCore, so iOS inherits them automatically.
    private let cleanupPack: CleanupPack
    private let cleanupConfig: CleanupConfig
    private let cleanup: any TextCleanup

    private var audioBuffers: [AVAudioPCMBuffer] = []
    private var recordingStartDate: Date?

    override public init() {
        let pack = CleanupPackLoader.load()
        self.cleanupPack = pack
        let config = CleanupConfig.default
        self.cleanupConfig = config
        self.cleanup = TextCleanupFactory.make(config, pack: pack)
        super.init()
        recordingEngine.delegate = self
        Task { await setup() }
    }

    // MARK: - Setup

    private func setup() async {
        do {
            try await recordingEngine.requestPermission()
        } catch {
            statusMessage = "Mic denied"
            return
        }

        do {
            try await transcriber.load()
            engineLoaded = transcriber.isLoaded
            statusMessage = "Tap mic to dictate"
        } catch {
            statusMessage = "Model load failed"
            return
        }

        do {
            let url = try TelemetryStore.iOSDatabaseURL()
            telemetryStore = try TelemetryStore(databaseURL: url)
        } catch {
            // Non-fatal
        }
    }

    // MARK: - Recording toggle (tap-to-toggle)

    public func toggleRecording() {
        switch state {
        case .idle:
            startRecording()
        case .recording:
            stopRecordingAndTranscribe()
        case .transcribing:
            break // ignore taps while transcribing
        }
    }

    private func startRecording() {
        guard engineLoaded else { return }
        audioBuffers = []
        recordingStartDate = Date()
        do {
            try recordingEngine.start()
            state = .recording
            statusMessage = "Recording…"
        } catch {
            statusMessage = "Failed: \(error.localizedDescription)"
        }
    }

    private func stopRecordingAndTranscribe() {
        guard state == .recording else { return }
        recordingEngine.stop()
        state = .transcribing
        statusMessage = "Transcribing…"
        let buffers = audioBuffers
        let startDate = recordingStartDate ?? Date()
        Task { await performTranscription(buffers: buffers, audioStartDate: startDate) }
    }

    // MARK: - Transcription

    private func performTranscription(buffers: [AVAudioPCMBuffer], audioStartDate: Date) async {
        defer {
            state = .idle
            statusMessage = "Tap mic to dictate"
        }

        do {
            let result = try await transcriber.transcribe(buffers: buffers, audioStartDate: audioStartDate)
            let rawText = result.text
            guard !rawText.isEmpty else { return }

            // AI cleanup pass (the Wispr brain). Skipped when off or too short to benefit.
            // Never throws — falls back to rule-based internally.
            let level = cleanupConfig.level
            let wordCount = rawText.split(whereSeparator: { $0.isWhitespace }).count
            let shouldClean = level != .off && wordCount >= cleanupPack.minWordsForCleanup
            let cleanupResult: CleanupResult?
            if shouldClean {
                statusMessage = "Cleaning…"
                cleanupResult = await cleanup.clean(CleanupRequest(
                    rawText: rawText,
                    level: level,
                    vocab: [:],
                    commandGrammar: cleanupPack.commandGrammar,
                    profile: cleanupPack.profile
                ))
            } else {
                cleanupResult = nil
            }
            let finalText = cleanupResult?.cleanedText ?? rawText
            guard !finalText.isEmpty else { return }

            // Insert into the text field
            insertTextCallback?(finalText + " ")
            lastTranscript = finalText
            lastWasCorrected = false

            // Telemetry (v2 — raw + cleaned + cleanup metadata, parity with macOS)
            let record = TranscriptRecord(
                platform: "ios",
                audioDurationMs: result.audioDurationMs,
                transcriptText: finalText,
                whisperkitConfidence: result.confidence,
                latencyMs: result.latencyMs,
                modelTier: "\(result.provider.rawValue)/\(result.model)",
                frontmostApp: nil,
                rawText: rawText,
                cleanupLevel: shouldClean ? level.rawValue : CleanupLevel.off.rawValue,
                cleanupProvider: cleanupResult.map { $0.usedFallback ? "\($0.provider.rawValue)+fallback" : $0.provider.rawValue }
            )
            try? await telemetryStore?.save(record)

        } catch TranscriptionError.emptyResult {
            // Nothing was said
        } catch {
            statusMessage = "Error: \(error.localizedDescription)"
            try? await Task.sleep(for: .seconds(2))
        }
    }

    // MARK: - Correction

    public func markLastCorrected() {
        lastWasCorrected = true
        // We don't have the record id easily here — mark last record in DB
        Task {
            if let records = try? await telemetryStore?.fetchRecent(limit: 1),
               let last = records.first {
                try? await telemetryStore?.markCorrected(id: last.id, note: nil)
            }
        }
    }
}

// MARK: - RecordingEngineDelegate

extension KeyboardViewModel: RecordingEngineDelegate {
    public nonisolated func recordingEngine(_ engine: RecordingEngine, didReceiveBuffer buffer: AVAudioPCMBuffer) {
        Task { @MainActor in self.audioBuffers.append(buffer) }
    }

    /// VAD silence does NOT auto-stop — the user taps the mic again to stop (same decision as
    /// macOS, where auto-stopping on a pause chopped sentences off mid-thought).
    public nonisolated func recordingEngineDidDetectSilence(_ engine: RecordingEngine) {
        // no-op
    }
}
