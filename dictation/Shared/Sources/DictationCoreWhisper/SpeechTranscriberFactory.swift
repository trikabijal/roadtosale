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
            // Prefer Apple's SpeechAnalyzer (fast, streaming) on macOS/iOS 26; fall back to the
            // lightweight SFSpeechRecognizer (older OS + fits the keyboard's ~70 MB memory cap).
            if #available(macOS 26.0, iOS 26.0, *) {
                return AppleAnalyzerTranscriber(localeIdentifier: language)
            }
            return AppleSpeechTranscriber(language: language)
        case .mock:
            return MockTranscriber()
        }
    }
}
