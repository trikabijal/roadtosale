import AVFoundation
import Foundation

/// Result of a streaming dictation: the assembled cleaned text (what gets pasted), the concatenated
/// raw STT (kept for telemetry / the learnings dataset), and enough metadata for a History record.
public struct StreamingResult: Sendable {
    public let cleanedText: String
    public let rawText: String
    /// A segment's transcription THREW mid-recording (a transient failure — not silence), so the
    /// assembled text is missing that slice. When true the caller must re-transcribe the full audio
    /// (via the batch path) instead of pasting a silently-truncated result. This is the guard against
    /// the "middle dropped" failure class: disjoint segments mean a lost segment is gone from the raw.
    public let incomplete: Bool
    /// Summed per-segment STT latency (telemetry parity with the batch path).
    public let latencyMs: Int
    /// Lowest per-segment confidence seen (conservative), for telemetry.
    public let confidence: Double

    public init(cleanedText: String, rawText: String, incomplete: Bool = false,
                latencyMs: Int = 0, confidence: Double = 0) {
        self.cleanedText = cleanedText
        self.rawText = rawText
        self.incomplete = incomplete
        self.latencyMs = latencyMs
        self.confidence = confidence
    }
}

/// Orchestrates streaming dictation (PRD 0007): transcribe each VAD-delimited segment as it's
/// flushed DURING speech (so the live pill grows and the post-stop STT wait is ~constant), then
/// run a SINGLE cleanup pass over the whole transcript at stop.
///
/// Cleanup is deliberately NOT incremental. Per-sentence cleanup with a rolling context caused the
/// small on-device model to echo the previous sentence, snowballing into repeated sentences (see
/// `qc/bugs/streaming/repeated-sentence.md`). One pass over the full raw text — the same path the
/// batch dictation flow uses — has no rolling context, so there is nothing to compound.
///
/// Segment failures are tracked, not swallowed: a segment whose transcription THROWS leaves a hole
/// in the disjoint raw, so `finish()` reports `incomplete` and the caller re-transcribes the full
/// audio rather than pasting a truncated result.
@MainActor
public final class StreamingDictationSession {
    private let transcriber: any SpeechTranscriber
    private let cleanup: any TextCleanup
    private let level: CleanupLevel
    private let vocab: [String: String]
    private let commandGrammar: [String: String]
    private let profile: String
    /// Cleanup is skipped below this word count (parity with the batch path's `minWordsForCleanup`).
    private let minWordsForCleanup: Int

    /// Raw STT text from each ingested segment, in order — the single source for both the live HUD
    /// pill (joined) and the final cleanup input.
    private var rawParts: [String] = []
    /// Set when a segment's transcription throws (transient failure, not silence) — the raw is then
    /// missing that slice, so the assembled result is `incomplete`.
    private var hadSegmentFailure = false
    private var totalLatencyMs = 0
    private var minConfidence: Double = 1

    public init(
        transcriber: any SpeechTranscriber,
        cleanup: any TextCleanup,
        level: CleanupLevel,
        vocab: [String: String] = [:],
        commandGrammar: [String: String] = [:],
        profile: String = "dictation",
        minWordsForCleanup: Int = 0
    ) {
        self.transcriber = transcriber
        self.cleanup = cleanup
        self.level = level
        self.vocab = vocab
        self.commandGrammar = commandGrammar
        self.profile = profile
        self.minWordsForCleanup = minWordsForCleanup
    }

    /// Raw transcript assembled so far — the live HUD pill source. Cleanup runs only at `finish`.
    public var confirmedText: String { rawParts.joined(separator: " ") }

    /// Warm the cleanup model so the single final clean isn't cold.
    public func prewarm() { cleanup.prewarm() }

    /// Transcribe one VAD-delimited segment and append its raw text. Call each time the recording
    /// engine flushes a segment. No cleanup happens here.
    ///
    /// A legitimately-empty/silent segment is a no-op. A segment whose transcription THROWS is
    /// recorded as a failure (`hadSegmentFailure`) — its audio is NOT recoverable from later
    /// segments (they are disjoint slices), so the whole dictation is marked incomplete at finish.
    @discardableResult
    public func ingest(segment: [AVAudioPCMBuffer], audioStartDate: Date) async -> String {
        do {
            let res = try await transcriber.transcribe(buffers: segment, audioStartDate: audioStartDate)
            totalLatencyMs += res.latencyMs
            minConfidence = Swift.min(minConfidence, res.confidence)
            let text = res.text.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !text.isEmpty else { return "" }
            rawParts.append(text)
            return text
        } catch TranscriptionError.emptyResult, TranscriptionError.noAudioData {
            return ""   // legitimate silence — not a failure
        } catch {
            hadSegmentFailure = true   // real transient failure — this slice is lost from the raw
            return ""
        }
    }

    /// Assemble the full raw transcript and run ONE cleanup pass over it. Call once at stop.
    public func finish() async -> StreamingResult {
        let rawText = rawParts.joined(separator: " ").trimmingCharacters(in: .whitespacesAndNewlines)
        let confidence = rawParts.isEmpty ? 0 : minConfidence
        guard !rawText.isEmpty else {
            return StreamingResult(cleanedText: "", rawText: "", incomplete: hadSegmentFailure,
                                   latencyMs: totalLatencyMs, confidence: 0)
        }
        // Skip cleanup for very short clips (parity with the batch path) or when level is off.
        let wordCount = rawText.split(whereSeparator: { $0.isWhitespace }).count
        guard level.shouldClean(wordCount: wordCount, minWords: minWordsForCleanup) else {
            return StreamingResult(cleanedText: rawText, rawText: rawText, incomplete: hadSegmentFailure,
                                   latencyMs: totalLatencyMs, confidence: confidence)
        }
        // Single pass over the whole transcript — no rolling context (the repeat-bug source).
        let req = CleanupRequest(rawText: rawText, level: level, vocab: vocab,
                                 commandGrammar: commandGrammar, profile: profile)
        let result = await cleanup.clean(req)
        let cleaned = result.cleanedText.isEmpty ? rawText : result.cleanedText
        return StreamingResult(cleanedText: cleaned, rawText: rawText, incomplete: hadSegmentFailure,
                               latencyMs: totalLatencyMs, confidence: confidence)
    }
}
