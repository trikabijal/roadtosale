import XCTest
@testable import DictationCore

final class RuleBasedCleanupTests: XCTestCase {

    private func clean(_ text: String, level: CleanupLevel = .full,
                       vocab: [String: String] = [:],
                       grammar: [String: String] = [:]) async -> CleanupResult {
        let engine = RuleBasedCleanup(pack: .fallback)
        return await engine.clean(CleanupRequest(rawText: text, level: level,
                                                 vocab: vocab, commandGrammar: grammar))
    }

    func testOffPassesThrough() async {
        let r = await clean("um so like hello", level: .off)
        XCTAssertEqual(r.cleanedText, "um so like hello")
        XCTAssertTrue(r.opsApplied.isEmpty)
        XCTAssertFalse(r.usedFallback)
    }

    func testRemovesFillersAndCapitalizes() async {
        let r = await clean("um i think uh we should ship")
        XCTAssertEqual(r.cleanedText, "I think we should ship")
        XCTAssertTrue(r.opsApplied.contains("fillers"))
    }

    func testObeysNewParagraphCommand() async {
        let r = await clean("ship it monday new paragraph lets sync tomorrow",
                            grammar: ["new paragraph": "\n\n"])
        XCTAssertTrue(r.cleanedText.contains("\n\n"))
        XCTAssertTrue(r.opsApplied.contains("commands"))
    }

    func testVocabForcesSpellingAfterCleanup() async {
        let r = await clean("call sarah mitchell", vocab: ["sarah mitchell": "Sarah Mitchell"])
        XCTAssertTrue(r.cleanedText.contains("Sarah Mitchell"))
        XCTAssertTrue(r.opsApplied.contains("vocab"))
    }

    func testCollapsesRepeatedWordsAtFull() async {
        let r = await clean("the the car is is fast")
        XCTAssertEqual(r.cleanedText, "The car is fast")
    }

    // Repeat-collapsing is full-only; light must preserve repeats.
    func testLightDoesNotCollapseRepeats() async {
        let r = await clean("the the car", level: .light)
        XCTAssertEqual(r.cleanedText, "The the car")
        XCTAssertFalse(r.opsApplied.contains("repeats"))
    }

    // Regression: capitalizeSentences must NOT capitalize after a period that isn't a
    // sentence break (decimals, abbreviations). This was a real bug.
    // A period NOT followed by whitespace (decimals, version numbers) must not trigger
    // capitalization of the following word — the core regression this fix addresses.
    func testDoesNotMangleDecimals() {
        XCTAssertEqual(CleanupText.capitalizeSentences("it costs 3.5 dollars today"),
                       "It costs 3.5 dollars today")
        XCTAssertEqual(CleanupText.capitalizeSentences("ship version 3.2 now"),
                       "Ship version 3.2 now")
    }

    func testCapitalizesAfterRealSentenceBreak() {
        XCTAssertEqual(CleanupText.capitalizeSentences("hello world. another one"),
                       "Hello world. Another one")
    }

    // Documents the rule-based limitation: the PRD canonical example is the LLM's job;
    // rule-based produces a strict-subset cleanup (keeps "like"/"ok", no terminal period).
    func testCanonicalExampleRuleBasedSubset() async {
        let r = await clean(
            "um so like i think we should uh ship it monday new paragraph lets sync tomorrow ok",
            grammar: ["new paragraph": "\n\n"])
        XCTAssertTrue(r.cleanedText.contains("\n\n"), "obeys new paragraph")
        XCTAssertTrue(r.cleanedText.lowercased().contains("ship it monday"))
        XCTAssertTrue(r.cleanedText.contains("like"), "rule-based keeps 'like' (LLM removes it)")
    }
}

final class HallucinationFilterTests: XCTestCase {

    func testJunkPhraseDroppedOnShortClip() {
        XCTAssertTrue(WhisperKitTranscriber.isLikelyHallucination(
            text: "Thank you.", confidence: 0.9, durationMs: 600))
    }

    func testJunkPhraseDroppedOnLowConfidence() {
        XCTAssertTrue(WhisperKitTranscriber.isLikelyHallucination(
            text: "thanks for watching", confidence: 0.3, durationMs: 3000))
    }

    func testRealSpeechKept() {
        XCTAssertFalse(WhisperKitTranscriber.isLikelyHallucination(
            text: "let's ship the dictation feature on monday", confidence: 0.8, durationMs: 4000))
    }

    func testEmptyIsHallucination() {
        XCTAssertTrue(WhisperKitTranscriber.isLikelyHallucination(
            text: "   ", confidence: 0.9, durationMs: 2000))
    }
}

final class CleanupPackTests: XCTestCase {

    func testBundledPackResourceIsPresent() {
        // Prove the JSON is actually bundled (not silently falling back to .fallback,
        // which is byte-identical and would mask a missing resource).
        XCTAssertNotNil(CleanupPackLoader.resourceURL(),
                        "cleanup pack resource missing from DictationCore bundle")
    }

    func testBundledPackLoads() {
        let pack = CleanupPackLoader.load()
        XCTAssertEqual(pack.profile, "dictation")
        XCTAssertNotNil(pack.prompts["full"])
        XCTAssertNotNil(pack.prompts["light"])
        XCTAssertEqual(pack.commandGrammar["new paragraph"], "\n\n")
    }

    // Drift guard: the bundled JSON pack and the Swift fallback must stay in sync.
    func testBundledPackMatchesFallback() {
        let pack = CleanupPackLoader.load()
        let fb = CleanupPack.fallback
        XCTAssertEqual(pack.profile, fb.profile)
        XCTAssertEqual(pack.minWordsForCleanup, fb.minWordsForCleanup)
        XCTAssertEqual(pack.commandGrammar, fb.commandGrammar)
        XCTAssertEqual(Set(pack.fillers), Set(fb.fillers))
        XCTAssertEqual(Set(pack.junkPhrases), Set(fb.junkPhrases))
        XCTAssertEqual(pack.prompts, fb.prompts)
    }
}

final class TextCleanupFactoryTests: XCTestCase {

    @MainActor
    func testRuleBasedProviderCleans() async {
        let engine = TextCleanupFactory.make(
            CleanupConfig(provider: .ruleBased, level: .full), pack: .fallback)
        let r = await engine.clean(CleanupRequest(rawText: "um hello there", level: .full))
        XCTAssertEqual(r.provider, .ruleBased)
        XCTAssertFalse(r.cleanedText.contains("um"))
    }
}
