import AVFoundation
import DictationCoreBase
import WhisperKit

/// On-device WhisperKit transcriber. One implementation of the `SpeechTranscriber`
/// contract — selected via `STTProvider.whisperKit`.
@MainActor
public final class WhisperKitTranscriber: SpeechTranscriber {

    public private(set) var isLoaded = false
    public let modelTier: ModelTier

    private var whisperKit: WhisperKit?
    private var loadTask: Task<Void, Error>?
    private var biasPrompt: String?

    public init(modelTier: ModelTier = .largeV3Turbo) {
        self.modelTier = modelTier
    }

    public func reset() {
        loadTask?.cancel()
        loadTask = nil
        whisperKit = nil
        isLoaded = false
    }

    public func setVocabularyBias(_ terms: [String]) {
        let cleaned = terms.map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
        biasPrompt = cleaned.isEmpty ? nil : cleaned.joined(separator: ", ")
    }

    // MARK: - Load

    /// Keeps models under Application Support rather than Documents to avoid macOS TCC prompts.
    public static func modelDownloadBase() throws -> URL {
        let base = try FileManager.default.url(for: .applicationSupportDirectory,
                                               in: .userDomainMask, appropriateFor: nil, create: true)
        let dir = base.appendingPathComponent("com.trika.dictation/huggingface", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    public func load(onProgress: (@MainActor (Double) -> Void)? = nil) async throws {
        isLoaded = false
        loadTask?.cancel()
        let tier = modelTier
        let downloadBase = try Self.modelDownloadBase()
        loadTask = Task {
            let modelFolder = try await WhisperKit.download(variant: tier.rawValue,
                                                            downloadBase: downloadBase) { progress in
                Task { @MainActor in onProgress?(progress.fractionCompleted) }
            }
            if Task.isCancelled { return }
            onProgress?(1.0)

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

        var samples = buffers.flatMap { buffer -> [Float] in
            guard let channelData = buffer.floatChannelData?[0] else { return [] }
            let count = Int(buffer.frameLength)
            return Array(UnsafeBufferPointer(start: channelData, count: count))
        }

        guard !samples.isEmpty else { throw TranscriptionError.noAudioData }

        let audioDurationMs = Int(Double(samples.count) / RecordingEngine.targetSampleRate * 1000)

        let peak = samples.reduce(Float(0)) { Swift.max($0, abs($1)) }
        if peak < Self.silenceFloor { throw TranscriptionError.noAudioData }

        if peak < 0.7 {
            let gain = Swift.min(0.95 / peak, 12)
            for i in samples.indices { samples[i] *= gain }
        }

        var decodeOptions: DecodingOptions?
        if let biasPrompt, let promptTokens = wk.tokenizer?.encode(text: " " + biasPrompt) {
            decodeOptions = DecodingOptions(promptTokens: promptTokens)
        }

        let results = try await wk.transcribe(audioArray: samples, decodeOptions: decodeOptions)
        let latencyMs = Int(Date().timeIntervalSince(transcribeStart) * 1000)

        guard !results.isEmpty else { throw TranscriptionError.emptyResult }

        let segs = results.flatMap { $0.segments }
        let confidence: Double
        if !segs.isEmpty {
            let avgLogProb = segs.reduce(0.0) { $0 + Double($1.avgLogprob) } / Double(segs.count)
            confidence = max(0, min(1, exp(avgLogProb)))
        } else {
            confidence = 0.5
        }

        let text = results.map(\.text).joined(separator: " ")
            .trimmingCharacters(in: CharacterSet.whitespacesAndNewlines)

        if Self.isLikelyHallucination(text: text, confidence: confidence, durationMs: audioDurationMs) {
            throw TranscriptionError.emptyResult
        }

        let wordCount = text.split(whereSeparator: { $0.isWhitespace }).count
        if audioDurationMs > 10_000, wordCount < 3 { throw TranscriptionError.emptyResult }

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

    nonisolated static let silenceFloor: Float = 0.02

    nonisolated static let junkPhrases: Set<String> = [
        "thank you", "thanks", "thank you for watching", "thanks for watching",
        "please subscribe", "you", "bye", "okay", "uh", "um", ".",
    ]

    nonisolated static func isLikelyHallucination(text: String, confidence: Double, durationMs: Int) -> Bool {
        let normalized = text
            .lowercased()
            .trimmingCharacters(in: CharacterSet(charactersIn: " .,!?\n"))
        guard !normalized.isEmpty else { return true }
        guard junkPhrases.contains(normalized) else { return false }
        return durationMs < 1500 || confidence < 0.5
    }
}
