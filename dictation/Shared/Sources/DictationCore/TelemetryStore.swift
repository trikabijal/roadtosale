import Foundation
import GRDB

// MARK: - Record

public struct TranscriptRecord: Identifiable, Codable, FetchableRecord, PersistableRecord, Sendable {
    public static var databaseTableName = "transcript_records"

    // The table uses snake_case columns; map camelCase properties to them.
    public static let databaseColumnEncodingStrategy = DatabaseColumnEncodingStrategy.convertToSnakeCase
    public static let databaseColumnDecodingStrategy = DatabaseColumnDecodingStrategy.convertFromSnakeCase

    public var id: String
    public var platform: String           // "mac" | "ios"
    public var recordedAt: Date
    public var audioDurationMs: Int
    public var transcriptText: String     // final pasted text (post-cleanup)
    public var wordCount: Int
    public var whisperkitConfidence: Double
    public var latencyMs: Int
    public var modelTier: String          // "<sttProvider>/<model>"
    public var frontmostApp: String?      // macOS only
    public var wasCorrected: Bool
    public var correctionNote: String?
    // Cleanup telemetry (PRD 0004) — the labelled dataset for the shared cleanup pack.
    public var rawText: String?           // pre-cleanup STT output
    public var cleanupLevel: String?      // "off" | "light" | "full"
    public var cleanupProvider: String?   // cleanup provider used, nil if cleanup skipped

    public init(
        id: String = UUID().uuidString,
        platform: String,
        recordedAt: Date = Date(),
        audioDurationMs: Int,
        transcriptText: String,
        wordCount: Int? = nil,
        whisperkitConfidence: Double,
        latencyMs: Int,
        modelTier: String,
        frontmostApp: String? = nil,
        wasCorrected: Bool = false,
        correctionNote: String? = nil,
        rawText: String? = nil,
        cleanupLevel: String? = nil,
        cleanupProvider: String? = nil
    ) {
        self.id = id
        self.platform = platform
        self.recordedAt = recordedAt
        self.audioDurationMs = audioDurationMs
        self.transcriptText = transcriptText
        self.wordCount = wordCount ?? transcriptText.split(separator: " ").count
        self.whisperkitConfidence = whisperkitConfidence
        self.latencyMs = latencyMs
        self.modelTier = modelTier
        self.frontmostApp = frontmostApp
        self.wasCorrected = wasCorrected
        self.correctionNote = correctionNote
        self.rawText = rawText
        self.cleanupLevel = cleanupLevel
        self.cleanupProvider = cleanupProvider
    }
}

// MARK: - Stats

public struct WeeklyStats: Sendable {
    public let totalCount: Int
    public let correctionRate: Double      // 0.0–1.0
    public let avgConfidence: Double
    public let avgLatencyMs: Double
    public let avgAudioDurationMs: Double

    public static let empty = WeeklyStats(
        totalCount: 0, correctionRate: 0,
        avgConfidence: 0, avgLatencyMs: 0, avgAudioDurationMs: 0
    )
}

// MARK: - Store

