import Foundation

/// Durable storage for the user's custom vocabulary — DELIBERATELY not `UserDefaults`.
///
/// `UserDefaults` is keyed by **bundle identifier**, so when the macOS app's bundle id was renamed
/// (`com.trika.dictation.mac` → `com.trika.justtalk.mac`) the word list was orphaned and lost — customer
/// data must never be that fragile. This persists to a JSON file in the SAME stable, name-keyed
/// directory as the telemetry DB (`…/com.trika.dictation/`), which *survived* that rename because its
/// path is keyed by the original product name, not the bundle id. A rename can't orphan it again.
///
/// Small and synchronous on purpose — the list is tiny and callers read it at launch.
public struct VocabularyStore {
    private let url: URL

    public init(url: URL) { self.url = url }

    /// Load the saved terms (empty if none / unreadable).
    public func load() -> [String] {
        guard let data = try? Data(contentsOf: url),
              let terms = try? JSONDecoder().decode([String].self, from: data) else { return [] }
        return terms
    }

    /// Persist the terms atomically (creates the directory if needed).
    public func save(_ terms: [String]) {
        let dir = url.deletingLastPathComponent()
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        guard let data = try? JSONEncoder().encode(terms) else { return }
        try? data.write(to: url, options: .atomic)
    }

    // MARK: - Stable locations (reuse the telemetry DB's directory so it can't drift)

    /// macOS: alongside the telemetry DB in Application Support — name-keyed, survives bundle renames.
    public static func macOSURL() throws -> URL {
        try TelemetryStore.macOSDatabaseURL().deletingLastPathComponent()
            .appendingPathComponent("vocabulary.json")
    }

    /// iOS: the shared App Group container (stable across renames, shared with the keyboard).
    public static func iOSURL() throws -> URL {
        try TelemetryStore.iOSDatabaseURL().deletingLastPathComponent()
            .appendingPathComponent("vocabulary.json")
    }
}
