import XCTest
@testable import DictationCore

final class TelemetryRetentionTests: XCTestCase {

    // MARK: - Helpers

    /// A fresh, isolated on-disk store per test (deleted in the returned cleanup closure's defer).
    private func makeStore() throws -> (TelemetryStore, URL) {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("tel-\(UUID().uuidString).sqlite")
        return (try TelemetryStore(databaseURL: url), url)
    }

    private func rec(
        _ id: String,
        daysAgo: Double = 0,
        durationMs: Int = 1000,
        confidence: Double = 0.9,
        latencyMs: Int = 100,
        corrected: Bool = false,
        text: String = "hello world"
    ) -> TranscriptRecord {
        TranscriptRecord(
            id: id, platform: "mac",
            recordedAt: Date().addingTimeInterval(-daysAgo * 86_400),
            audioDurationMs: durationMs, transcriptText: text,
            whisperkitConfidence: confidence, latencyMs: latencyMs,
            modelTier: "test/x", wasCorrected: corrected
        )
    }

    // MARK: - Retention (existing)

    func testPurgeRemovesRecordsOlderThanWindow() async throws {
        let (store, url) = try makeStore()
        defer { try? FileManager.default.removeItem(at: url) }

        try await store.save(rec("old", daysAgo: 40))
        try await store.save(rec("edge", daysAgo: 29))
        try await store.save(rec("fresh", daysAgo: 0))

        let removed = try await store.purge(olderThanDays: 30)
        XCTAssertEqual(removed, 1, "only the 40-day-old record should be purged")
        let remaining = try await store.fetchRecent(limit: 10).map(\.id).sorted()
        XCTAssertEqual(remaining, ["edge", "fresh"])
    }

    // MARK: - Save → aggregate (T-TEL-1 / backlog #7)

    /// A completed dictation persists, and weekly stats aggregate the in-window records:
    /// count, average confidence/latency/duration, and summed audio. Records older than a
    /// week are excluded from the weekly window.
    func testWeeklyStatsAggregateInWindowRecords() async throws {
        let (store, url) = try makeStore()
        defer { try? FileManager.default.removeItem(at: url) }

        // Two in-window records with known values, plus one outside the 7-day window.
        try await store.save(rec("a", daysAgo: 0, durationMs: 1000, confidence: 0.8, latencyMs: 100))
        try await store.save(rec("b", daysAgo: 1, durationMs: 3000, confidence: 0.6, latencyMs: 300))
        try await store.save(rec("stale", daysAgo: 10, durationMs: 9999, confidence: 0.1, latencyMs: 9999))

        let stats = try await store.fetchWeeklyStats()
        XCTAssertEqual(stats.totalCount, 2, "stale (10-day-old) record is outside the weekly window")
        XCTAssertEqual(stats.avgConfidence, 0.7, accuracy: 0.0001)   // (0.8 + 0.6) / 2
        XCTAssertEqual(stats.avgLatencyMs, 200, accuracy: 0.0001)    // (100 + 300) / 2
        XCTAssertEqual(stats.avgAudioDurationMs, 2000, accuracy: 0.0001) // (1000 + 3000) / 2
        XCTAssertEqual(stats.totalAudioMs, 4000, accuracy: 0.0001)   // 1000 + 3000
    }

    /// `markCorrected` flips a record's flag, which raises the weekly correction rate
    /// (fraction of in-window records corrected). J9's persistence backstop.
    func testMarkCorrectedRaisesCorrectionRate() async throws {
        let (store, url) = try makeStore()
        defer { try? FileManager.default.removeItem(at: url) }

        try await store.save(rec("a"))
        try await store.save(rec("b"))
        try await store.save(rec("c"))
        try await store.save(rec("d"))

        let before = try await store.fetchWeeklyStats()
        XCTAssertEqual(before.correctionRate, 0, accuracy: 0.0001, "nothing corrected yet")

        try await store.markCorrected(id: "a", note: "missed a name")

        let after = try await store.fetchWeeklyStats()
        XCTAssertEqual(after.correctionRate, 0.25, accuracy: 0.0001, "1 of 4 corrected")
        // The note persisted on the right row, untouched rows stay uncorrected.
        let rows = try await store.fetchRecent(limit: 10)
        let a = try XCTUnwrap(rows.first { $0.id == "a" })
        XCTAssertTrue(a.wasCorrected)
        XCTAssertEqual(a.correctionNote, "missed a name")
        XCTAssertFalse(try XCTUnwrap(rows.first { $0.id == "b" }).wasCorrected)
    }

    /// All-time usage totals sum every record (no time window) — the basis for cost projection.
    func testUsageTotalsSumAllRecordsIgnoringWindow() async throws {
        let (store, url) = try makeStore()
        defer { try? FileManager.default.removeItem(at: url) }

        try await store.save(rec("a", daysAgo: 0, durationMs: 60_000))
        try await store.save(rec("b", daysAgo: 100, durationMs: 120_000))  // far outside any week

        let totals = try await store.fetchUsageTotals()
        XCTAssertEqual(totals.totalCount, 2, "all-time totals ignore the weekly window")
        XCTAssertEqual(totals.totalAudioMs, 180_000)
        XCTAssertEqual(totals.totalMinutes, 3.0, accuracy: 0.0001)   // 180_000ms = 3 min
        XCTAssertEqual(totals.totalHours, 0.05, accuracy: 0.0001)
    }

    /// Empty store yields zeroed (not crashing / not nil) aggregates.
    func testStatsOnEmptyStoreAreZero() async throws {
        let (store, url) = try makeStore()
        defer { try? FileManager.default.removeItem(at: url) }

        let stats = try await store.fetchWeeklyStats()
        XCTAssertEqual(stats.totalCount, 0)
        XCTAssertEqual(stats.totalAudioMs, 0, accuracy: 0.0001)

        let totals = try await store.fetchUsageTotals()
        XCTAssertEqual(totals.totalCount, 0)
        XCTAssertEqual(totals.totalAudioMs, 0)
    }

    /// Substring search is case-insensitive and ordered newest-first; an empty query
    /// returns the most recent records rather than filtering.
    func testSearchMatchesSubstringNewestFirst() async throws {
        let (store, url) = try makeStore()
        defer { try? FileManager.default.removeItem(at: url) }

        try await store.save(rec("old", daysAgo: 2, text: "ship the Dictation feature"))
        try await store.save(rec("new", daysAgo: 0, text: "DICTATION ships monday"))
        try await store.save(rec("other", daysAgo: 1, text: "unrelated note"))

        let hits = try await store.search(matching: "dictation").map(\.id)
        XCTAssertEqual(hits, ["new", "old"], "case-insensitive substring, newest first")

        let all = try await store.search(matching: "   ").map(\.id)
        XCTAssertEqual(all, ["new", "other", "old"], "blank query returns recent, not filtered")
    }
}
