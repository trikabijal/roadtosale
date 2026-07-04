import XCTest
@testable import DictationCore

/// Tests for the pure LocalAgreement-2 logic (PRD 0008). No model — synthetic segments only.
final class StreamingAgreementTests: XCTestCase {

    private func seg(_ text: String, _ start: Double, _ end: Double) -> AgreedSegment {
        AgreedSegment(text: text, start: start, end: end)
    }

    /// Fewer segments than the unconfirmed window → nothing confirmed yet, all hypothesis.
    func testHoldsEverythingAsHypothesisBelowWindow() {
        var a = StreamingAgreement(requiredUnconfirmed: 2)
        a.integrate([seg("the", 0, 0.5), seg("quick", 0.5, 1.0)])
        XCTAssertEqual(a.confirmedText, "")
        XCTAssertEqual(a.hypothesisText, "the quick")
        XCTAssertEqual(a.lastConfirmedEnd, 0)
    }

    /// Beyond the window, the leading segments confirm and the trailing `requiredUnconfirmed` stay hypothesis.
    func testConfirmsAllButTrailingWindow() {
        var a = StreamingAgreement(requiredUnconfirmed: 2)
        a.integrate([seg("the", 0, 0.5), seg("quick", 0.5, 1.0),
                     seg("brown", 1.0, 1.5), seg("fox", 1.5, 2.0)])
        XCTAssertEqual(a.confirmedText, "the quick")
        XCTAssertEqual(a.hypothesisText, "brown fox")
        XCTAssertEqual(a.lastConfirmedEnd, 1.0, accuracy: 1e-6)
    }

    /// A second pass that revises the tail must NOT rewrite already-confirmed text; confirmed only grows.
    func testTailRevisionNeverRewritesConfirmed() {
        var a = StreamingAgreement(requiredUnconfirmed: 2)
        a.integrate([seg("the", 0, 0.5), seg("quick", 0.5, 1.0),
                     seg("brown", 1.0, 1.5), seg("fax", 1.5, 2.0)])          // "fax" — a tail guess
        XCTAssertEqual(a.confirmedText, "the quick")
        // Next pass has more audio; Whisper revised "fax"→"fox" and added words. Confirmed prefix is stable.
        a.integrate([seg("the", 0, 0.5), seg("quick", 0.5, 1.0), seg("brown", 1.0, 1.5),
                     seg("fox", 1.5, 2.0), seg("jumps", 2.0, 2.5), seg("over", 2.5, 3.0)])
        XCTAssertTrue(a.confirmedText.hasPrefix("the quick"))
        XCTAssertEqual(a.confirmedText, "the quick brown fox")   // "brown","fox" now confirmed
        XCTAssertEqual(a.hypothesisText, "jumps over")
        XCTAssertGreaterThanOrEqual(a.lastConfirmedEnd, 1.0)
    }

    /// `lastConfirmedEnd` is monotonic and confirmed segments are never duplicated across passes.
    func testMonotonicNoDuplication() {
        var a = StreamingAgreement(requiredUnconfirmed: 1)
        a.integrate([seg("a", 0, 1), seg("b", 1, 2), seg("c", 2, 3)])       // confirm a,b ; hyp c
        a.integrate([seg("a", 0, 1), seg("b", 1, 2), seg("c", 2, 3), seg("d", 3, 4)]) // re-decode whole buffer
        XCTAssertEqual(a.confirmedText, "a b c")     // no "a a b b c" duplication
        XCTAssertEqual(a.hypothesisText, "d")
        XCTAssertEqual(a.lastConfirmedEnd, 3, accuracy: 1e-6)
    }

    /// clipTimestamps-style feed: each pass returns only segments after the last confirmed point.
    func testClippedFeedConfirmsForward() {
        var a = StreamingAgreement(requiredUnconfirmed: 1)
        a.integrate([seg("one", 0, 1), seg("two", 1, 2)])                    // confirm one ; hyp two
        XCTAssertEqual(a.confirmedText, "one")
        a.integrate([seg("two", 1, 2), seg("three", 2, 3)])                  // clipped to post-1s audio
        XCTAssertEqual(a.confirmedText, "one two")
        XCTAssertEqual(a.hypothesisText, "three")
    }

    /// At stop, the trailing hypothesis is promoted to confirmed (no more audio is coming).
    func testFlushHypothesisPromotesTail() {
        var a = StreamingAgreement(requiredUnconfirmed: 2)
        a.integrate([seg("hello", 0, 1), seg("there", 1, 2), seg("world", 2, 3)])
        XCTAssertEqual(a.confirmedText, "hello")
        let final = a.flushHypothesis()
        XCTAssertEqual(final, "hello there world")
        XCTAssertEqual(a.hypothesisText, "")
    }

    /// Segment text is whitespace-trimmed and empty segments are dropped from the joined output.
    func testTrimsAndDropsEmpty() {
        var a = StreamingAgreement(requiredUnconfirmed: 0)
        a.integrate([seg("  hi  ", 0, 1), seg("", 1, 2), seg(" there", 2, 3)])
        XCTAssertEqual(a.confirmedText, "hi there")
    }

    /// The bug the user hit: WhisperKit timestamp/special tokens leaking onto the pill ("5.90 6.32").
    /// `sanitize` must strip `<|…|>` tokens and collapse whitespace, leaving only the words.
    func testSanitizeStripsTimestampAndSpecialTokens() {
        XCTAssertEqual(WhisperKitStreamingSession.sanitize("<|0.00|> hello world<|5.90|>"), "hello world")
        XCTAssertEqual(WhisperKitStreamingSession.sanitize("<|startoftranscript|><|en|><|transcribe|> hi"), "hi")
        XCTAssertEqual(WhisperKitStreamingSession.sanitize("five point <|5.90|> nine"), "five point nine")
        XCTAssertEqual(WhisperKitStreamingSession.sanitize("  plain   text  "), "plain text")
        XCTAssertEqual(WhisperKitStreamingSession.sanitize("<|6.32|>"), "")
    }
}
