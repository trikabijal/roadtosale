import Foundation

/// Accumulates streamed transcription fragments and emits **completed sentences** as they form,
/// holding back the trailing partial sentence. Used by `StreamingDictationSession` so cleanup can
/// run per-sentence during speech (see PRD 0007). Pure value type — no I/O, fully unit-testable.
///
/// Sentence boundary = `.`, `!`, or `?` followed by whitespace or end-of-text. A runaway partial
/// (a user talking for a long time without a boundary) is force-emitted once it exceeds
/// `maxWordsBeforeFlush`, so cleanup never stalls waiting for a period that isn't coming.
public struct SentenceBuffer {
    private var pending = ""
    /// Force-emit the partial once it reaches this many words without a terminator.
    public var maxWordsBeforeFlush: Int

    public init(maxWordsBeforeFlush: Int = 40) {
        self.maxWordsBeforeFlush = maxWordsBeforeFlush
    }

    /// The current not-yet-emitted partial (for HUD display / tests).
    public var partial: String { pending }

    /// Add a transcribed fragment, inserting a space if needed so words don't run together.
    public mutating func append(_ fragment: String) {
        let frag = fragment.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !frag.isEmpty else { return }
        if pending.isEmpty {
            pending = frag
        } else {
            pending += " " + frag
        }
    }

    /// Emit every complete sentence now available (in order), keeping the trailing partial.
    /// Also force-emits the partial if it has grown past `maxWordsBeforeFlush` (runaway guard).
    public mutating func drainCompleteSentences() -> [String] {
        var out: [String] = []
        while let cut = firstSentenceCut(in: pending) {
            let sentence = String(pending[..<cut]).trimmingCharacters(in: .whitespacesAndNewlines)
            if !sentence.isEmpty { out.append(sentence) }
            pending = String(pending[cut...]).trimmingCharacters(in: .whitespacesAndNewlines)
        }
        if wordCount(pending) >= maxWordsBeforeFlush {
            let s = pending.trimmingCharacters(in: .whitespacesAndNewlines)
            if !s.isEmpty { out.append(s) }
            pending = ""
        }
        return out
    }

    /// Emit whatever remains (called once at stop). Returns the final partial as a single "sentence"
    /// (or nothing if empty), and clears the buffer.
    public mutating func flushAll() -> [String] {
        let s = pending.trimmingCharacters(in: .whitespacesAndNewlines)
        pending = ""
        return s.isEmpty ? [] : [s]
    }

    // MARK: - Private

    /// Index just PAST a sentence-terminating `.`/`!`/`?` (so the terminator stays with the
    /// sentence), where the terminator is followed by whitespace or end-of-text. Returns nil if
    /// the pending text has no complete sentence yet.
    private func firstSentenceCut(in s: String) -> String.Index? {
        var i = s.startIndex
        while i < s.endIndex {
            let c = s[i]
            if c == "." || c == "!" || c == "?" {
                let next = s.index(after: i)
                if next == s.endIndex || s[next].isWhitespace {
                    return next
                }
            }
            i = s.index(after: i)
        }
        return nil
    }

    private func wordCount(_ s: String) -> Int {
        s.split(whereSeparator: { $0.isWhitespace }).count
    }
}
