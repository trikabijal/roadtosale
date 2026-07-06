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
        XCTAssertEqual(pack.lexicon, fb.lexicon)   // dictation pack carries no lexicon
    }

    // road-to-sale (#8): the dealership lexicon loads…
    func testRoadToSalePackLexiconLoads() {
        let pack = CleanupPackLoader.load(profile: "road-to-sale")
        XCTAssertEqual(pack.profile, "road-to-sale")
        XCTAssertFalse(pack.lexicon.terms.isEmpty)
        XCTAssertEqual(pack.lexicon.expansions["f and i"], "F&I")
    }

    // …and actually applies in cleanup (expands spoken acronyms + hyphenates terms).
    @MainActor
    func testRoadToSaleLexiconAppliesInCleanup() async {
        let pack = CleanupPackLoader.load(profile: "road-to-sale")
        let engine = RuleBasedCleanup(pack: pack)
        let r = await engine.clean(CleanupRequest(
            rawText: "we agreed on the f and i and the a p r and the trade in",
            level: .full))
        XCTAssertTrue(r.cleanedText.contains("F&I"), r.cleanedText)
        XCTAssertTrue(r.cleanedText.contains("APR"), r.cleanedText)
        XCTAssertTrue(r.cleanedText.contains("trade-in"), r.cleanedText)
    }

    // catalog-derived vocab (#7): a tenant's make terms merge into the glossary and apply.
    @MainActor
    func testCatalogTermsMergeIntoLexicon() async {
        let base = CleanupPackLoader.load(profile: "road-to-sale")
        // Terms as the derive_vocab.py step would produce for a Honda dealer.
        let pack = base.mergingLexiconTerms(["EX-L", "CR-V Hybrid AWD", "TrailSport"])
        XCTAssertTrue(pack.lexicon.terms.contains("EX-L"))
        let engine = RuleBasedCleanup(pack: pack)
        let r = await engine.clean(CleanupRequest(
            rawText: "they want the ex-l trim and we have a trailsport in stock", level: .full))
        XCTAssertTrue(r.cleanedText.contains("EX-L"), r.cleanedText)
        XCTAssertTrue(r.cleanedText.contains("TrailSport"), r.cleanedText)
    }
}

// F10: the on-device cleanup model echoed parts of its own prompt (the "Known names and
// terms …" block, or a verbatim copy of the dictated text) into its output, which landed on
// the clipboard. These exercise the pure guards extracted into CleanupOutputSanitizer — no
// FoundationModels framework needed, so they run in CI.
final class CleanupOutputSanitizerTests: XCTestCase {

    // MARK: sanitizeOutput

    func testStripsEchoedKnownNamesAndTermsBlock() {
        let raw = "Let's sync tomorrow with Bijal and Teena.\n\n"
            + "Known names and terms — if you hear something close to one of these, "
            + "use this exact spelling: Bijal, Teena."
        XCTAssertEqual(CleanupOutputSanitizer.sanitizeOutput(raw),
                       "Let's sync tomorrow with Bijal and Teena.")
    }

    func testStripsEchoedTermsBlockCaseInsensitively() {
        let raw = "The car is fast. known NAMES and terms: Honda, EX-L."
        XCTAssertEqual(CleanupOutputSanitizer.sanitizeOutput(raw), "The car is fast.")
    }

    // Regression: legacy <transcript> tags + leading label stripping still work.
    func testStripsLegacyTranscriptTags() {
        XCTAssertEqual(
            CleanupOutputSanitizer.sanitizeOutput("<transcript>Ship it Monday.</transcript>"),
            "Ship it Monday.")
    }

    func testStripsLeadingDictatedTextLabel() {
        XCTAssertEqual(
            CleanupOutputSanitizer.sanitizeOutput("Dictated text: Ship it Monday."),
            "Ship it Monday.")
    }

    func testLeavesCleanOutputUntouched() {
        XCTAssertEqual(
            CleanupOutputSanitizer.sanitizeOutput("I think the setup is working fine."),
            "I think the setup is working fine.")
    }

    // MARK: content-drop guard (cleanup deleted a meaningful clause)

    /// The real bug: Apple STT captured it, cleanup deleted the leading clause "So the best data
    /// would be". The guard must flag this so cleanup falls back instead of pasting truncated text.
    func testDegenerateWhenCleanupDropsLeadingClause() {
        let raw = "So the best data would be Apple's own model, publishing it somewhere and whisper its own model is publishing it somewhere."
        let cleaned = "Apple's own model, publishing it somewhere and whispering its own model is publishing it somewhere."
        XCTAssertTrue(CleanupOutputSanitizer.droppedLeadingOrTrailingContent(output: cleaned, input: raw))
        XCTAssertTrue(CleanupOutputSanitizer.isDegenerate(output: cleaned, input: raw))
    }

    func testDegenerateWhenCleanupDropsTrailingClause() {
        let raw = "We should ship the dictation feature on Monday and tell the whole team about it."
        let cleaned = "We should ship the dictation feature on Monday."   // dropped the trailing clause
        XCTAssertTrue(CleanupOutputSanitizer.droppedLeadingOrTrailingContent(output: cleaned, input: raw))
    }

    /// Legitimate filler/repeat trimming must NOT trip the guard — the opening content words survive.
    func testContentGuardIgnoresNormalFillerTrim() {
        let raw = "um so like the the best data would actually be really useful for us here"
        let cleaned = "The best data would be really useful for us here."
        XCTAssertFalse(CleanupOutputSanitizer.droppedLeadingOrTrailingContent(output: cleaned, input: raw))
        XCTAssertFalse(CleanupOutputSanitizer.isDegenerate(output: cleaned, input: raw))
    }

    /// Short inputs aren't judged (too little to tell a drop from a rewrite).
    func testContentGuardSkipsShortInput() {
        XCTAssertFalse(CleanupOutputSanitizer.droppedLeadingOrTrailingContent(
            output: "Ship it.", input: "So we should ship it."))
    }

    // MARK: isDegenerate

    func testDegenerateWhenOutputIsInputRepeatedTwice() {
        let input = "let's ship the dictation feature on monday"
        let output = input + " " + input
        XCTAssertTrue(CleanupOutputSanitizer.isDegenerate(output: output, input: input))
    }

    func testDegenerateWhenOutputContainsInputPlusLargeAppendedBlock() {
        let input = "let's ship the dictation feature on monday"
        let output = "Let's ship the dictation feature on Monday. "
            + "Here is some extra commentary the model invented and kept on adding well past "
            + "the original transcript length to pad the response considerably."
        XCTAssertTrue(CleanupOutputSanitizer.isDegenerate(output: output, input: input))
    }

    func testNotDegenerateForNormalSameLengthCleanup() {
        let input = "um so i think we should uh ship it monday"
        let output = "I think we should ship it Monday."
        XCTAssertFalse(CleanupOutputSanitizer.isDegenerate(output: output, input: input))
    }

    func testNotDegenerateForLegitimatelyShorterCleanup() {
        // Shorter output that does NOT contain the raw input verbatim — a real summary-ish
        // tidy, not an echo. Must not be flagged.
        let input = "um so like you know i was thinking that maybe we could possibly ship it"
        let output = "I was thinking we could ship it."
        XCTAssertFalse(CleanupOutputSanitizer.isDegenerate(output: output, input: input))
    }

    func testNotDegenerateForTrivialShortInput() {
        // Guard against flagging trivial inputs (< 3 words) even on a near-echo.
        let input = "hello there"
        let output = "hello there"
        XCTAssertFalse(CleanupOutputSanitizer.isDegenerate(output: output, input: input))
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
