import AVFoundation
import Speech

// MARK: - Apple SpeechAnalyzer implementation of SpeechTranscriber (PRD 0008)
//
// The `appleSpeech` provider, backed by macOS 26 / iOS 26 `SpeechAnalyzer` + `SpeechTranscriber`.
// It is the FAST + SMOOTH-STREAMING path (Apple's on-device model is ~2× WhisperKit large-v3-turbo
// and streams native volatile/finalized results) — best for ENGLISH. WhisperKit stays the
// multilingual/accuracy provider (Apple's fast engine ships English + major languages but, verified
// on-device, NO Hindi/Gujarati — so Hinglish/Gujarati must use WhisperKit).
//
// NOTE the name clash: our contract protocol is `SpeechTranscriber` (DictationCore); Apple's class is
// `Speech.SpeechTranscriber`. Always fully-qualify Apple's.

/// Convert an `AVAudioPCMBuffer` to the format Apple's analyzer wants. Cached converter per format.
@available(macOS 26.0, iOS 26.0, *)
final class AppleAudioConverter {
    private var converter: AVAudioConverter?
    private var fromFormat: AVAudioFormat?
    let target: AVAudioFormat

    init(target: AVAudioFormat) { self.target = target }

    func convert(_ input: AVAudioPCMBuffer) -> AVAudioPCMBuffer? {
        if input.format == target { return input }
        if converter == nil || fromFormat != input.format {
            converter = AVAudioConverter(from: input.format, to: target)
            fromFormat = input.format
        }
        guard let converter else { return nil }
        let ratio = target.sampleRate / input.format.sampleRate
        let capacity = AVAudioFrameCount(Double(input.frameLength) * ratio + 64)
        guard let out = AVAudioPCMBuffer(pcmFormat: target, frameCapacity: capacity) else { return nil }
        var fed = false
        var err: NSError?
        converter.convert(to: out, error: &err) { _, status in
            if fed { status.pointee = .noDataNow; return nil }
            fed = true
            status.pointee = .haveData
            return input
        }
        if err != nil || out.frameLength == 0 { return nil }
        return out
    }
}

@available(macOS 26.0, iOS 26.0, *)
@MainActor
public final class AppleAnalyzerTranscriber: DictationCoreBase.SpeechTranscriber {
    public private(set) var isLoaded = false
    private let locale: Locale
    private var audioFormat: AVAudioFormat?

    /// `model` from STTConfig is a BCP-47 locale id (e.g. "en-US"); default to the user's locale.
    public init(localeIdentifier: String = "") {
        if localeIdentifier.isEmpty {
            self.locale = Locale(identifier: SpeechDefaults.locale)
        } else {
            self.locale = Locale(identifier: localeIdentifier)
        }
    }

    public func load(onProgress: (@MainActor (Double) -> Void)? = nil) async throws {
        isLoaded = false
        // Speech authorization is required. If denied/restricted, THROW so `load()` fails cleanly
        // (AppState marks the engine unavailable and surfaces a message) instead of silently
        // "succeeding" and then returning empty on every dictation. The user can switch to WhisperKit
        // in Settings. (Auto-fallback to WhisperKit is a possible future enhancement, not done here.)
        let status = await withCheckedContinuation { (c: CheckedContinuation<SFSpeechRecognizerAuthorizationStatus, Never>) in
            SFSpeechRecognizer.requestAuthorization { c.resume(returning: $0) }
        }
        guard status == .authorized else {
            throw TranscriptionError.providerUnavailable("Speech recognition isn't authorized — enable it in System Settings, or use WhisperKit")
        }

        let module = Speech.SpeechTranscriber(locale: locale, preset: .progressiveTranscription)

        // Verify the locale is one Apple actually supports (verified on-device: en-* yes, hi/gu no).
        let supported = await Speech.SpeechTranscriber.supportedLocales
        let wanted = locale.identifier(.bcp47)
        guard supported.contains(where: { $0.identifier(.bcp47) == wanted }) else {
            throw TranscriptionError.providerUnavailable("Apple Speech has no model for \(wanted)")
        }

        // Download the language asset on first use (no-op once installed).
        if let request = try await AssetInventory.assetInstallationRequest(supporting: [module]) {
            onProgress?(0.1)
            try await request.downloadAndInstall()
        }
        onProgress?(1.0)

        audioFormat = await SpeechAnalyzer.bestAvailableAudioFormat(compatibleWith: [module])
        isLoaded = true
    }

