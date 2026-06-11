import XCTest
@testable import DictationCore

final class TelemetryRetentionTests: XCTestCase {

    func testPurgeRemovesRecordsOlderThanWindow() async throws {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("tel-\(UUID().uuidString).sqlite")
        let store = try TelemetryStore(databaseURL: url)
        defer { try? FileManager.default.removeItem(at: url) }

        func rec(_ id: String, daysAgo: Double) -> TranscriptRecord {
            TranscriptRecord(id: id, platform: "mac",
                             recordedAt: Date().addingTimeInterval(-daysAgo * 86_400),
                             audioDurationMs: 1000, transcriptText: "hello world",
                             whisperkitConfidence: 0.9, latencyMs: 100, modelTier: "test/x")
        }
        try await store.save(rec("old", daysAgo: 40))
        try await store.save(rec("edge", daysAgo: 29))
        try await store.save(rec("fresh", daysAgo: 0))

        let removed = try await store.purge(olderThanDays: 30)
        XCTAssertEqual(removed, 1, "only the 40-day-old record should be purged")
        let remaining = try await store.fetchRecent(limit: 10).map(\.id).sorted()
        XCTAssertEqual(remaining, ["edge", "fresh"])
    }
}
