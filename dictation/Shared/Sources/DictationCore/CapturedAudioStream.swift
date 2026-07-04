import Foundation
import os

#if canImport(AVFoundation)
import AVFoundation

/// Thread-safe, ORDER-PRESERVING capture buffer, designed so the audio render thread is never
/// meaningfully blocked.
///
/// The mic tap delivers buffers serially on its real-time render thread. That thread appends here
/// under an `os_unfair_lock` — no syscall (unlike `NSLock`), and the critical sections are tiny.
/// `snapshot`/`drain` are an Array copy-on-write retain (cheap); the one non-O(1) case is an
/// `append` made while a `snapshot` result is still alive, which triggers a COW copy of the backing
/// store inside the lock. That's brief and infrequent (snapshots happen only on VAD silence during
/// recording) and is identical to the previous `NSLock` accumulator — just called out honestly.
///
/// At 16 kHz mono Float32 (~64 KB/s) a full lock-free sample ring would be over-engineering; this
/// keeps the render thread safe without that complexity. Replaces the previous `NSLock`-based
/// accumulator (a syscall per append, worse behaviour under contention / priority inversion).
///
/// Order matters: appending synchronously on the render thread (rather than hopping to an actor per
/// buffer) is what keeps long recordings in temporal order — independent actor tasks can run out of
/// sequence and scramble the audio (short clips happened to stay ordered, hence "short worked, long
/// failed").
public final class CapturedAudioStream: @unchecked Sendable {
    // `uncheckedState` because AVAudioPCMBuffer is not Sendable; the lock provides the safety.
    private let state = OSAllocatedUnfairLock(uncheckedState: [AVAudioPCMBuffer]())

    public init() {}

    /// Append one captured buffer. Called on the audio render thread. O(1), no syscall.
    public func append(_ buffer: AVAudioPCMBuffer) {
        state.withLockUnchecked { $0.append(buffer) }
    }

    /// All captured buffers, in order (COW copy — an O(1) retain).
    public func snapshot() -> [AVAudioPCMBuffer] {
        state.withLockUnchecked { $0 }
    }

    /// Buffers captured after `index`, plus the new total count — lets a streaming consumer flush
    /// only the newly-arrived segment without rescanning. The consumer passes back the returned
    /// `count` on the next call.
    public func drain(after index: Int) -> (buffers: [AVAudioPCMBuffer], count: Int) {
        state.withLockUnchecked { buffers in
            let total = buffers.count
            guard index >= 0, total > index else { return ([], total) }
            return (Array(buffers[index..<total]), total)
        }
    }

    /// Number of buffers captured so far.
    public var count: Int { state.withLockUnchecked { $0.count } }

    public func reset() {
        state.withLockUnchecked { $0.removeAll(keepingCapacity: true) }
    }
}
#endif
