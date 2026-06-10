import Foundation

/// Pure, dependency-free guards that scrub and validate the on-device cleanup model's
/// output before it reaches the clipboard. Extracted out of `FoundationModelsCleanup` so
/// they are unit-testable WITHOUT the `FoundationModels` framework (which is unavailable in
/// CI and on non-Apple-Intelligence builds). `FoundationModelsCleanup` is the only caller.
///
/// Finding F10: the small on-device model sometimes echoes parts of its own prompt — the
/// injected "Known names and terms …" vocabulary block and/or a verbatim copy of the
/// dictated text — back into its output. These would otherwise land on the user's clipboard.
enum CleanupOutputSanitizer {

    /// Fragment that marks an echoed copy of the term-biasing instruction (see F10). Matched
    /// case-insensitively; everything from this point on is dropped.
    private static let instructionEchoMarker = "Known names and terms"

    /// Strip anything the model may wrap around the result so it never reaches the clipboard:
    /// - legacy `<transcript>` tags,
    /// - a leading "Dictated text:"/"Output:"/etc. label,
    /// - an echoed "Known names and terms — …" instruction block (F10): truncate at it.
    static func sanitizeOutput(_ raw: String) -> String {
        var out = raw
        for tag in ["<transcript>", "</transcript>"] {
            out = out.replacingOccurrences(of: tag, with: "", options: [.caseInsensitive])
        }
        out = out.trimmingCharacters(in: .whitespacesAndNewlines)
        for label in ["Dictated text:", "Cleaned text:", "Cleaned:", "Output:"] {
            if out.lowercased().hasPrefix(label.lowercased()) {
                out = String(out.dropFirst(label.count)).trimmingCharacters(in: .whitespacesAndNewlines)
            }
        }
        // If the model echoed the instruction block, drop it and everything after.
        if let r = out.range(of: instructionEchoMarker, options: [.caseInsensitive]) {
            out = String(out[..<r.lowerBound]).trimmingCharacters(in: .whitespacesAndNewlines)
        }
        return out
    }

    /// Guards against the model returning nothing, ballooning, collapsing, or echoing the
    /// input — signs it ignored the instructions or answered/repeated instead of cleaning.
    /// Returns `true` to tell the caller to fall back to deterministic rule-based cleanup.
    static func isDegenerate(output: String, input: String) -> Bool {
        let trimmedOut = output.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmedOut.isEmpty { return true }

        let inWords = input.split(whereSeparator: \.isWhitespace).count
        let outWords = trimmedOut.split(whereSeparator: \.isWhitespace).count
        if inWords >= 4, outWords > inWords * 3 { return true }          // ballooned (likely answered)
        if inWords >= 6, outWords < max(1, inWords / 4) { return true }  // collapsed (likely summarized)

        // Echo/duplication guard: the model returned the input verbatim (near-verbatim, after
        // normalizing case + whitespace) plus a materially longer tail — e.g. it repeated the
        // transcript twice, or appended an echoed prompt block. Conservative: requires a
        // non-trivial input and a clear length blow-up so normal cleanups aren't flagged.
        if inWords >= 3 {
            let normIn = normalized(input)
            let normOut = normalized(trimmedOut)
            if !normIn.isEmpty,
               normOut.contains(normIn),
               outWords > Int(Double(inWords) * 1.5) {
                return true
            }
        }
        return false
    }

    /// Lowercase + collapse runs of whitespace to one space, trimmed. Lets a near-verbatim
    /// echo (minor punctuation/casing drift) still match in the duplication check.
    private static func normalized(_ s: String) -> String {
        let lowered = s.lowercased()
        let collapsed = lowered.split(whereSeparator: \.isWhitespace).joined(separator: " ")
        return collapsed
    }
}
