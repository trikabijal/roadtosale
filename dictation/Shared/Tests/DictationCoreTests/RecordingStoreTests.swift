import XCTest
@testable import DictationCore
@testable import DictationCoreBase

#if canImport(AVFoundation)
final class RecordingStoreTests: XCTestCase {

    private var dir: URL!

    override func setUpWithError() throws {
        dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("rec-store-tests-\(UUID().uuidString)", isDirectory: true)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: dir)
    }

    private func samples(_ value: Float, _ count: Int) -> [Float] {
        [Float](repeating: value, count: count)
    }

    func testSaveThenLoadRoundTripsSamples() throws {
        let store = try FileRecordingStore(directory: dir, maxRecordings: 5)
        let input = samples(0.5, 1600)                       // 0.1s @ 16kHz
        let rec = try store.save(samples: input, sampleRate: 16_000, recordedAt: Date(timeIntervalSince1970: 1000))

        let loaded = try store.loadSamples(id: rec.id)
        XCTAssertEqual(loaded.sampleRate, 16_000)
        XCTAssertEqual(loaded.samples.count, input.count)
        XCTAssertEqual(try XCTUnwrap(loaded.samples.first), Float(0.5), accuracy: 0.0001)
        XCTAssertEqual(rec.durationMs, 100)                  // 1600 / 16000 * 1000
    }

    func testRetainsOnlyNewestFive() throws {
        let store = try FileRecordingStore(directory: dir, maxRecordings: 5)
        // Save 6, each 1s apart so filenames (epoch-millis) are distinct and ordered.
        for i in 0..<6 {
            _ = try store.save(samples: samples(Float(i) / 10, 800),
                               sampleRate: 16_000,
                               recordedAt: Date(timeIntervalSince1970: 1000 + Double(i)))
        }
        let recent = store.recent()
        XCTAssertEqual(recent.count, 5)
        // Newest first: the i=5 recording (latest date) leads; i=0 was pruned.
        XCTAssertEqual(recent.first?.recordedAt, Date(timeIntervalSince1970: 1005))
        XCTAssertEqual(recent.last?.recordedAt, Date(timeIntervalSince1970: 1001))
    }

    func testLoadMissingThrows() throws {
        let store = try FileRecordingStore(directory: dir, maxRecordings: 5)
        XCTAssertThrowsError(try store.loadSamples(id: "rec-1-1.wav"))
    }

    func testBufferBridgeRoundTrip() {
        let original = samples(0.25, 320)
        guard let buffer = AudioSampleBridge.makeBuffer(samples: original, sampleRate: 16_000) else {
            return XCTFail("makeBuffer returned nil")
        }
        let back = AudioSampleBridge.flatten([buffer])
        XCTAssertEqual(back.count, original.count)
        XCTAssertEqual(try XCTUnwrap(back.first), Float(0.25), accuracy: 0.0001)
    }
}
#endif
