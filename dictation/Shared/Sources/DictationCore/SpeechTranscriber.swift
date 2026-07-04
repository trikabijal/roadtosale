import AVFoundation

// MARK: - Result (provider-agnostic)

public struct TranscriptionResult: Sendable {
    public let text: String
    public let confidence: Double      // 0.0–1.0
    public let audioDurationMs: Int
    public let latencyMs: Int
    public let provider: STTProvider
    public let model: String

    public init(
        text: String,
        confidence: Double,
        audioDurationMs: Int,
        latencyMs: Int,
        provider: STTProvider,
        model: String
    ) {
        self.text = text
        self.confidence = confidence
        self.audioDurationMs = audioDurationMs
        self.latencyMs = latencyMs
        self.provider = provider
        self.model = model
    }
}

// MARK: - Contract

/// The voice-understanding model, behind a stable contract so any provider can be
/// swapped in by config. This is the Swift (batch) sibling of the streaming
/// `TranscriptionStrategy` in `voice-engine/` — see `docs/model-contracts.md`.
@MainActor
public protocol SpeechTranscriber: AnyObject {
    var isLoaded: Bool { get }
    /// Downloads (first run) and loads the model. `onProgress` reports 0.0–1.0.
    func load(onProgress: (@MainActor (Double) -> Void)?) async throws
    func transcribe(buffers: [AVAudioPCMBuffer], audioStartDate: Date) async throws -> TranscriptionResult
    /// Optional custom-vocabulary biasing (names, jargon). Providers that don't support
    /// biasing ignore it. Default: no-op.
    func setVocabularyBias(_ terms: [String])
    /// Release the loaded model so orphaned/timed-out work can't keep holding resources; the
    /// next `transcribe` reloads. Default: no-op (only heavyweight engines need it).
    func reset()
    /// Vend a streaming session for the LIVE PILL (PRD 0008), reusing THIS transcriber's already-
    /// loaded model — no second model, no second mic. Returns `nil` when the provider can't stream
    /// (the caller then falls back to the per-segment preview). The accurate PASTED text always
    /// comes from `transcribe(buffers:)`, never from the streaming session. Default: `nil`.
    func makeStreamingSession() -> (any StreamingTranscriber)?
}

public extension SpeechTranscriber {
    func load() async throws { try await load(onProgress: nil) }
    func setVocabularyBias(_ terms: [String]) {}
    func reset() {}
    func makeStreamingSession() -> (any StreamingTranscriber)? { nil }
}

// MARK: - Provider + config

public enum STTProvider: String, CaseIterable, Sendable {
    case whisperKit
    case appleSpeech
    case mock

    public var displayName: String {
        switch self {
        case .whisperKit:  return "WhisperKit (on-device)"
        case .appleSpeech: return "Apple SpeechTranscriber"
        case .mock:        return "Mock (testing)"
        }
    }

    /// Whether the provider is wired up for real use yet.
    public var isAvailable: Bool {
        switch self {
        case .whisperKit, .mock: return true
        case .appleSpeech:       return false   // contract-ready, not yet implemented
        }
    }

    /// Providers offered in Settings (mock is test-only).
    public static var selectable: [STTProvider] { [.whisperKit, .appleSpeech] }
}

public struct STTConfig: Sendable, Equatable {
    public var provider: STTProvider
    public var model: String   // provider-specific id; for whisperKit = ModelTier.rawValue

    public init(provider: STTProvider, model: String) {
        self.provider = provider
        self.model = model
    }

    public static let `default` = STTConfig(
        provider: .whisperKit,
        model: ModelTier.largeV3Turbo.rawValue
    )

    /// Human-friendly model name for status messages.
    public var modelDisplayName: String {
        switch provider {
        case .whisperKit: return ModelTier(rawValue: model)?.displayName ?? model
        case .appleSpeech, .mock: return provider.displayName
        }
    }
}

// MARK: - Factory

@MainActor
public enum SpeechTranscriberFactory {
    public static func make(_ config: STTConfig) -> any SpeechTranscriber {
        switch config.provider {
        case .whisperKit:
            let tier = ModelTier(rawValue: config.model) ?? .largeV3Turbo
            return WhisperKitTranscriber(modelTier: tier)
        case .mock:
            return MockTranscriber()
        case .appleSpeech:
            return UnavailableTranscriber(providerName: STTProvider.appleSpeech.displayName)
        }
    }
}

// MARK: - Mock + Unavailable implementations

/// Deterministic transcriber for tests and pipeline wiring (no model download).
@MainActor
public final class MockTranscriber: SpeechTranscriber {
    public private(set) var isLoaded = false
    private let cannedText: String

    public init(cannedText: String = "mock transcript") {
        self.cannedText = cannedText
    }

    public func load(onProgress: (@MainActor (Double) -> Void)?) async throws {
        onProgress?(1.0)
        isLoaded = true
    }

    public func transcribe(buffers: [AVAudioPCMBuffer], audioStartDate: Date) async throws -> TranscriptionResult {
        let frames = buffers.reduce(0) { $0 + Int($1.frameLength) }
        let durationMs = Int(Double(frames) / RecordingEngine.targetSampleRate * 1000)
        return TranscriptionResult(
            text: cannedText,
            confidence: 1.0,
            audioDurationMs: durationMs,
            latencyMs: 0,
            provider: .mock,
            model: "mock"
        )
    }
}

/// Stand-in for a provider that exists in the contract but isn't implemented yet
/// (e.g. Apple SpeechTranscriber). Proves the contract is provider-agnostic; surfaces
/// a clear message rather than silently failing.
@MainActor
public final class UnavailableTranscriber: SpeechTranscriber {
    public let isLoaded = false
    private let providerName: String

    public init(providerName: String) { self.providerName = providerName }

    public func load(onProgress: (@MainActor (Double) -> Void)?) async throws {
        throw TranscriptionError.providerUnavailable(providerName)
    }

    public func transcribe(buffers: [AVAudioPCMBuffer], audioStartDate: Date) async throws -> TranscriptionResult {
        throw TranscriptionError.providerUnavailable(providerName)
    }
}

// MARK: - Errors

public enum TranscriptionError: Error, LocalizedError {
    case modelNotLoaded
    case noAudioData
    case emptyResult
    case providerUnavailable(String)

    public var errorDescription: String? {
        switch self {
        case .modelNotLoaded: return "Speech model is not loaded yet."
        case .noAudioData: return "No audio was recorded."
        case .emptyResult: return "Transcription returned no text."
        case .providerUnavailable(let name): return "\(name) is not available yet."
        }
    }
}
