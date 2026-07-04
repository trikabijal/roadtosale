import AVFoundation
import XCTest
@testable import DictationCore

// Streaming dictation (PRD 0007): StreamingDictationSession assembly — raw during speech, one
// cleanup pass at stop, and the segment-failure safety net. Deterministic — mock STT + cleanup.

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

private enum ScriptedError: Error { case boom }

/// STT mock with a per-call script: emit text, a legitimate empty/silence throw, or a transient
/// failure throw — so we can prove how the session distinguishes silence from a real failure.
@MainActor
private final class ScriptedTranscriber: SpeechTranscriber {
    enum Step { case text(String); case silence; case fail }
    var isLoaded = true
    private var steps: [Step]
    init(_ steps: [Step]) { self.steps = steps }
    func load(onProgress: (@MainActor (Double) -> Void)?) async throws {}
    func transcribe(buffers: [AVAudioPCMBuffer], audioStartDate: Date) async throws -> TranscriptionResult {
        switch steps.isEmpty ? .silence : steps.removeFirst() {
        case .text(let t):
            return TranscriptionResult(text: t, confidence: 1, audioDurationMs: 1000,
                                       latencyMs: 1, provider: .mock, model: "mock")
        case .silence: throw TranscriptionError.emptyResult
        case .fail:    throw ScriptedError.boom
        }
    }
}

@MainActor
final class StreamingDictationSessionTests: XCTestCase {

    private func makeSession(_ segments: [String], cleanup: RecordingCleanup, level: CleanupLevel = .full)
        -> StreamingDictationSession {
        StreamingDictationSession(transcriber: QueuedTranscriber(segments), cleanup: cleanup, level: level)
    }

    func testAssemblesRawThenCleansOnceAtFinish() async {
        let cleanup = RecordingCleanup()
        let s = makeSession(["Hello world.", "How are you?"], cleanup: cleanup)
        await s.ingest(segment: [], audioStartDate: Date())
        await s.ingest(segment: [], audioStartDate: Date())
        let result = await s.finish()
        // ONE cleanup pass over the full raw transcript — not per sentence.
        XCTAssertEqual(cleanup.calls.count, 1)
        XCTAssertEqual(cleanup.calls[0].raw, "Hello world. How are you?")
        XCTAssertEqual(result.cleanedText, "[Hello world. How are you?]")
        XCTAssertEqual(result.rawText, "Hello world. How are you?")
    }

    func testNeverPassesPriorContext() async {
        // The repeat-bug guard: no code path may thread a rolling priorContext into cleanup.
        let cleanup = RecordingCleanup()
        let s = makeSession(["First one.", "Second two."], cleanup: cleanup)
        await s.ingest(segment: [], audioStartDate: Date())
        await s.ingest(segment: [], audioStartDate: Date())
        _ = await s.finish()
        XCTAssertEqual(cleanup.calls.count, 1)
        XCTAssertEqual(cleanup.calls[0].context, "")   // always empty — nothing to echo/snowball
    }

    func testNoCleanupDuringSpeechOnlyAtFinish() async {
        let cleanup = RecordingCleanup()
        let s = makeSession(["I think", "we ship it."], cleanup: cleanup)
        await s.ingest(segment: [], audioStartDate: Date())
        XCTAssertEqual(cleanup.calls.count, 0)   // ingest never cleans
        await s.ingest(segment: [], audioStartDate: Date())
        XCTAssertEqual(cleanup.calls.count, 0)
        let result = await s.finish()
        XCTAssertEqual(cleanup.calls.count, 1)   // exactly one pass, at stop
        XCTAssertEqual(result.cleanedText, "[I think we ship it.]")
    }

    func testEmptySegmentsProduceEmptyResult() async {
        let cleanup = RecordingCleanup()
        let s = makeSession(["", ""], cleanup: cleanup)   // transcriber returns empty
        await s.ingest(segment: [], audioStartDate: Date())
        await s.ingest(segment: [], audioStartDate: Date())
        let result = await s.finish()
        XCTAssertEqual(cleanup.calls.count, 0)   // nothing to clean
        XCTAssertEqual(result.cleanedText, "")
        XCTAssertEqual(result.rawText, "")
    }

    func testLevelOffSkipsCleanup() async {
        let cleanup = RecordingCleanup()
        let s = makeSession(["Raw text here."], cleanup: cleanup, level: .off)
        await s.ingest(segment: [], audioStartDate: Date())
        let result = await s.finish()
        XCTAssertEqual(cleanup.calls.count, 0)
        XCTAssertEqual(result.cleanedText, "Raw text here.")
    }

    // MARK: - Segment-failure safety net (the "middle dropped" guard)

    /// A segment whose transcription THROWS leaves a hole in the disjoint raw. `finish()` must
    /// report `incomplete` so the caller re-transcribes the full audio instead of pasting a
    /// silently-truncated result. This is the regression test for the blocker.
    func testThrownSegmentMarksResultIncomplete() async {
        let cleanup = RecordingCleanup()
        let stt = ScriptedTranscriber([.text("First one."), .fail, .text("Third three.")])
        let s = StreamingDictationSession(transcriber: stt, cleanup: cleanup, level: .full)
        await s.ingest(segment: [], audioStartDate: Date())   // ok
        await s.ingest(segment: [], audioStartDate: Date())   // throws → hole
        await s.ingest(segment: [], audioStartDate: Date())   // ok
        let r = await s.finish()
        XCTAssertTrue(r.incomplete, "a thrown segment must flag the result incomplete")
        XCTAssertEqual(r.rawText, "First one. Third three.")   // middle is missing from the raw
    }

    /// A legitimately-empty/silent segment is NOT a failure — it must not flag incomplete.
    func testSilentSegmentDoesNotMarkIncomplete() async {
        let cleanup = RecordingCleanup()
        let stt = ScriptedTranscriber([.text("Hello."), .silence, .text("World.")])
        let s = StreamingDictationSession(transcriber: stt, cleanup: cleanup, level: .full)
        for _ in 0..<3 { await s.ingest(segment: [], audioStartDate: Date()) }
        let r = await s.finish()
        XCTAssertFalse(r.incomplete, "silence is not a transient failure")
        XCTAssertEqual(r.rawText, "Hello. World.")
    }

    /// Below `minWordsForCleanup`, streaming skips the LLM pass (parity with the batch path).
    func testShortClipSkipsCleanupBelowMinWords() async {
        let cleanup = RecordingCleanup()
        let s = StreamingDictationSession(transcriber: QueuedTranscriber(["Hi there."]),
                                          cleanup: cleanup, level: .full, minWordsForCleanup: 5)
        await s.ingest(segment: [], audioStartDate: Date())
        let r = await s.finish()
        XCTAssertEqual(cleanup.calls.count, 0, "2 words < 5 → no cleanup pass")
        XCTAssertEqual(r.cleanedText, "Hi there.")   // raw returned unchanged
    }
}
