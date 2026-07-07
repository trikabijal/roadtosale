import AVFoundation
import XCTest
@testable import DictationCore
@testable import DictationCoreBase

/// A fresh 16 kHz mono buffer. Free function (not a method) so it's safely callable from the
/// concurrent-append task group without capturing a non-Sendable test instance.
private func pcmBuffer(_ frames: Int = 160) -> AVAudioPCMBuffer {
    let fmt = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: 16000,
                            channels: 1, interleaved: false)!
    let b = AVAudioPCMBuffer(pcmFormat: fmt, frameCapacity: AVAudioFrameCount(frames))!
    b.frameLength = AVAudioFrameCount(frames)
    return b
}

final class CapturedAudioStreamTests: XCTestCase {

    func testAppendPreservesOrderAndCount() {
        let s = CapturedAudioStream()
        let a = pcmBuffer(), b = pcmBuffer(), c = pcmBuffer()
        s.append(a); s.append(b); s.append(c)
        let snap = s.snapshot()
        XCTAssertEqual(snap.count, 3)
        XCTAssertTrue(snap[0] === a && snap[1] === b && snap[2] === c, "order must be preserved")
        XCTAssertEqual(s.count, 3)
    }

    func testResetEmptiesThenAppendStillWorks() {
        let s = CapturedAudioStream()
        s.append(pcmBuffer()); s.append(pcmBuffer())
        s.reset()
        XCTAssertEqual(s.count, 0)
        XCTAssertTrue(s.snapshot().isEmpty)
        s.append(pcmBuffer())
        XCTAssertEqual(s.count, 1)
    }

    func testDrainReturnsTailAndNewCount() {
        let s = CapturedAudioStream()
        let a = pcmBuffer(), b = pcmBuffer(), c = pcmBuffer()
        s.append(a); s.append(b); s.append(c)
        let (tail, total) = s.drain(after: 1)
        XCTAssertEqual(total, 3)
        XCTAssertEqual(tail.count, 2)
        XCTAssertTrue(tail[0] === b && tail[1] === c)
    }

    func testDrainEdgeCases() {
        let s = CapturedAudioStream()
        s.append(pcmBuffer()); s.append(pcmBuffer())   // count = 2
        // after == count → empty tail, count reported
        XCTAssertTrue(s.drain(after: 2).buffers.isEmpty)
        XCTAssertEqual(s.drain(after: 2).count, 2)
        // after > count → empty, no crash
        XCTAssertTrue(s.drain(after: 5).buffers.isEmpty)
        // negative index → guarded, empty tail + real total
        let neg = s.drain(after: -1)
        XCTAssertTrue(neg.buffers.isEmpty)
        XCTAssertEqual(neg.count, 2)
        // after count-1 → exactly one element
        XCTAssertEqual(s.drain(after: 1).buffers.count, 1)
    }

    /// The reason the type exists: safe concurrent producer. All appends must land, no crash.
    /// (Run under the Thread Sanitizer for the strongest signal.)
    func testConcurrentAppendsAllLand() async {
        let s = CapturedAudioStream()
        let n = 500
        await withTaskGroup(of: Void.self) { group in
            for _ in 0..<n { group.addTask { s.append(pcmBuffer(16)) } }
        }
        XCTAssertEqual(s.count, n)
        XCTAssertEqual(s.snapshot().count, n)
    }
}
