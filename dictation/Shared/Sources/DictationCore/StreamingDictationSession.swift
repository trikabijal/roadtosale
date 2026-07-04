import AVFoundation
import Foundation

/// Result of a streaming dictation: the assembled cleaned text (what gets pasted) plus the
/// concatenated raw STT (kept for telemetry / the learnings dataset, like the batch path).
public struct StreamingResult: Sendable {
    public let cleanedText: String
    public let rawText: String
    public init(cleanedText: String, rawText: String) {
        self.cleanedText = cleanedText
        self.rawText = rawText
    }
}

/// Orchestrates streaming dictation (PRD 0007): transcribe each VAD-delimited segment as it's
/// flushed DURING speech, feed the text through a `SentenceBuffer`, and clean each completed
/// sentence incrementally with rolling context. At stop, only the trailing partial sentence
/// remains to process — so the post-stop wait is ~constant regardless of total length.
///
/// Cleanup uses a fresh (but model-warm) session per sentence with the previous cleaned sentence
/// as `priorContext`, deliberately avoiding the single-accumulating-session latency ramp measured
/// in `dev/streaming-latency-report.md`.
@MainActor
public final class StreamingDictationSession {
    private let transcriber: any SpeechTranscriber
    private let cleanup: any TextCleanup
    private let level: CleanupLevel
    private let vocab: [String: String]
    private let commandGrammar: [String: String]
    private let profile: String

    private var buffer: SentenceBuffer
    private var cleaned: [String] = []
    private var rawParts: [String] = []
    private var priorContext = ""

    public init(
        transcriber: any SpeechTranscriber,
        cleanup: any TextCleanup,
        level: CleanupLevel,
        vocab: [String: String] = [:],
        commandGrammar: [String: String] = [:],
        profile: String = "dictation",
        maxWordsBeforeFlush: Int = 40
    ) {
        self.transcriber = transcriber
        self.cleanup = cleanup
        self.level = level
        self.vocab = vocab
        self.commandGrammar = commandGrammar
        self.profile = profile
        self.buffer = SentenceBuffer(maxWordsBeforeFlush: maxWordsBeforeFlush)
    }

    /// Assembled cleaned text so far (for a live HUD display).
    public var confirmedText: String { cleaned.joined(separator: " ") }
    /// The not-yet-finalized trailing partial (for a live HUD display).
    public var partialText: String { buffer.partial }

    /// Warm the cleanup model so the first per-sentence clean isn't cold.
    public func prewarm() { cleanup.prewarm() }

    /// Transcribe one VAD-delimited segment and clean any sentences it completes. Call each time
    /// the recording engine flushes a segment (on `recordingEngineDidDetectSilence`). Returns the
    /// sentences newly cleaned by this segment (may be empty). Errors are swallowed — a failed
    /// segment must never abort the whole dictation; its audio is still in later segments/the raw.
    @discardableResult
    public func ingest(segment: [AVAudioPCMBuffer], audioStartDate: Date) async -> [String] {
        guard let res = try? await transcriber.transcribe(buffers: segment, audioStartDate: audioStartDate) else {
            return []
        }
        let text = res.text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return [] }
        rawParts.append(text)
        buffer.append(text)
        var emitted: [String] = []
        for sentence in buffer.drainCompleteSentences() {
            emitted.append(await cleanAndAppend(sentence))
        }
        return emitted
    }

    /// Flush the final partial sentence and return the assembled result. Call once at stop.
    public func finish() async -> StreamingResult {
        for sentence in buffer.flushAll() {
            _ = await cleanAndAppend(sentence)
        }
        return StreamingResult(
            cleanedText: cleaned.joined(separator: " ").trimmingCharacters(in: .whitespacesAndNewlines),
            rawText: rawParts.joined(separator: " ").trimmingCharacters(in: .whitespacesAndNewlines)
        )
    }

    // MARK: - Private

    private func cleanAndAppend(_ sentence: String) async -> String {
        guard level != .off else {
            cleaned.append(sentence)
            priorContext = sentence
            return sentence
        }
        let req = CleanupRequest(
            rawText: sentence, level: level, vocab: vocab,
            commandGrammar: commandGrammar, profile: profile, priorContext: priorContext
        )
        let result = await cleanup.clean(req)
        let text = result.cleanedText.isEmpty ? sentence : result.cleanedText
        cleaned.append(text)
        priorContext = text
        return text
    }
}
