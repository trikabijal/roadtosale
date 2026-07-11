import XCTest
@testable import DictationCoreBase

/// The Flow-Session handoff is the mechanism behind the "no app switch" UX: the keyboard reads the
/// session heartbeat to decide whether to signal a live background app (seamless) or relaunch it
/// (cold). These pin the deterministic contract both processes depend on.
final class DictationHandoffTests: XCTestCase {

    private var store: UserDefaults!

    override func setUp() {
        super.setUp()
        store = UserDefaults(suiteName: DictationHandoff.appGroup)
        store.removePersistentDomain(forName: DictationHandoff.appGroup)
    }

    override func tearDown() {
        store.removePersistentDomain(forName: DictationHandoff.appGroup)
        super.tearDown()
    }

    // MARK: - Session liveness

    func testSessionNotAliveByDefault() {
        XCTAssertFalse(DictationHandoff.isSessionAlive(), "no heartbeat → cold path (relaunch)")
    }

    func testMarkSessionAliveMakesItAlive() {
        DictationHandoff.markSessionAlive()
        XCTAssertTrue(DictationHandoff.isSessionAlive(), "fresh heartbeat → seamless path (signal)")
    }

    func testStaleHeartbeatIsNotAlive() {
        // Simulate a heartbeat older than the window: the app was suspended/killed, so the keyboard
        // must fall back to relaunching rather than signalling a dead process.
        store.set(Date().timeIntervalSince1970 - 60, forKey: "flowSessionHeartbeat")
        XCTAssertFalse(DictationHandoff.isSessionAlive(maxAgeSeconds: 8))
    }

    func testMarkSessionEndedClearsLiveness() {
        DictationHandoff.markSessionAlive()
        XCTAssertTrue(DictationHandoff.isSessionAlive())
        DictationHandoff.markSessionEnded()
        XCTAssertFalse(DictationHandoff.isSessionAlive(), "ended session → next tap relaunches")
    }

    // MARK: - Transcript round-trip

    func testWriteThenConsumeReturnsText() {
        DictationHandoff.write("hello world")
        XCTAssertEqual(DictationHandoff.consume(), "hello world")
    }

    func testConsumeIsOneShot() {
        DictationHandoff.write("once")
        XCTAssertEqual(DictationHandoff.consume(), "once")
        XCTAssertNil(DictationHandoff.consume(), "second read must be nil — no double insert")
    }

    func testStaleTranscriptIsIgnored() {
        store.set(["text": "old", "ts": Date().timeIntervalSince1970 - 300], forKey: "pendingDictation")
        XCTAssertNil(DictationHandoff.consume(maxAgeSeconds: 120),
                     "an abandoned transcript must not inject into an unrelated field later")
    }

    // MARK: - Live mic level

    func testLevelRoundTrip() {
        DictationHandoff.writeLevel(0.42)
        XCTAssertEqual(DictationHandoff.readLevel(), 0.42, accuracy: 0.0001)
    }

    func testLevelDefaultsToZero() {
        XCTAssertEqual(DictationHandoff.readLevel(), 0, accuracy: 0.0001)
    }
}