public actor TelemetryStore {

    private let dbQueue: DatabaseQueue

    public init(databaseURL: URL) throws {
        var config = Configuration()
        config.label = "DictationTelemetry"
        self.dbQueue = try DatabaseQueue(path: databaseURL.path, configuration: config)
        try Self.migrate(dbQueue)
    }

    // MARK: - Write

    public func save(_ record: TranscriptRecord) throws {
        try dbQueue.write { db in
            try record.insert(db)
        }
    }

    public func markCorrected(id: String, note: String?) throws {
        try dbQueue.write { db in
            try db.execute(
                sql: "UPDATE transcript_records SET was_corrected = 1, correction_note = ? WHERE id = ?",
                arguments: [note, id]
            )
        }
    }

    // MARK: - Read

    public func fetchRecent(limit: Int = 5) throws -> [TranscriptRecord] {
        try dbQueue.read { db in
            try TranscriptRecord
                .order(Column("recorded_at").desc)
                .limit(limit)
                .fetchAll(db)
        }
    }

    /// Full-text-ish search over transcript text (case-insensitive substring).
    /// Empty query returns the most recent records.
    public func search(matching query: String, limit: Int = 100) throws -> [TranscriptRecord] {
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        return try dbQueue.read { db in
            var request = TranscriptRecord.order(Column("recorded_at").desc).limit(limit)
            if !trimmed.isEmpty {
                request = request.filter(Column("transcript_text").like("%\(trimmed)%"))
            }
            return try request.fetchAll(db)
        }
    }

    public func fetchWeeklyStats() throws -> WeeklyStats {
        let weekAgo = Date().addingTimeInterval(-7 * 24 * 3600)
        return try dbQueue.read { db in
            let rows = try Row.fetchAll(
                db,
                sql: """
                    SELECT
                        COUNT(*) as total,
                        AVG(CASE WHEN was_corrected = 1 THEN 1.0 ELSE 0.0 END) as correction_rate,
                        AVG(whisperkit_confidence) as avg_confidence,
                        AVG(latency_ms) as avg_latency,
                        AVG(audio_duration_ms) as avg_audio_duration
                    FROM transcript_records
                    WHERE recorded_at >= ?
                    """,
                arguments: [weekAgo]
            )
            guard let row = rows.first else { return .empty }
            return WeeklyStats(
                totalCount: row["total"] ?? 0,
                correctionRate: row["correction_rate"] ?? 0,
                avgConfidence: row["avg_confidence"] ?? 0,
                avgLatencyMs: row["avg_latency"] ?? 0,
                avgAudioDurationMs: row["avg_audio_duration"] ?? 0
            )
        }
    }

    // MARK: - Migration

    private static func migrate(_ dbQueue: DatabaseQueue) throws {
        var migrator = DatabaseMigrator()
        migrator.registerMigration("v1_create_transcripts") { db in
            try db.create(table: TranscriptRecord.databaseTableName) { t in
                t.column("id", .text).primaryKey()
                t.column("platform", .text).notNull()
                t.column("recorded_at", .datetime).notNull()
                t.column("audio_duration_ms", .integer).notNull()
                t.column("transcript_text", .text).notNull()
                t.column("word_count", .integer).notNull()
                t.column("whisperkit_confidence", .double).notNull()
                t.column("latency_ms", .integer).notNull()
                t.column("model_tier", .text).notNull()
                t.column("frontmost_app", .text)
                t.column("was_corrected", .boolean).notNull().defaults(to: false)
                t.column("correction_note", .text)
            }
        }
        migrator.registerMigration("v2_add_cleanup_columns") { db in
            try db.alter(table: TranscriptRecord.databaseTableName) { t in
                t.add(column: "raw_text", .text)
                t.add(column: "cleanup_level", .text)
                t.add(column: "cleanup_provider", .text)
            }
        }
        try migrator.migrate(dbQueue)
    }

    // MARK: - Database URL helpers

    public static func macOSDatabaseURL() throws -> URL {
        let appSupport = try FileManager.default.url(
            for: .applicationSupportDirectory,
            in: .userDomainMask,
            appropriateFor: nil,
            create: true
        )
        let dir = appSupport.appendingPathComponent("com.trika.dictation", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir.appendingPathComponent("telemetry.sqlite")
    }

    public static func iOSDatabaseURL() throws -> URL {
        guard let containerURL = FileManager.default.containerURL(
            forSecurityApplicationGroupIdentifier: "group.com.trika.dictation"
        ) else {
            throw TelemetryStoreError.appGroupUnavailable
        }
        return containerURL.appendingPathComponent("telemetry.sqlite")
    }
}

public enum TelemetryStoreError: Error {
    case appGroupUnavailable
}
