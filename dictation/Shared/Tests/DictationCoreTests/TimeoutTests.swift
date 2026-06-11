import XCTest
@testable import DictationCore

final class TimeoutTests: XCTestCase {

    /// The bug the rewrite fixes: a structured task group would await a hung child at scope
    /// exit, so the timeout never fired. This operation never returns AND ignores cancellation
    /// (a continuation-based suspend) — withTimeout must still throw TimeoutError promptly.
    func testFiresEvenWhenOperationIgnoresCancellation() async {
        let start = Date()
        do {
            _ = try await withTimeout(seconds: 0.2) {
                await withCheckedContinuation { (_: CheckedContinuation<Int, Never>) in /* never resumes */ }
            }
            XCTFail("expected TimeoutError")
        } catch is TimeoutError {
            XCTAssertLessThan(Date().timeIntervalSince(start), 2.0, "timeout should fire near the deadline, not hang")
        } catch {
            XCTFail("unexpected error: \(error)")
        }
    }

    func testReturnsValueWhenOperationFinishesInTime() async throws {
        let v = try await withTimeout(seconds: 5) { 42 }
        XCTAssertEqual(v, 42)
    }

    func testPropagatesOperationErrorNotTimeout() async {
        struct Boom: Error {}
        do {
            _ = try await withTimeout(seconds: 5) { throw Boom() }
            XCTFail("expected Boom")
        } catch is TimeoutError {
            XCTFail("should surface the operation's error, not a timeout")
        } catch is Boom {
            // correct
        } catch {
            XCTFail("unexpected: \(error)")
        }
    }
}
