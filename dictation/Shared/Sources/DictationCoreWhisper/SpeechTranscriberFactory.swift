import DictationCoreBase

@MainActor
public enum SpeechTranscriberFactory {
    public static func make(_ config: STTConfig) -> any SpeechTranscriber {
        switch config.provider {
        case .whisperKit:
            let tier = ModelTier(rawValue: config.model) ?? .largeV3Turbo
            return WhisperKitTranscriber(modelTier: tier)
        case .appleSpeech:
            let language = config.model == "default" ? "en-US" : config.model
            return AppleSpeechTranscriber(language: language)
        case .mock:
            return MockTranscriber()
        }
    }
}
