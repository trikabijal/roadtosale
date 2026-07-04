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
public final class AppleSpeechTranscriber: DictationCore.SpeechTranscriber {
    public private(set) var isLoaded = false
    private let locale: Locale
    private var audioFormat: AVAudioFormat?

    /// `model` from STTConfig is a BCP-47 locale id (e.g. "en-US"); default to the user's locale.
    public init(localeIdentifier: String = "") {
        if localeIdentifier.isEmpty {
            self.locale = Locale(identifier: "en-US")
        } else {
            self.locale = Locale(identifier: localeIdentifier)
        }
    }

    public func load(onProgress: (@MainActor (Double) -> Void)? = nil) async throws {
        isLoaded = false
        // Request Speech authorization (on-device SpeechAnalyzer still checks it). Best-effort — if
        // denied, the analyzer will throw and the caller falls back to WhisperKit.
        _ = await withCheckedContinuation { (c: CheckedContinuation<SFSpeechRecognizerAuthorizationStatus, Never>) in
            SFSpeechRecognizer.requestAuthorization { c.resume(returning: $0) }
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
        resultsTask = Task { @MainActor [weak self] in
            guard let self else { return }
            do {
                for try await result in self.module.results {
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
        Task { try? await analyzer.start(inputSequence: stream) }
    }

    public func step(samples: [Float]) async -> StreamingTranscript {
        startIfNeeded()
        // Feed only the new tail (Apple wants incremental audio, not the whole buffer each tick).
        guard samples.count > fedSamples else { return snapshot() }
        let tail = Array(samples[fedSamples...])
        fedSamples = samples.count
        if let buffer = AudioSampleBridge.makeBuffer(samples: tail, sampleRate: RecordingEngine.targetSampleRate),
           let converted = converter.convert(buffer) {
            continuation?.yield(AnalyzerInput(buffer: converted))
        }
        return snapshot()
    }

    public func finish(samples: [Float]?) async -> StreamingTranscript {
        if let samples { _ = await step(samples: samples) }
        continuation?.finish()
        try? await analyzer.finalizeAndFinishThroughEndOfInput()
        _ = await resultsTask?.value
        volatile = ""
        return snapshot()
    }

    public func reset() {
        resultsTask?.cancel()
        continuation?.finish()
        confirmed = AttributedString()
        volatile = ""
        fedSamples = 0
        started = false
    }

    public func onLivePartial(_ handler: (@MainActor @Sendable (StreamingTranscript) -> Void)?) {}

    private func snapshot() -> StreamingTranscript {
        // Show finalized + volatile as the LIVE text. Apple finalizes in big, late chunks, so
        // finalized-only made the pill sit on "Listening…" for whole sentences. Apple's volatile is
        // the real-time edge (designed for live captions) and refines incrementally — not the
        // whole-window re-decode that made WhisperKit chatter — so it's safe to show live.
        let fin = String(confirmed.characters).trimmingCharacters(in: .whitespacesAndNewlines)
        let vol = volatile.trimmingCharacters(in: .whitespacesAndNewlines)
        let live = vol.isEmpty ? fin : (fin.isEmpty ? vol : fin + " " + vol)
        return StreamingTranscript(confirmed: live, hypothesis: "", confidence: 0.9)
    }
}
