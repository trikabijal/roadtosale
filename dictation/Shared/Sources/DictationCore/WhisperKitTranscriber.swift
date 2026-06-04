import AVFoundation
import WhisperKit

// MARK: - WhisperKit model tiers

public enum ModelTier: String, CaseIterable, Sendable {
    /// ~40 MB — fits in iOS keyboard extension memory limit
    case tinyEn = "openai_whisper-tiny.en"
    /// ~75 MB — iOS app, good balance
    case baseEn = "openai_whisper-base.en"
    /// ~150 MB — iOS app or Mac where latency matters
    case smallEn = "openai_whisper-small.en"
    /// ~800 MB — macOS only, best accuracy
    /// Model name must match WhisperKit 0.18.0 HuggingFace repo exactly
    case largeV3Turbo = "openai_whisper-large-v3_turbo_954MB"

    public var displayName: String {
        switch self {
        case .tinyEn: return "Tiny (fastest)"
        case .baseEn: return "Base (balanced)"
        case .smallEn: return "Small (accurate)"
        case .largeV3Turbo: return "Large Turbo (best)"
        }
    }
}

// MARK: - WhisperKit implementation of SpeechTranscriber

/// On-device WhisperKit transcriber. One implementation of the `SpeechTranscriber`
/// contract — selected via `STTProvider.whisperKit`.
@MainActor
public final class WhisperKitTranscriber: SpeechTranscriber {

    public private(set) var isLoaded = false
    public let modelTier: ModelTier

    private var whisperKit: WhisperKit?
    private var loadTask: Task<Void, Error>?

    public init(modelTier: ModelTier = .largeV3Turbo) {
        self.modelTier = modelTier
    }

    // MARK: - Load

    /// Downloads (first run only) and loads the model. `onProgress` reports download
    /// completion fraction (0.0–1.0) on the main actor — the large models are
    /// ~150 MB–1 GB, so the first launch needs visible progress.
    public func load(onProgress: (@MainActor (Double) -> Void)? = nil) async throws {
        isLoaded = false
        loadTask?.cancel()
        let tier = modelTier
        loadTask = Task {
            // 1. Fetch model files (returns immediately from cache on later runs).
            let modelFolder = try await WhisperKit.download(variant: tier.rawValue) { progress in
                Task { @MainActor in onProgress?(progress.fractionCompleted) }
            }
            if Task.isCancelled { return }
            onProgress?(1.0)

            // 2. Load from the local folder (no re-download). Pass the variant name too
            //    so WhisperKit selects the matching tokenizer.
            let config = WhisperKitConfig(
                model: tier.rawValue,
                modelFolder: modelFolder.path,
                download: false
            )
            let wk = try await WhisperKit(config)
            if !Task.isCancelled {
                self.whisperKit = wk
                self.isLoaded = true
            }
        }
        try await loadTask!.value
    }

    // MARK: - Transcribe

    public func transcribe(
        buffers: [AVAudioPCMBuffer],
        audioStartDate: Date
    ) async throws -> TranscriptionResult {
        guard let wk = whisperKit else {
            throw TranscriptionError.modelNotLoaded
        }

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

        // Peak amplitude across the clip. WhisperKit hallucinates confident phantom
        // phrases ("Thank you.", "Thanks for watching") on effectively-silent audio,
        // so skip transcription entirely when nothing was actually said.
        let peak = samples.reduce(Float(0)) { Swift.max($0, abs($1)) }
        if peak < Self.silenceFloor {
            throw TranscriptionError.noAudioData
        }

        let results = try await wk.transcribe(audioArray: samples)
        let latencyMs = Int(Date().timeIntervalSince(transcribeStart) * 1000)

        guard let first = results.first else {
            throw TranscriptionError.emptyResult
        }

        // Confidence: average exp(avgLogprob) across segments
        let confidence: Double
        let segs = results.flatMap { $0.segments }
        if !segs.isEmpty {
            let avgLogProb = segs.reduce(0.0) { $0 + Double($1.avgLogprob) } / Double(segs.count)
            confidence = max(0, min(1, exp(avgLogProb)))
        } else {
            confidence = 0.5
        }

        let text = first.text.trimmingCharacters(in: CharacterSet.whitespacesAndNewlines)

        // Reject low-confidence single phantom phrases on short clips.
        if Self.isLikelyHallucination(text: text, confidence: confidence, durationMs: audioDurationMs) {
            throw TranscriptionError.emptyResult
        }

        return TranscriptionResult(
            text: text,
            confidence: confidence,
            audioDurationMs: audioDurationMs,
            latencyMs: latencyMs,
            provider: .whisperKit,
            model: modelTier.rawValue
        )
    }

    // MARK: - Hallucination filter

    /// Peak below this counts as silence (≈ -34 dBFS). Conservative so quiet speech survives.
    static let silenceFloor: Float = 0.02

    /// Known WhisperKit silence/no-speech hallucinations, normalized. NOTE: candidate for
    /// the portable cleanup data-pack (PRD 0004 FR-B0) — keep it data-shaped.
    static let junkPhrases: Set<String> = [
        "thank you", "thanks", "thank you for watching", "thanks for watching",
        "please subscribe", "you", "bye", "okay", "uh", "um", ".",
    ]

    /// Pure, testable: true when the transcript looks like a phantom phrase rather than
    /// real dictation. Only fires on short clips so genuine short answers survive.
    static func isLikelyHallucination(text: String, confidence: Double, durationMs: Int) -> Bool {
        let normalized = text
            .lowercased()
            .trimmingCharacters(in: CharacterSet(charactersIn: " .,!?\n"))
        guard !normalized.isEmpty else { return true }
        guard junkPhrases.contains(normalized) else { return false }
        return durationMs < 1500 || confidence < 0.5
    }
}
