import AVFoundation
import WhisperKit

// MARK: - WhisperKit model tiers

public enum ModelTier: String, CaseIterable, Sendable {
    // English-only (.en) tiers — fast, but mangle non-English. Good for English-only use (coding).
    /// ~40 MB — fits in iOS keyboard extension memory limit.
    case tinyEn = "openai_whisper-tiny.en"
    /// ~75 MB.
    case baseEn = "openai_whisper-base.en"
    /// ~150 MB — fast + accurate for English-only contexts.
    case smallEn = "openai_whisper-small.en"

    // Multilingual tiers — handle Hindi/Gujarati (Hinglish). Smaller = faster but weaker on
    // low-resource languages (esp. Gujarati), which need a large model.
    /// Multilingual small — much faster than the large tiers; decent Hindi, weak Gujarati.
    case small = "openai_whisper-small"
    /// Multilingual, speed-optimized large (pruned decoder). Default — Hinglish at reasonable speed.
    case largeV3Turbo = "openai_whisper-large-v3_turbo_954MB"
    /// Multilingual, full large-v3 — best accuracy incl. Gujarati; slowest/largest (~3 GB download).
    case largeV3 = "openai_whisper-large-v3"

    public var displayName: String {
        switch self {
        case .tinyEn:       return "Tiny (English, fastest)"
        case .baseEn:       return "Base (English)"
        case .smallEn:      return "Small (English, fast)"
        case .small:        return "Small (multilingual)"
        case .largeV3Turbo: return "Large Turbo (multilingual, balanced)"
        case .largeV3:      return "Large v3 (multilingual, best)"
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
    private var biasPrompt: String?

    public init(modelTier: ModelTier = .largeV3Turbo) {
        self.modelTier = modelTier
    }

    /// Custom-vocabulary biasing: the terms become a decoder conditioning prompt so
    /// names/jargon transcribe correctly.
    /// Drop the loaded model (e.g. after a timeout) so a stuck/orphaned transcribe can't keep
    /// holding it; the next transcribe reloads fresh.
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

    /// Where model files are downloaded/cached. WhisperKit's default `downloadBase` is the user's
    /// **Documents** folder — on a non-sandboxed Mac app every access there triggers a macOS
    /// "allow access to Documents" TCC prompt, so a multi-tier app fires a burst of them. We instead
    /// keep models under Application Support, alongside the telemetry DB and recordings, which needs
    /// no TCC grant. Named `huggingface` so the on-disk layout (`<base>/models/<repo>/<variant>`)
    /// matches WhisperKit's default `Documents/huggingface`, making the existing cache portable here.
    public static func modelDownloadBase() throws -> URL {
        let base = try FileManager.default.url(for: .applicationSupportDirectory,
                                               in: .userDomainMask, appropriateFor: nil, create: true)
        let dir = base.appendingPathComponent("com.trika.dictation/huggingface", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    /// Downloads (first run only) and loads the model. `onProgress` reports download
    /// completion fraction (0.0–1.0) on the main actor — the large models are
    /// ~150 MB–1 GB, so the first launch needs visible progress.
    public func load(onProgress: (@MainActor (Double) -> Void)? = nil) async throws {
        isLoaded = false
        loadTask?.cancel()
        let tier = modelTier
        let downloadBase = try Self.modelDownloadBase()
        loadTask = Task {
            // 1. Fetch model files (returns immediately from cache on later runs). downloadBase keeps
            //    them out of ~/Documents so macOS doesn't prompt for Documents-folder access.
            let modelFolder = try await WhisperKit.download(variant: tier.rawValue,
                                                            downloadBase: downloadBase) { progress in
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
        var samples = buffers.flatMap { buffer -> [Float] in
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

        // Gain-normalize quiet recordings. Low mic levels (real example: peak ≈ 0.10, ~10% of
        // full scale) degrade accuracy and provoke trailing hallucinations ("thank you for
        // watching"). Boost so the peak ≈ 0.95 — only amplify, never attenuate, and cap the
        // gain so a near-silent clip's noise floor isn't blown up.
        if peak < 0.7 {
            let gain = Swift.min(0.95 / peak, 12)
            for i in samples.indices { samples[i] *= gain }
        }

        // Apply custom-vocabulary biasing as a decoder prompt when set. (WhisperKit windows
        // >30s audio internally — verified 60s → full transcript with and without an explicit
        // chunking strategy — so no chunking option is needed here; the long-audio failure was
        // a capture-side buffer-ordering bug, not transcription.)
        var decodeOptions: DecodingOptions?
        if let biasPrompt, let promptTokens = wk.tokenizer?.encode(text: " " + biasPrompt) {
            decodeOptions = DecodingOptions(promptTokens: promptTokens)
        }

        let results = try await wk.transcribe(audioArray: samples, decodeOptions: decodeOptions)
        let latencyMs = Int(Date().timeIntervalSince(transcribeStart) * 1000)

        guard !results.isEmpty else {
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

        // Join ALL result entries, not just the first — for long/windowed audio WhisperKit can
        // emit more than one, and confidence is already averaged across all of them, so taking
        // only `first.text` would silently truncate the transcript.
        let text = results.map(\.text).joined(separator: " ")
            .trimmingCharacters(in: CharacterSet.whitespacesAndNewlines)

        // Reject low-confidence single phantom phrases on short clips.
        if Self.isLikelyHallucination(text: text, confidence: confidence, durationMs: audioDurationMs) {
            throw TranscriptionError.emptyResult
        }

        // A long clip that yields almost no words is a transcription failure, not real speech
        // (the old long-audio junk). Surface it as empty so the dictation is PRESERVED for
        // retry rather than pasting garbage and discarding the audio.
        let wordCount = text.split(whereSeparator: { $0.isWhitespace }).count
        if audioDurationMs > 10_000, wordCount < 3 {
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

    // MARK: - Streaming session (PRD 0008 — the live pill)

    /// Vend a LocalAgreement streaming session that reuses THIS transcriber's already-loaded model
    /// (no second model, no second mic). Returns nil until the model is loaded — the caller then
    /// falls back to the per-segment preview. Only ever drives the live pill; the pasted text still
    /// comes from `transcribe(buffers:)`.
    public func makeStreamingSession() -> (any StreamingTranscriber)? {
        guard let wk = whisperKit else { return nil }
        return WhisperKitStreamingSession(whisperKit: wk, biasPrompt: biasPrompt)
    }

    // MARK: - Hallucination filter

    /// Peak below this counts as silence (≈ -34 dBFS). Conservative so quiet speech survives.
    nonisolated static let silenceFloor: Float = 0.02

    /// Known WhisperKit silence/no-speech hallucinations, normalized. NOTE: candidate for
    /// the portable cleanup data-pack (PRD 0004 FR-B0) — keep it data-shaped.
    nonisolated static let junkPhrases: Set<String> = [
        "thank you", "thanks", "thank you for watching", "thanks for watching",
        "please subscribe", "you", "bye", "okay", "uh", "um", ".",
    ]

    /// Pure, testable: true when the transcript looks like a phantom phrase rather than
    /// real dictation. Only fires on short clips so genuine short answers survive.
    nonisolated static func isLikelyHallucination(text: String, confidence: Double, durationMs: Int) -> Bool {
        let normalized = text
            .lowercased()
            .trimmingCharacters(in: CharacterSet(charactersIn: " .,!?\n"))
        guard !normalized.isEmpty else { return true }
        guard junkPhrases.contains(normalized) else { return false }
        return durationMs < 1500 || confidence < 0.5
    }
}

// MARK: - WhisperKit streaming session (LocalAgreement-2, PRD 0008)

/// Streaming transcriber backing the live pill. Reuses a loaded `WhisperKit` instance (shared with
/// the `WhisperKitTranscriber` that vended it) and re-transcribes the growing audio each `step`,
/// applying `StreamingAgreement` to split stable confirmed text from the revisable hypothesis tail.
///
/// The confirmed/hypothesis logic and the `clipTimestamps=[lastConfirmedEnd]` windowing are the same
/// as WhisperKit's own `AudioStreamTranscriber` (see PRD 0008 §0) — we run them over OUR fed buffers
/// instead of WhisperKit's self-owned microphone, so `RecordingEngine`'s capture/retry/VAD/metering
/// stay intact.
@MainActor
public final class WhisperKitStreamingSession: StreamingTranscriber {
    private let whisperKit: WhisperKit
    private let biasPrompt: String?
    private var agreement = StreamingAgreement(requiredUnconfirmed: 2)
    private var last = StreamingTranscript.empty
    /// Guard against the O(n²) re-encode blow-up: past this much audio we stop running new streaming
    /// passes (the pill freezes at the last confirmed text) — the accurate pasted text is the batch
    /// pass at stop regardless, so long dictations lose only live-pill motion, not correctness.
    private static let maxStreamSeconds: Double = 45

    public init(whisperKit: WhisperKit, biasPrompt: String?) {
        self.whisperKit = whisperKit
        self.biasPrompt = biasPrompt
    }

    public func step(samples: [Float]) async -> StreamingTranscript {
        guard !samples.isEmpty else { return last }
        let seconds = Double(samples.count) / RecordingEngine.targetSampleRate
        // Stop feeding new audio once past the guard, but let the tail finish confirming.
        guard seconds <= Self.maxStreamSeconds else { return last }
        // Silence floor — don't let WhisperKit hallucinate a phantom phrase into the pill.
        let peak = samples.reduce(Float(0)) { Swift.max($0, abs($1)) }
        guard peak >= WhisperKitTranscriber.silenceFloor else { return last }

        var options = DecodingOptions()
        options.clipTimestamps = [Float(agreement.lastConfirmedEnd)]
        if let biasPrompt, let tokens = whisperKit.tokenizer?.encode(text: " " + biasPrompt) {
            options.promptTokens = tokens
        }

        let start = Date()
        guard let results = try? await whisperKit.transcribe(audioArray: samples, decodeOptions: options),
              !results.isEmpty else {
            return last   // a failed pass is a no-op for the preview — never abort the dictation
        }
        let latencyMs = Int(Date().timeIntervalSince(start) * 1000)

        let segs = results.flatMap { $0.segments }
        let fresh = segs.map { AgreedSegment(text: $0.text, start: Double($0.start), end: Double($0.end)) }
        agreement.integrate(fresh)

        let confidence: Double
        if segs.isEmpty {
            confidence = last.confidence
        } else {
            let avg = segs.reduce(0.0) { $0 + Double($1.avgLogprob) } / Double(segs.count)
            confidence = max(0, min(1, exp(avg)))
        }
        last = StreamingTranscript(confirmed: agreement.confirmedText,
                                   hypothesis: agreement.hypothesisText,
                                   confidence: confidence, latencyMs: latencyMs)
        return last
    }

    public func finish(samples: [Float]?) async -> StreamingTranscript {
        if let samples { _ = await step(samples: samples) }
        let confirmed = agreement.flushHypothesis()
        last = StreamingTranscript(confirmed: confirmed, hypothesis: "",
                                   confidence: last.confidence, latencyMs: last.latencyMs)
        return last
    }

    public func reset() {
        agreement = StreamingAgreement(requiredUnconfirmed: 2)
        last = .empty
    }
}
