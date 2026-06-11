import Foundation

/// Thrown by `withTimeout` when the operation exceeds its deadline.
public struct TimeoutError: Error { public init() {} }

/// Run `operation`, throwing `TimeoutError` if it doesn't finish within `seconds`.
///
/// Implemented as an UNSTRUCTURED race: the operation and a timer run as independent tasks,
/// and whichever finishes first resolves the result; the loser is cancelled and **abandoned**
/// (never awaited). This is deliberate — a structured `withThrowingTaskGroup` waits for all
/// children at scope exit, so if the operation ignores cooperative cancellation (e.g. a hung
/// `WhisperKit.transcribe` or `LanguageModelSession.respond`), the timeout would never actually
/// fire and the caller would hang. Here the caller is freed at the deadline regardless; a truly
/// stuck operation task lingers in the background (the OS reclaims it) but cannot block fallback.
public func withTimeout<T: Sendable>(
    seconds: Double,
    operation: @escaping @Sendable () async throws -> T
) async throws -> T {
    let box = TimeoutBox<T>()
    let op = Task {
        do { box.resume(.success(try await operation())) }
        catch { box.resume(.failure(error)) }
    }
    let timer = Task {
        try? await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
        box.resume(.failure(TimeoutError()))
    }
    defer { op.cancel(); timer.cancel() }
    return try await withCheckedThrowingContinuation { box.setContinuation($0) }
}

/// Resolves a continuation exactly once, whichever of the racing tasks reports first.
private final class TimeoutBox<T: Sendable>: @unchecked Sendable {
    private let lock = NSLock()
    private var cont: CheckedContinuation<T, Error>?
    private var pending: Result<T, Error>?
    private var done = false

    func setContinuation(_ c: CheckedContinuation<T, Error>) {
        lock.lock()
        if let p = pending { lock.unlock(); c.resume(with: p); return }
        cont = c
        lock.unlock()
    }

    func resume(_ result: Result<T, Error>) {
        lock.lock()
        if done { lock.unlock(); return }
        done = true
        let c = cont
        cont = nil
        if c == nil { pending = result }   // arrived before the awaiter registered
        lock.unlock()
        c?.resume(with: result)
    }
}
