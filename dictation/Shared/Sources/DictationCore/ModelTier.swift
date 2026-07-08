public enum ModelTier: String, CaseIterable, Sendable {
    case tinyEn       = "openai_whisper-tiny.en"
    case baseEn       = "openai_whisper-base.en"
    case smallEn      = "openai_whisper-small.en"
    case small        = "openai_whisper-small"
    case largeV3Turbo = "openai_whisper-large-v3_turbo_954MB"
    case largeV3      = "openai_whisper-large-v3"

    /// The default WhisperKit model — the multilingual BACKUP to Apple Speech. `small` (multilingual)
    /// is the ship choice: fast on-device, Indic-capable (Hinglish/Gujarati), far lighter than
    /// large-v3-turbo (954 MB) whose batch-at-stop latency was ~4.6 s. One source of truth for the
    /// whisper default — referenced by AppState, SettingsView, and the STT config default.
    public static var defaultWhisper: ModelTier { .small }

    public var displayName: String {
        switch self {
        case .tinyEn:       return "Tiny (English, fastest)"
        case .baseEn:       return "Base (English)"
        case .smallEn:      return "Small (English, fast)"
        case .small:        return "Small (multilingual)"
        case .largeV3Turbo: return "Large Turbo (multilingual, balanced)"
        case .largeV3:      return "Large v3 (multilingual, best)"
        }
    }
}
