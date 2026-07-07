import XCTest
import AVFoundation
@testable import DictationCore
@testable import DictationCoreBase

/// Runnable version of journey J2's core (dictate → clean) at the contract level, with the mock
/// transcriber so it's deterministic and needs no model: STT output flows through cleanup and
/// comes out polished. (Full-UI journeys — onboarding, paste targeting, keyboard freeze — need a
/// UI harness and are tracked separately.)
@MainActor
final class PipelineTests: XCTestCase {

    func testDictateThenCleanProducesPolishedText() async throws {
        // 1. "Speak" — mock STT returns a filler-laden transcript regardless of audio.
        let stt = MockTranscriber(cannedText: "um so i think this is working uh and we should ship it")
        try await stt.load()
        XCTAssertTrue(stt.isLoaded)
        guard let buffer = AudioSampleBridge.makeBuffer(samples: [Float](repeating: 0.1, count: 1600),
                                                        sampleRate: 16000) else { return XCTFail() }
        let raw = try await stt.transcribe(buffers: [buffer], audioStartDate: Date()).text
        XCTAssertFalse(raw.isEmpty)

        // 2. Clean — deterministic rule-based path (no LLM needed in CI).
        let pack = CleanupPackLoader.load()
        let cleanup = RuleBasedCleanup(pack: pack)
        let cleaned = await cleanup.clean(CleanupRequest(rawText: raw, level: .full,
                                                         commandGrammar: pack.commandGrammar)).cleanedText

        // 3. Assert the user-visible result: fillers gone, sentence capitalized, content intact.
        XCTAssertFalse(cleaned.lowercased().split(whereSeparator: \.isWhitespace).contains("um"))
        XCTAssertFalse(cleaned.lowercased().split(whereSeparator: \.isWhitespace).contains("uh"))
        XCTAssertEqual(cleaned.first, cleaned.first?.uppercased().first, "first letter should be capitalized")
        XCTAssertTrue(cleaned.lowercased().contains("ship it"), "real content preserved")
    }

    func testResetIsSafeToCall() async throws {
        let stt = MockTranscriber()
        try await stt.load()
        stt.reset()   // contract: callable on any transcriber (no-op for mock); must not crash
        RuleBasedCleanup(pack: CleanupPackLoader.load()).reset()
    }
}
