import Foundation

/// Deterministic, dependency-free cleanup. Doubles as the cross-platform FALLBACK
/// reference and the `ruleBased` provider. Mirrors the transformations in
/// `voice-engine/src/cleanup/rule-based.ts` so fallback behaviour matches everywhere.
public struct RuleBasedCleanup: TextCleanup {
    let pack: CleanupPack

    public init(pack: CleanupPack) {
        self.pack = pack
    }

    public func clean(_ req: CleanupRequest) async -> CleanupResult {
        let start = Date()
        if req.level == .off {
            return CleanupResult(cleanedText: req.rawText, opsApplied: [], usedFallback: false,
                                 latencyMs: 0, provider: .ruleBased)
        }

        var ops: [String] = []
        var text = req.rawText
        text = CleanupText.applyMap(text, req.commandGrammar, op: "commands", ops: &ops)
        text = CleanupText.removeFillers(text, pack.fillers, ops: &ops)
        if req.level == .full {
            text = CleanupText.collapseRepeats(text, ops: &ops)
        }
        text = CleanupText.normalize(text, ops: &ops)
        text = CleanupText.applyMap(text, req.vocab, op: "vocab", ops: &ops)

        return CleanupResult(
            cleanedText: text.trimmingCharacters(in: .whitespacesAndNewlines),
            opsApplied: ops,
            usedFallback: false,
            latencyMs: Int(Date().timeIntervalSince(start) * 1000),
            provider: .ruleBased
        )
    }
}

// MARK: - Shared text transforms (used by rule-based + Foundation Models post-pass)

enum CleanupText {

    static func regexReplace(_ text: String, pattern: String, template: String) -> String {
        guard let re = try? NSRegularExpression(pattern: pattern, options: [.caseInsensitive]) else {
            return text
        }
        let range = NSRange(text.startIndex..., in: text)
        return re.stringByReplacingMatches(in: text, options: [], range: range, withTemplate: template)
    }

    /// Replace map keys (longest-first, whole-word, case-insensitive) with their values.
    static func applyMap(_ text: String, _ map: [String: String], op: String, ops: inout [String]) -> String {
        guard !map.isEmpty else { return text }
        var out = text
        for key in map.keys.sorted(by: { $0.count > $1.count }) {
            let pattern = "\\b\(NSRegularExpression.escapedPattern(for: key))\\b"
            let template = NSRegularExpression.escapedTemplate(for: map[key] ?? "")
            out = regexReplace(out, pattern: pattern, template: template)
        }
        if out != text { ops.append(op) }
        return out
    }

    static func removeFillers(_ text: String, _ fillers: [String], ops: inout [String]) -> String {
        guard !fillers.isEmpty else { return text }
        let alternation = fillers.map { NSRegularExpression.escapedPattern(for: $0) }.joined(separator: "|")
        let out = regexReplace(text, pattern: "\\b(?:\(alternation))\\b,?", template: "")
        if out != text { ops.append("fillers") }
        return out
    }

    /// "i i think" → "i think"; "the the car" → "the car".
    static func collapseRepeats(_ text: String, ops: inout [String]) -> String {
        let out = regexReplace(text, pattern: "\\b(\\w+)(\\s+\\1\\b)+", template: "$1")
        if out != text { ops.append("repeats") }
        return out
    }

    static func normalize(_ text: String, ops: inout [String]) -> String {
        var out = text
        out = regexReplace(out, pattern: "[ \\t]{2,}", template: " ")
        out = regexReplace(out, pattern: " +([,.!?;:])", template: "$1")
        out = regexReplace(out, pattern: "[ \\t]*\\n[ \\t]*", template: "\n")
        out = out.trimmingCharacters(in: .whitespacesAndNewlines)
        out = capitalizeSentences(out)
        out = regexReplace(out, pattern: "\\bi\\b", template: "I")
        if out != text { ops.append("punctuation") }
        return out
    }

    static func capitalizeSentences(_ text: String) -> String {
        var result = ""
        var capitalizeNext = true
        for ch in text {
            if capitalizeNext, ch.isLetter {
                result.append(contentsOf: ch.uppercased())
                capitalizeNext = false
            } else {
                result.append(ch)
                if ch == "." || ch == "!" || ch == "?" || ch == "\n" {
                    capitalizeNext = true
                }
            }
        }
        return result
    }

    /// Strip a single layer of wrapping quotes/backticks the model may add.
    static func stripWrappingQuotes(_ text: String) -> String {
        let pairs: [(Character, Character)] = [("\"", "\""), ("'", "'"), ("`", "`"), ("“", "”")]
        for (open, close) in pairs where text.count >= 2 && text.first == open && text.last == close {
            return String(text.dropFirst().dropLast()).trimmingCharacters(in: .whitespacesAndNewlines)
        }
        return text
    }
}
