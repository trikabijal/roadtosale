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

// MARK: - Vocabulary helpers (single source of truth for both platforms)

public enum Vocabulary {
    /// Always-on brand terms so the app spells its own name right even before the user adds anything.
    public static let brand = ["Just Talk", "Trika"]

    /// Dev seed for the personal daily-driver build (brand + tech + the people/terms in use). This bakes
    /// personal names into the binary — fine for the personal build; replace with Mac↔iOS vocabulary
    /// sync before shipping to other users.
    public static let seed = [
        "WhisperKit", "Wispr Flow", "Trika", "LLM", "HUD", "VAD", "IPC", "DevOps", "Darwin", "Claude",
        "PRD", "UAT", "OEM", "Flowable", "Bijal", "Deepali", "Meher", "Teena", "Tiez", "trika.ai",
        "EHR", "Just Talk", "Road To Sale", "Trisha", "Sanghavi", "Poddar",
    ]

    /// STT recognition bias = brand + user terms.
    public static func biasTerms(_ user: [String]) -> [String] { brand + user }

    /// Forced-spelling map for cleanup (term → itself), brand UNDER user so a user override wins.
    public static func spellingMap(_ user: [String]) -> [String: String] {
        var map = Dictionary(brand.map { ($0.lowercased(), $0) }, uniquingKeysWith: { _, b in b })
        for term in user { map[term.lowercased()] = term }
        return map
    }

    /// Seed the store the first time (idempotent — only when empty). Returns the resulting terms.
    @discardableResult
    public static func ensureSeeded(_ store: VocabularyStore) -> [String] {
        let current = store.load()
        if current.isEmpty { store.save(seed); return seed }
        return current
    }
}