    public func reset() { isLoaded = false }

    // Apple biasing (contextual strings) is a future addition; no-op for now. Spelling of names on
    // the English path is generally strong; multilingual/vocab-heavy dictation uses WhisperKit.
    public func setVocabularyBias(_ terms: [String]) {}

    /// Batch transcription (the pasted-output path). Feeds all buffers through a one-shot analyzer and
    /// returns the finalized text. Apple's model is fast, so this keeps stop snappy.
    public func transcribe(buffers: [AVAudioPCMBuffer], audioStartDate: Date) async throws -> TranscriptionResult {
        guard isLoaded, let audioFormat else { throw TranscriptionError.modelNotLoaded }
        guard !buffers.isEmpty else { throw TranscriptionError.noAudioData }
        let start = Date()

        let module = Speech.SpeechTranscriber(locale: locale, preset: .transcription)
        let analyzer = SpeechAnalyzer(modules: [module])
        let converter = AppleAudioConverter(target: audioFormat)

        let (stream, cont) = AsyncStream<AnalyzerInput>.makeStream()
        let collector = Task { () -> String in
            var acc = AttributedString()
            for try await result in module.results where result.isFinal { acc += result.text }
            return String(acc.characters)
        }
        // If `analyzer.start`/`finalize` throws, the error propagates out — make sure the collector Task
        // (and the input stream) don't leak an abandoned Task + analyzer per failed transcription.
        defer { collector.cancel(); cont.finish() }

        try await analyzer.start(inputSequence: stream)
        for buffer in buffers {
            if let converted = converter.convert(buffer) { cont.yield(AnalyzerInput(buffer: converted)) }
        }
        cont.finish()
        try await analyzer.finalizeAndFinishThroughEndOfInput()

        let text = ((try? await collector.value) ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { throw TranscriptionError.emptyResult }

        let audioMs = Int(buffers.reduce(0) { $0 + Double($1.frameLength) / $1.format.sampleRate } * 1000)
        return TranscriptionResult(
            text: text,
            confidence: 0.9,   // Apple doesn't surface a scalar confidence; report a stable placeholder
            audioDurationMs: audioMs,
            latencyMs: Int(Date().timeIntervalSince(start) * 1000),
            provider: .appleSpeech,
            model: locale.identifier(.bcp47)
        )
    }

    /// The streaming session for the live pill — native volatile/finalized results, no re-decode cost.
    public func makeStreamingSession() -> (any StreamingTranscriber)? {
        guard isLoaded, let audioFormat else { return nil }
        return AppleStreamingSession(locale: locale, audioFormat: audioFormat)
    }
}

// MARK: - Apple streaming session

/// Feeds captured audio into a live `SpeechAnalyzer` and exposes its finalized text as the pill's
/// confirmed (append-only) stream. Adapts our pull-based `step(samples:)` contract onto Apple's
/// push-based analyzer: each step feeds only the NEW samples since the last call.
@available(macOS 26.0, iOS 26.0, *)
@MainActor
public final class AppleStreamingSession: StreamingTranscriber {
    private let module: Speech.SpeechTranscriber
    private let analyzer: SpeechAnalyzer
    private let converter: AppleAudioConverter
    private var continuation: AsyncStream<AnalyzerInput>.Continuation?
    private var resultsTask: Task<Void, Never>?
    private var startTask: Task<Void, Never>?
    private var started = false
    private var fedSamples = 0

