import Foundation

#if canImport(FoundationModels)
import FoundationModels

/// Cleanup powered by Apple's on-device Foundation Models. Nothing leaves the device.
///
/// Robustness: if the system model is unavailable (Apple Intelligence off, model not
/// downloaded, ineligible device), if the request throws, or if the output looks
/// degenerate, it transparently falls back to `RuleBasedCleanup` and flags
/// `usedFallback`. Cleanup never throws to the caller — paste must never be blocked.
@available(macOS 26.0, iOS 26.0, *)
public struct FoundationModelsCleanup: TextCleanup {
    let pack: CleanupPack
    let fallback: RuleBasedCleanup

    public init(pack: CleanupPack, fallback: RuleBasedCleanup) {
        self.pack = pack
        self.fallback = fallback
    }

    public func clean(_ req: CleanupRequest) async -> CleanupResult {
        if req.level == .off {
            return CleanupResult(cleanedText: req.rawText, opsApplied: [], usedFallback: false,
                                 latencyMs: 0, provider: .foundationModels)
        }

        // Model must be ready; otherwise fall back.
        guard SystemLanguageModel.default.isAvailable,
              let instructions = pack.prompts[req.level.rawValue]
        else {
            return await fallbackResult(req)
        }

        let start = Date()
        do {
            let session = LanguageModelSession(instructions: instructions)
            let response = try await session.respond(to: req.rawText)
            var text = CleanupText.stripWrappingQuotes(
                response.content.trimmingCharacters(in: .whitespacesAndNewlines)
            )

            if isDegenerate(output: text, input: req.rawText) {
                return await fallbackResult(req)
            }

            // Deterministic post-pass: guarantee spoken commands + forced vocab spellings.
            var ops = ["llm"]
            text = CleanupText.applyMap(text, req.commandGrammar, op: "commands", ops: &ops)
            text = CleanupText.applyMap(text, req.vocab, op: "vocab", ops: &ops)

            return CleanupResult(
                cleanedText: text.trimmingCharacters(in: .whitespacesAndNewlines),
                opsApplied: ops,
                usedFallback: false,
                latencyMs: Int(Date().timeIntervalSince(start) * 1000),
                provider: .foundationModels
            )
        } catch {
            return await fallbackResult(req)
        }
    }

    // MARK: - Private

    private func fallbackResult(_ req: CleanupRequest) async -> CleanupResult {
        var result = await fallback.clean(req)
        result.usedFallback = true
        return result
    }

    /// Guards against the model returning nothing, ballooning, or collapsing the text —
    /// signs it ignored the instructions or answered instead of cleaning.
    private func isDegenerate(output: String, input: String) -> Bool {
        if output.isEmpty { return true }
        let inWords = input.split(whereSeparator: \.isWhitespace).count
        let outWords = output.split(whereSeparator: \.isWhitespace).count
        if inWords >= 4, outWords > inWords * 3 { return true }          // ballooned (likely answered)
        if inWords >= 6, outWords < max(1, inWords / 4) { return true }  // collapsed (likely summarized)
        return false
    }
}
#endif
