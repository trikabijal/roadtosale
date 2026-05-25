import AVFoundation
import WhisperKit

// MARK: - Types

public enum ModelTier: String, CaseIterable, Sendable {
    /// ~40 MB — fits in iOS keyboard extension memory limit
    case tinyEn = "openai_whisper-tiny.en"
    /// ~75 MB — iOS app, good balance
    case baseEn = "openai_whisper-base.en"
    /// ~150 MB — iOS app or Mac where latency matters
    case smallEn = "openai_whisper-small.en"
    /// ~800 MB — macOS only, best accuracy
    case largeV3Turbo = "openai_whisper-large-v3-turbo"

    public var displayName: String {
        switch self {
        case .tinyEn: return "Tiny (fastest)"
        case .baseEn: return "Base (balanced)"
        case .smallEn: return "Small (accurate)"
        case .largeV3Turbo: return "Large Turbo (best)"
        }
    }
}

public struct TranscriptionResult: Sendable {
    public let text: String
    public let confidence: Double     // 0.0–1.0, average segment log-prob converted
    public let audioDurationMs: Int
    public let latencyMs: Int
    public let modelTier: ModelTier
}

// MARK: - Engine

@MainActor
public final class TranscriptionEngine: ObservableObject {

    @Published public private(set) var isLoaded = false
    @Published public private(set) var isTranscribing = false
    @Published public private(set) var modelTier: ModelTier

    private var whisperKit: WhisperKit?
    private var loadTask: Task<Void, Error>?

    public init(modelTier: ModelTier = .baseEn) {
        self.modelTier = modelTier
    }

    // MARK: - Public

    public func loadModel() async throws {
        isLoaded = false
        // Cancel any in-flight load
        loadTask?.cancel()
        let tier = modelTier
        loadTask = Task {
            let wk = try await WhisperKit(WhisperKitConfig(model: tier.rawValue))
            if !Task.isCancelled {
                self.whisperKit = wk
                self.isLoaded = true
            }
        }
        try await loadTask!.value
    }

    public func setModelTier(_ tier: ModelTier) async throws {
        modelTier = tier
        try await loadModel()
    }

    /// Transcribe accumulated audio buffers. audioStartDate is when recording began.
    public func transcribe(
        buffers: [AVAudioPCMBuffer],
        audioStartDate: Date
    ) async throws -> TranscriptionResult {
        guard let wk = whisperKit else {
            throw TranscriptionError.modelNotLoaded
        }

        isTranscribing = true
        defer { isTranscribing = false }

        let transcribeStart = Date()

        // Merge all Float32 samples into one array
        let samples = buffers.flatMap { buffer -> [Float] in
            guard let channelData = buffer.floatChannelData?[0] else { return [] }
            let count = Int(buffer.frameLength)
            return Array(UnsafeBufferPointer(start: channelData, count: count))
        }

        guard !samples.isEmpty else {
            throw TranscriptionError.noAudioData
        }

        // Duration from sample count at 16kHz
        let audioDurationMs = Int(Double(samples.count) / RecordingEngine.targetSampleRate * 1000)

        let results = try await wk.transcribe(audioArray: samples)
        let transcribeEnd = Date()
        let latencyMs = Int(transcribeEnd.timeIntervalSince(transcribeStart) * 1000)

        guard let first = results?.first else {
            throw TranscriptionError.emptyResult
        }

        // Compute confidence: average exp(avgLogprob) across segments
        let confidence: Double
        if let segs = results?.flatMap({ $0.segments }), !segs.isEmpty {
            let avgLogProb = segs.reduce(0.0) { $0 + Double($1.avgLogprob) } / Double(segs.count)
            confidence = max(0, min(1, exp(avgLogProb)))
        } else {
            confidence = 0.5 // fallback
        }

        return TranscriptionResult(
            text: first.text.trimmingCharacters(in: .whitespacesAndNewlines),
            confidence: confidence,
            audioDurationMs: audioDurationMs,
            latencyMs: latencyMs,
            modelTier: modelTier
        )
    }
}

// MARK: - Errors

public enum TranscriptionError: Error, LocalizedError {
    case modelNotLoaded
    case noAudioData
    case emptyResult

    public var errorDescription: String? {
        switch self {
        case .modelNotLoaded: return "WhisperKit model is not loaded yet."
        case .noAudioData: return "No audio was recorded."
        case .emptyResult: return "Transcription returned no text."
        }
    }
}