    /// Finalized text so far (append-only) and the latest volatile tail.
    private var confirmed = AttributedString()
    private var volatile = ""

    init(locale: Locale, audioFormat: AVAudioFormat) {
        self.module = Speech.SpeechTranscriber(locale: locale, preset: .progressiveTranscription)
        self.analyzer = SpeechAnalyzer(modules: [module])
        self.converter = AppleAudioConverter(target: audioFormat)
    }

    private func startIfNeeded() {
        guard !started else { return }
        started = true
        let (stream, cont) = AsyncStream<AnalyzerInput>.makeStream()
        continuation = cont
        // Capture `module`/`analyzer` LOCALLY (not `self`) and use `[weak self]` re-checked inside the
        // loop, so neither Task pins `self` across its (indefinite) suspension. Without this the
        // session — and its SpeechAnalyzer — leaks on every dictation (both `module.results` and
        // `analyzer.start` only return once the input is finished, which happens in reset()/finish()).
        let module = self.module
        resultsTask = Task { @MainActor [weak self] in
            do {
                for try await result in module.results {
                    guard let self else { return }
                    if result.isFinal {
                        self.confirmed += result.text
                        self.volatile = ""
                    } else {
                        self.volatile = String(result.text.characters)
                    }
                }
            } catch {
                // A streaming error just freezes the pill at its last text — the pasted output is the
                // batch pass, so this never loses the dictation.
            }
        }
        let analyzer = self.analyzer
        startTask = Task { try? await analyzer.start(inputSequence: stream) }
    }

    public func step(samples: [Float]) async -> StreamingTranscript {
        startIfNeeded()
        // Feed only the new tail (Apple wants incremental audio, not the whole buffer each tick).
        // `min` guards against a shrunk buffer (defensive — the buffer is append-only per recording).
        let start = Swift.min(fedSamples, samples.count)
        guard start < samples.count else { return snapshot() }
        let tail = Array(samples[start...])
        fedSamples = samples.count
        if let buffer = AudioSampleBridge.makeBuffer(samples: tail, sampleRate: RecordingEngine.targetSampleRate),
           let converted = converter.convert(buffer) {
            continuation?.yield(AnalyzerInput(buffer: converted))
        }
        return snapshot()
    }

    public func reset() {
        // Finish the input + stop the analyzer so both internal Tasks complete and the session
        // (and its SpeechAnalyzer) can deallocate. Called by AppState on every teardown.
        continuation?.finish()
        continuation = nil
        resultsTask?.cancel(); resultsTask = nil
        startTask?.cancel(); startTask = nil
        let analyzer = self.analyzer
        Task { await analyzer.cancelAndFinishNow() }
        confirmed = AttributedString()
        volatile = ""
        fedSamples = 0
        started = false
    }

    private func snapshot() -> StreamingTranscript {
        // Return finalized + volatile as the LIVE text in `confirmed`. Apple finalizes in big, late
        // chunks, so finalized-only made the pill sit on "Listening…" for whole sentences; the
        // volatile tail is the real-time edge (what Apple Dictation shows live).
        // NOTE: this means the Apple pill's text is NOT strictly append-only — the volatile tail can
        // revise as Apple refines it. That's intended: Apple's volatile revises incrementally (a
        // word or two at the end), not the jarring whole-window re-type WhisperKit produced, and the
        // pasted output is the batch pass regardless. (Whichever un-finalized volatile remains at
        // stop is simply dropped from the pill — the batch pass owns the real text.)
        let fin = String(confirmed.characters).trimmingCharacters(in: .whitespacesAndNewlines)
        let vol = volatile.trimmingCharacters(in: .whitespacesAndNewlines)
        let live = vol.isEmpty ? fin : (fin.isEmpty ? vol : fin + " " + vol)
        return StreamingTranscript(confirmed: live)
    }
}
