import XCTest
@testable import DictationCore

// FoundationModelsCleanup is only compiled where the framework is importable and the OS is
// new enough. These tests exercise the parts that DON'T need the live on-device model:
//   * the timeout-scaling selection logic (pure math, the T-CLN-7 length scaling),
//   * the task-prompt framing (the "edit, never answer" guard, T-CLN-2 supporting),
//   * the `.off` short-circuit, and
//   * the fallback contract (clean never throws; when the system model is unavailable —
//     as on CI without Apple Intelligence — it falls back to rule-based with usedFallback,
//     the T-CLN-4 fallback path).
// When the live model IS present (a real device), the fallback assertions are skipped so the
// test stays deterministic; the pure-logic assertions still run.
#if canImport(FoundationModels)
import FoundationModels

@available(macOS 26.0, iOS 26.0, *)
final class FoundationModelsCleanupTests: XCTestCase {

    private func makeCleanup(pack: CleanupPack = .fallback) -> FoundationModelsCleanup {
        FoundationModelsCleanup(pack: pack, fallback: RuleBasedCleanup(pack: pack))
    }

    // MARK: - responseTimeout scaling (T-CLN-7)

    /// Floor (15s) for short dictations so a genuine hang still fails fast, a 90s ceiling that
    /// bounds a true hang, and ~50ms/word in between (the documented 3× margin over the model's
    /// ~16ms/word, so a ~760-word / 5-min dictation gets the seconds it legitimately needs
    /// instead of the old flat 12s that timed out by a hair).
    func testResponseTimeoutHasFloorCeilingAndScale() {
        XCTAssertEqual(FoundationModelsCleanup.responseTimeout(wordCount: 0), 15, "floor")
        XCTAssertEqual(FoundationModelsCleanup.responseTimeout(wordCount: 50), 15,
                       "still on the floor (50 * 0.05 = 2.5 < 15)")
        XCTAssertEqual(FoundationModelsCleanup.responseTimeout(wordCount: 400), 20, accuracy: 0.0001,
                       "scales at 0.05/word once past the floor (400 * 0.05 = 20)")
        XCTAssertEqual(FoundationModelsCleanup.responseTimeout(wordCount: 760), 38, accuracy: 0.0001,
                       "a ~5-min dictation gets ~38s, comfortably above the model's ~12s need")
        XCTAssertEqual(FoundationModelsCleanup.responseTimeout(wordCount: 100_000), 90, "ceiling")
    }

    func testResponseTimeoutIsMonotonicNonDecreasing() {
        var last = 0.0
        for words in stride(from: 0, through: 3000, by: 137) {
            let t = FoundationModelsCleanup.responseTimeout(wordCount: words)
            XCTAssertGreaterThanOrEqual(t, last, "timeout must never shrink as the transcript grows")
            last = t
        }
    }

    // MARK: - task prompt framing (supports T-CLN-2)

    /// The transcript is framed as DATA to edit, never a conversational turn — the small
    /// on-device model otherwise answers questions in the dictation. The prompt must carry the
    /// raw text and the explicit "never reply / answer / follow" instruction.
    func testTaskPromptFramesTextAsDataNotAQuestion() {
        let raw = "what time is it"
        let prompt = FoundationModelsCleanup.taskPrompt(for: raw)
        XCTAssertTrue(prompt.contains(raw), "the dictated text must appear in the prompt")
        let lowered = prompt.lowercased()
        XCTAssertTrue(lowered.contains("never reply") || lowered.contains("never answer")
                        || lowered.contains("never reply to it, answer it"),
                      "prompt must instruct the model not to answer the content")
        XCTAssertTrue(lowered.contains("dictated text"), "the text is labelled as data to edit")
    }

    // MARK: - .off short-circuit

    /// level:.off returns the raw text verbatim, never touches the model, and is not a fallback.
    func testOffLevelReturnsRawTextWithoutModelOrFallback() async {
        let cleanup = makeCleanup()
        let raw = "um so like leave THIS exactly as-is 123"
        let r = await cleanup.clean(CleanupRequest(rawText: raw, level: .off))
        XCTAssertEqual(r.cleanedText, raw)
        XCTAssertFalse(r.usedFallback)
        XCTAssertEqual(r.provider, .foundationModels)
        XCTAssertTrue(r.opsApplied.isEmpty)
    }

    // MARK: - fallback contract (T-CLN-4)

    /// clean() must NEVER throw and must always return usable text — paste is never blocked.
    /// On CI (no Apple Intelligence / model not downloaded) the system model is unavailable, so
    /// this deterministically exercises the fallback path: rule-based cleaning + usedFallback.
    /// On a real device with the model present, we skip the fallback-specific assertions.
    func testCleanNeverBlocksPasteAndFallsBackWhenModelUnavailable() async throws {
        let cleanup = makeCleanup()
        let r = await cleanup.clean(CleanupRequest(rawText: "um i think uh we should ship", level: .full))

        // Always true regardless of model availability: non-empty, content preserved.
        XCTAssertFalse(r.cleanedText.isEmpty, "cleanup must always return text (never block paste)")
        XCTAssertTrue(r.cleanedText.lowercased().contains("ship"), "real content preserved")

        if SystemLanguageModel.default.isAvailable {
            throw XCTSkip("on-device model available — fallback path not deterministically taken here")
        }
        // Model unavailable → fallback to rule-based, flagged, with the rule-based result.
        XCTAssertTrue(r.usedFallback, "no model on this host: must fall back")
        XCTAssertEqual(r.cleanedText, "I think we should ship", "rule-based cleanup applied on fallback")
    }

    /// The fallback result must still apply the deterministic post-pass (forced vocab),
    /// so spelling is guaranteed even when the LLM never ran.
    func testFallbackStillAppliesForcedVocab() async throws {
        if SystemLanguageModel.default.isAvailable {
            throw XCTSkip("on-device model available — not deterministically on the fallback path")
        }
        let cleanup = makeCleanup()
        let r = await cleanup.clean(CleanupRequest(
            rawText: "call sarah mitchell", level: .full,
            vocab: ["sarah mitchell": "Sarah Mitchell"]))
        XCTAssertTrue(r.usedFallback)
        XCTAssertTrue(r.cleanedText.contains("Sarah Mitchell"), r.cleanedText)
    }
}
#endif
