import AVFoundation
import WhisperKit
import DictationCoreBase

// `ModelTier` — the WhisperKit model ids, `defaultWhisper`, and display names — is the SINGLE source of
// truth in DictationCoreBase/ModelTier.swift (imported above). It used to be duplicated here (Base can't
// depend on WhisperKit), which risked the two `defaultWhisper` drifting; the duplicate is now gone.

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
    ) async throws -> DictationCoreBase.TranscriptionResult {
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

        return DictationCoreBase.TranscriptionResult(
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
    nonisolated static let silenceFloor: Float = AudioLevels.silenceFloor

    /// Known WhisperKit silence/no-speech hallucinations, normalized. Sourced from the shared
    /// `defaultJunkPhrases` (DictationCoreBase) so the cleanup pack and this filter can't diverge.
    nonisolated static let junkPhrases = Set(defaultJunkPhrases)

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
    private var agreement = StreamingAgreement(requiredUnconfirmed: 1)
    private var last = StreamingTranscript.empty
    /// WhisperKit re-decode is expensive, so throttle it to this cadence even when `step` is called
    /// far more often (the tick is 100ms for Apple's benefit). Between decodes, return the last result.
    private var lastDecodeAt: Date?
    private static let minDecodeInterval: TimeInterval = 0.5
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
        // Throttle the expensive re-decode: the tick calls step frequently (for Apple), but WhisperKit
        // only needs to re-run every ~0.5s. Between decodes, hand back the last result cheaply.
        let now = Date()
        if let lastDecodeAt, now.timeIntervalSince(lastDecodeAt) < Self.minDecodeInterval { return last }
        lastDecodeAt = now

        // Silence floor — don't let WhisperKit hallucinate a phantom phrase into the pill.
        let peak = samples.reduce(Float(0)) { Swift.max($0, abs($1)) }
        guard peak >= WhisperKitTranscriber.silenceFloor else { return last }

        var options = DecodingOptions()
        options.clipTimestamps = [Float(agreement.lastConfirmedEnd)]
        // NOTE: deliberately NO vocab-bias promptTokens here. WhisperKit echoes the prompt into the
        // output on thin/near-silent windows, dumping the private vocab list onto the live pill (the
        // F10 echo class). The pill is preview only; the accurate PASTED text is the batch pass, which
        // keeps the bias. So spelling accuracy is unaffected — we just stop the echo on the pill.

        guard let results = try? await whisperKit.transcribe(audioArray: samples, decodeOptions: options),
              !results.isEmpty else {
            return last   // a failed pass is a no-op for the preview — never abort the dictation
        }

        let segs = results.flatMap { $0.segments }
        // Strip WhisperKit timestamp/special tokens (`<|5.90|>`, `<|startoftranscript|>`) that live in
        // raw segment text — the batch path uses the cleaned result text, but segment text keeps them.
        let fresh = segs.map { AgreedSegment(text: Self.sanitize($0.text), start: Double($0.start), end: Double($0.end)) }
        agreement.integrate(fresh)

        last = StreamingTranscript(confirmed: agreement.confirmedText, hypothesis: agreement.hypothesisText)
        return last
    }

    public func reset() {
        agreement = StreamingAgreement(requiredUnconfirmed: 1)
        last = .empty
        lastDecodeAt = nil
    }

    /// Strip WhisperKit special/timestamp tokens (`<|5.90|>`, `<|startoftranscript|>`, …) and collapse
    /// whitespace. Raw segment text and the decode-progress text both carry these; without stripping,
    /// the timestamps ("5.90 6.32") render straight onto the pill.
    nonisolated static func sanitize(_ raw: String) -> String {
        raw.replacingOccurrences(of: "<\\|[^|]*\\|>", with: "", options: .regularExpression)
            .replacingOccurrences(of: "\\s+", with: " ", options: .regularExpression)
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }
}
