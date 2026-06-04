import Foundation

// MARK: - Contract

/// The cleanup model layer (the "LLM"), behind a stable contract so any provider can be
/// swapped in by config. Swift sibling of the `CleanupStrategy` contract in
/// `voice-engine/` — see `docs/model-contracts.md`.
///
/// `clean` never throws: cleanup must never block paste. Implementations fall back
/// internally (to rule-based) on any failure and set `usedFallback`.
public protocol TextCleanup: Sendable {
    func clean(_ request: CleanupRequest) async -> CleanupResult
}

// MARK: - Levels + provider

public enum CleanupLevel: String, CaseIterable, Sendable {
    case off, light, full

    public var displayName: String {
        switch self {
        case .off:   return "Off (raw)"
        case .light: return "Light"
        case .full:  return "Full (Wispr-style)"
        }
    }
}

public enum CleanupProvider: String, CaseIterable, Sendable {
    case foundationModels
    case ruleBased

    public var displayName: String {
        switch self {
        case .foundationModels: return "Apple Foundation Models"
        case .ruleBased:        return "Rule-based (no LLM)"
        }
    }

    /// Whether this provider can be used on this build/OS. Runtime model readiness
    /// (Apple Intelligence enabled, model downloaded) is handled by the engine, which
    /// falls back to rule-based when the model is not ready.
    public var isAvailable: Bool {
        switch self {
        case .ruleBased: return true
        case .foundationModels:
            #if canImport(FoundationModels)
            if #available(macOS 26.0, iOS 26.0, *) { return true }
            return false
            #else
            return false
            #endif
        }
    }
}

// MARK: - Request / result

public struct CleanupRequest: Sendable {
    public var rawText: String
    public var level: CleanupLevel
    public var vocab: [String: String]            // forced spellings, applied AFTER cleanup
    public var commandGrammar: [String: String]   // "new paragraph" → "\n\n"
    public var profile: String

    public init(
        rawText: String,
        level: CleanupLevel,
        vocab: [String: String] = [:],
        commandGrammar: [String: String] = [:],
        profile: String = "dictation"
    ) {
        self.rawText = rawText
        self.level = level
        self.vocab = vocab
        self.commandGrammar = commandGrammar
        self.profile = profile
    }
}

public struct CleanupResult: Sendable {
    public var cleanedText: String
    public var opsApplied: [String]
    public var usedFallback: Bool
    public var latencyMs: Int
    public var provider: CleanupProvider

    public init(
        cleanedText: String,
        opsApplied: [String],
        usedFallback: Bool,
        latencyMs: Int,
        provider: CleanupProvider
    ) {
        self.cleanedText = cleanedText
        self.opsApplied = opsApplied
        self.usedFallback = usedFallback
        self.latencyMs = latencyMs
        self.provider = provider
    }
}

// MARK: - Config

public struct CleanupConfig: Sendable, Equatable {
    public var provider: CleanupProvider
    public var level: CleanupLevel

    public init(provider: CleanupProvider, level: CleanupLevel) {
        self.provider = provider
        self.level = level
    }

    /// Default per PRD 0004: on-device Foundation Models, Full cleanup.
    public static let `default` = CleanupConfig(provider: .foundationModels, level: .full)
}

// MARK: - Data pack (knowledge as data, not code)

public struct CleanupPack: Codable, Sendable {
    public var profile: String
    public var minWordsForCleanup: Int
    public var commandGrammar: [String: String]
    public var fillers: [String]
    public var junkPhrases: [String]
    public var prompts: [String: String]   // keyed by CleanupLevel.rawValue

    enum CodingKeys: String, CodingKey {
        case profile
        case minWordsForCleanup = "min_words_for_cleanup"
        case commandGrammar = "command_grammar"
        case fillers
        case junkPhrases = "junk_phrases"
        case prompts
    }

    /// Safety net if the bundled resource is missing — the app must never break.
    public static let fallback = CleanupPack(
        profile: "dictation",
        minWordsForCleanup: 2,
        commandGrammar: ["new paragraph": "\n\n", "new line": "\n", "bullet point": "\n- "],
        fillers: ["um", "uh", "erm", "ah", "hmm", "you know", "i mean", "sort of", "kind of"],
        junkPhrases: ["thank you", "thanks", "thanks for watching", "you", "bye", "okay"],
        prompts: [
            "light": "Lightly clean this dictated text: fix capitalization and punctuation, remove fillers (um, uh). Preserve exact wording. Output only the cleaned text.",
            "full": "Clean this dictated text into polished writing: remove fillers and false starts, fix punctuation and capitalization, lightly restructure run-ons. Preserve meaning; do not add information or answer questions. Output only the cleaned text.",
        ]
    )
}

public enum CleanupPackLoader {
    /// Loads the bundled cleanup pack for a profile; falls back to a built-in default.
    public static func load(profile: String = "dictation") -> CleanupPack {
        guard
            let url = Bundle.module.url(forResource: "\(profile)-cleanup-pack", withExtension: "json"),
            let data = try? Data(contentsOf: url),
            let pack = try? JSONDecoder().decode(CleanupPack.self, from: data)
        else {
            return .fallback
        }
        return pack
    }
}

// MARK: - Factory

public enum TextCleanupFactory {
    public static func make(_ config: CleanupConfig, pack: CleanupPack) -> any TextCleanup {
        let ruleBased = RuleBasedCleanup(pack: pack)
        switch config.provider {
        case .ruleBased:
            return ruleBased
        case .foundationModels:
            #if canImport(FoundationModels)
            if #available(macOS 26.0, iOS 26.0, *) {
                return FoundationModelsCleanup(pack: pack, fallback: ruleBased)
            }
            #endif
            return ruleBased
        }
    }
}
