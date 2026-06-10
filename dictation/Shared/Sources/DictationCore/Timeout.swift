import Foundation

/// Thrown by `withTimeout` when the operation exceeds its deadline.
public struct TimeoutError: Error { public init() {} }

/// Run `operation`, throwing `TimeoutError` if it does not complete within `seconds`.
///
/// On timeout the operation's task is cancelled (cooperatively — a long-running model call
/// may not stop instantly, but the caller stops waiting and can fall back). This turns a hung
/// on-device model call — the cleanup LLM or the STT engine — into a graceful fallback
/// instead of a HUD stuck on "Cleaning…"/"Transcribing…" with no paste.
public func withTimeout<T: Sendable>(
    seconds: Double,
    operation: @escaping @Sendable () async throws -> T
) async throws -> T {
    try await withThrowingTaskGroup(of: T.self) { group in
        group.addTask { try await operation() }
        group.addTask {
            try await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
            throw TimeoutError()
        }
        defer { group.cancelAll() }
        guard let result = try await group.next() else { throw TimeoutError() }
        return result
    }
}
