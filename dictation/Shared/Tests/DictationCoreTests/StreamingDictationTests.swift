import AVFoundation
import XCTest
@testable import DictationCore

// Streaming dictation (PRD 0007): the SentenceBuffer boundary logic and the
// StreamingDictationSession assembly. Fully deterministic — mock STT + mock cleanup, no model.

final class SentenceBufferTests: XCTestCase {

    func testEmitsCompletedSentenceHoldsPartial() {
        var b = SentenceBuffer()
        b.append("Hello world. How are")
        XCTAssertEqual(b.drainCompleteSentences(), ["Hello world."])
        XCTAssertEqual(b.partial, "How are")
        b.append("you?")
        XCTAssertEqual(b.drainCompleteSentences(), ["How are you?"])
        XCTAssertEqual(b.partial, "")
    }

    func testMultipleSentencesInOneFragment() {
        var b = SentenceBuffer()
        b.append("One. Two! Three?")
        XCTAssertEqual(b.drainCompleteSentences(), ["One.", "Two!", "Three?"])
        XCTAssertEqual(b.partial, "")
    }

    func testPartialAccumulatesAcrossFragments() {
        var b = SentenceBuffer()
        b.append("I think")
        XCTAssertEqual(b.drainCompleteSentences(), [])
        b.append("we should ship it.")
        XCTAssertEqual(b.drainCompleteSentences(), ["I think we should ship it."])
    }

    func testRunawayPartialForceEmitted() {
        var b = SentenceBuffer(maxWordsBeforeFlush: 4)
        b.append("one two three four five")   // no terminator, over the guard
        XCTAssertEqual(b.drainCompleteSentences(), ["one two three four five"])
        XCTAssertEqual(b.partial, "")
    }

    func testDecimalNotTreatedAsBoundary() {
        var b = SentenceBuffer()
        b.append("It costs 3.50 dollars today.")   // "3.50" has no space after the dot
        XCTAssertEqual(b.drainCompleteSentences(), ["It costs 3.50 dollars today."])
    }

    func testFlushAllEmptyIsEmpty() {
        var b = SentenceBuffer()
        XCTAssertEqual(b.flushAll(), [])
    }
}

// MARK: - Mocks

@MainActor
private final class QueuedTranscriber: SpeechTranscriber {
    var isLoaded = true
    private var queue: [String]
    init(_ q: [String]) { queue = q }
    func load(onProgress: (@MainActor (Double) -> Void)?) async throws {}
    func transcribe(buffers: [AVAudioPCMBuffer], audioStartDate: Date) async throws -> TranscriptionResult {
        let t = queue.isEmpty ? "" : queue.removeFirst()
        return TranscriptionResult(text: t, confidence: 1, audioDurationMs: 1000,
                                   latencyMs: 1, provider: .mock, model: "mock")
    }
}

private final class RecordingCleanup: TextCleanup, @unchecked Sendable {
    private(set) var calls: [(raw: String, context: String)] = []
    func clean(_ req: CleanupRequest) async -> CleanupResult {
        calls.append((req.rawText, req.priorContext))
        return CleanupResult(cleanedText: "[\(req.rawText)]", opsApplied: ["mock"],
                             usedFallback: false, latencyMs: 1, provider: .ruleBased)
    }
}

@MainActor
final class StreamingDictationSessionTests: XCTestCase {

    private func makeSession(_ segments: [String], cleanup: RecordingCleanup, level: CleanupLevel = .full)
        -> StreamingDictationSession {
        StreamingDictationSession(transcriber: QueuedTranscriber(segments), cleanup: cleanup, level: level)
    }

    func testAssemblesCleanedSentencesInOrder() async {
        let cleanup = RecordingCleanup()
        let s = makeSession(["Hello world.", "How are you?"], cleanup: cleanup)
        await s.ingest(segment: [], audioStartDate: Date())
        await s.ingest(segment: [], audioStartDate: Date())
        let result = await s.finish()
        XCTAssertEqual(result.cleanedText, "[Hello world.] [How are you?]")
        XCTAssertEqual(result.rawText, "Hello world. How are you?")
    }

    func testRollingContextThreadsPreviousCleanedSentence() async {
        let cleanup = RecordingCleanup()
        let s = makeSession(["First one.", "Second two."], cleanup: cleanup)
        await s.ingest(segment: [], audioStartDate: Date())
        await s.ingest(segment: [], audioStartDate: Date())
        _ = await s.finish()
        XCTAssertEqual(cleanup.calls.count, 2)
        XCTAssertEqual(cleanup.calls[0].context, "")               // first sentence: no prior
        XCTAssertEqual(cleanup.calls[1].context, "[First one.]")   // second: prior cleaned sentence
    }

    func testPartialSentenceOnlyCleanedAtFinish() async {
        let cleanup = RecordingCleanup()
        // Two segments form ONE sentence; nothing completes until finish.
        let s = makeSession(["I think", "we ship"], cleanup: cleanup)
        await s.ingest(segment: [], audioStartDate: Date())
        XCTAssertEqual(cleanup.calls.count, 0)   // no boundary yet -> no cleanup during speech
        await s.ingest(segment: [], audioStartDate: Date())
        XCTAssertEqual(cleanup.calls.count, 0)
        let result = await s.finish()            // flushAll emits the partial
        XCTAssertEqual(cleanup.calls.count, 1)
        XCTAssertEqual(result.cleanedText, "[I think we ship]")
    }

    func testMidStreamSentencesCleanedDuringSpeechNotAtStop() async {
        let cleanup = RecordingCleanup()
        // First segment completes a sentence (cleaned live); second is a trailing partial.
        let s = makeSession(["Done sentence. Trailing", "words here"], cleanup: cleanup)
        await s.ingest(segment: [], audioStartDate: Date())
        XCTAssertEqual(cleanup.calls.count, 1)                  // "Done sentence." cleaned live
        XCTAssertEqual(cleanup.calls[0].raw, "Done sentence.")
        await s.ingest(segment: [], audioStartDate: Date())
        XCTAssertEqual(cleanup.calls.count, 1)                  // still just the partial pending
        let result = await s.finish()
        XCTAssertEqual(cleanup.calls.count, 2)                  // only the tail processed post-stop
        XCTAssertEqual(result.cleanedText, "[Done sentence.] [Trailing words here]")
    }

    func testLevelOffSkipsCleanup() async {
        let cleanup = RecordingCleanup()
        let s = makeSession(["Raw text here."], cleanup: cleanup, level: .off)
        await s.ingest(segment: [], audioStartDate: Date())
        let result = await s.finish()
        XCTAssertEqual(cleanup.calls.count, 0)
        XCTAssertEqual(result.cleanedText, "Raw text here.")
    }
}
