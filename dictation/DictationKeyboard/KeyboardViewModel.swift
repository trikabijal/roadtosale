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

    private var audioBuffers: [AVAudioPCMBuffer] = []
    private var recordingStartDate: Date?

    override public init() {
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
            guard !result.text.isEmpty else { return }

            // Insert into the text field
            insertTextCallback?(result.text + " ")
            lastTranscript = result.text
            lastWasCorrected = false

            // Telemetry
            let record = TranscriptRecord(
                platform: "ios",
                audioDurationMs: result.audioDurationMs,
                transcriptText: result.text,
                whisperkitConfidence: result.confidence,
                latencyMs: result.latencyMs,
                modelTier: "\(result.provider.rawValue)/\(result.model)"
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

    public nonisolated func recordingEngineDidDetectSilence(_ engine: RecordingEngine) {
        Task { @MainActor in self.stopRecordingAndTranscribe() }
    }
}
