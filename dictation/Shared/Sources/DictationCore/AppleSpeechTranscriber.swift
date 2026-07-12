import AVFoundation
import Speech

/// Batch STT using `SFSpeechRecognizer` — Apple's on-device recogniser.
///
/// Used as the keyboard extension's transcription backend because WhisperKit's memory
/// footprint (~300 MB) exceeds the OS limit for keyboard extensions (~70 MB).
/// `SFSpeechRecognizer` needs no model download and fits in ~10 MB.
///
/// The `SpeechTranscriber` contract is batch: all captured audio arrives as an array of
/// `AVAudioPCMBuffer` already recorded by `RecordingEngine`. This implementation feeds
/// every buffer into an `SFSpeechAudioBufferRecognitionRequest`, signals end-of-audio,
/// then waits for the single final result — adapting the streaming API to the batch contract.
@MainActor
public final class AppleSpeechTranscriber: SpeechTranscriber {

    public private(set) var isLoaded = false

    /// BCP-47 locale identifier, e.g. "en-US", "hi-IN", "en-IN".
    private let language: String
    private var customVocabulary: [String] = []

    public init(language: String = SpeechDefaults.locale) {
        self.language = language
    }

    // MARK: - SpeechTranscriber

    public func load(onProgress: (@MainActor (Double) -> Void)?) async throws {
        // No model download needed. Verify a recognizer exists for this locale.
        guard SFSpeechRecognizer(locale: Locale(identifier: language)) != nil else {
            throw TranscriptionError.providerUnavailable(
                "Apple Speech: locale \(language) is not supported on this device"
            )
        }
        isLoaded = true
        onProgress?(1.0)
    }

    public func setVocabularyBias(_ terms: [String]) {
        customVocabulary = terms
    }

    /// Feeds all captured buffers into a batch `SFSpeechAudioBufferRecognitionRequest` and
    /// returns the single final result. Partial callbacks are suppressed — only the final
    /// result is returned, matching the `SpeechTranscriber` batch contract.
    public func transcribe(
        buffers: [AVAudioPCMBuffer],
        audioStartDate: Date
    ) async throws -> TranscriptionResult {
        guard isLoaded else { throw TranscriptionError.modelNotLoaded }
        guard !buffers.isEmpty else { throw TranscriptionError.noAudioData }

        let totalFrames = buffers.reduce(0) { $0 + Int($1.frameLength) }
        guard totalFrames > 0 else { throw TranscriptionError.noAudioData }

        let audioDurationMs = Int(Double(totalFrames) / RecordingEngine.targetSampleRate * 1000)
        let callStart = Date()

        // Capture value-type properties before suspension so the result-handler closure
        // never crosses an actor boundary to read `self`.
        let lang = language
        let vocab = customVocabulary

        guard let recognizer = SFSpeechRecognizer(locale: Locale(identifier: lang)),
              recognizer.isAvailable
        else {
            throw TranscriptionError.providerUnavailable("Apple Speech: \(lang) unavailable")
        }

        let request = SFSpeechAudioBufferRecognitionRequest()
        request.shouldReportPartialResults = false  // batch — one final callback only
        if !vocab.isEmpty { request.contextualStrings = vocab }

        return try await withCheckedThrowingContinuation { continuation in
            var settled = false

            recognizer.recognitionTask(with: request) { result, error in
                guard !settled else { return }

                if let error {
                    settled = true
                    let nsErr = error as NSError
                    // Code 203 = request cancelled (endAudio on short/silent audio).
                    // Code 1110 = no speech detected.
                    // Treat both as emptyResult so callers can offer a retry path.
                    if nsErr.code == 203 || nsErr.code == 1110 {
                        continuation.resume(throwing: TranscriptionError.emptyResult)
                    } else {
                        continuation.resume(throwing: error)
                    }
                    return
                }

                guard let result, result.isFinal else { return }
                settled = true

                let text = result.bestTranscription.formattedString
                    .trimmingCharacters(in: .whitespacesAndNewlines)
                guard !text.isEmpty else {
                    continuation.resume(throwing: TranscriptionError.emptyResult)
                    return
                }

                let confidence = result.bestTranscription.segments.last
                    .map { Double($0.confidence) } ?? 0.0
                let latencyMs = Int(Date().timeIntervalSince(callStart) * 1000)

                continuation.resume(returning: TranscriptionResult(
                    text: text,
                    confidence: confidence,
                    audioDurationMs: audioDurationMs,
                    latencyMs: latencyMs,
                    provider: .appleSpeech,
                    model: lang
                ))
            }

            // Feed all captured audio then signal end-of-audio. The recogniser fires the
            // final callback once it has processed everything.
            for buffer in buffers { request.append(buffer) }
            request.endAudio()
        }
    }
}
