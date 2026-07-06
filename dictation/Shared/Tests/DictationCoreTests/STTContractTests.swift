import XCTest
import AVFoundation
@testable import DictationCore

/// f3 — the STT provider contract: factory, provider gating, config, test doubles, and the
/// model-free `AppleAudioConverter`. No real model is loaded (that's Tier 3, model-gated).
@MainActor
final class STTContractTests: XCTestCase {

    // MARK: factory

    func testFactoryBuildsWhisperKit() {
        let t = SpeechTranscriberFactory.make(STTConfig(provider: .whisperKit, model: ModelTier.largeV3Turbo.rawValue))
        let wk = t as? WhisperKitTranscriber
        XCTAssertNotNil(wk)
        XCTAssertEqual(wk?.modelTier, .largeV3Turbo)
    }

    func testFactoryDefaultsTierOnBadModel() {
        let t = SpeechTranscriberFactory.make(STTConfig(provider: .whisperKit, model: "not-a-real-tier"))
        XCTAssertEqual((t as? WhisperKitTranscriber)?.modelTier, .largeV3Turbo)
    }

    func testFactoryBuildsMock() {
        XCTAssertTrue(SpeechTranscriberFactory.make(STTConfig(provider: .mock, model: "x")) is MockTranscriber)
    }

    /// Apple provider resolves per OS: the real impl on macOS 26+, else a graceful `UnavailableTranscriber`.
    func testFactoryApplePerOSAvailability() {
        let t = SpeechTranscriberFactory.make(STTConfig(provider: .appleSpeech, model: "en-US"))
        if #available(macOS 26.0, *) {
            XCTAssertTrue(t is AppleSpeechTranscriber)
        } else {
            XCTAssertTrue(t is UnavailableTranscriber)
        }
    }

    // MARK: provider / config

    func testProviderAvailability() {
        XCTAssertTrue(STTProvider.whisperKit.isAvailable)
        XCTAssertTrue(STTProvider.mock.isAvailable)
        if #available(macOS 26.0, *) {
            XCTAssertTrue(STTProvider.appleSpeech.isAvailable)
        } else {
            XCTAssertFalse(STTProvider.appleSpeech.isAvailable)
        }
    }

    func testSelectableExcludesMock() {
        XCTAssertEqual(STTProvider.selectable, [.whisperKit, .appleSpeech])
        XCTAssertFalse(STTProvider.selectable.contains(.mock))
    }

    func testConfigDefault() {
        XCTAssertEqual(STTConfig.default.provider, .whisperKit)
        XCTAssertEqual(STTConfig.default.model, ModelTier.largeV3Turbo.rawValue)
    }

    func testModelDisplayName() {
        let cfg = STTConfig(provider: .whisperKit, model: ModelTier.largeV3Turbo.rawValue)
        XCTAssertEqual(cfg.modelDisplayName, ModelTier.largeV3Turbo.displayName)
        XCTAssertEqual(STTConfig(provider: .appleSpeech, model: "en-US").modelDisplayName,
                       STTProvider.appleSpeech.displayName)
    }

    // MARK: test doubles

    func testMockTranscriberReturnsCanned() async throws {
        let mock = MockTranscriber(cannedText: "hello world")
        let r = try await mock.transcribe(buffers: [], audioStartDate: Date())
        XCTAssertEqual(r.text, "hello world")
        XCTAssertEqual(r.provider, .mock)
        XCTAssertEqual(r.confidence, 1.0, accuracy: 1e-9)
    }

    func testUnavailableTranscriberThrows() async {
        let u = UnavailableTranscriber(providerName: "Apple SpeechTranscriber")
        do {
            try await u.load()
            XCTFail("expected providerUnavailable")
        } catch let TranscriptionError.providerUnavailable(name) {
            XCTAssertEqual(name, "Apple SpeechTranscriber")
        } catch {
            XCTFail("wrong error: \(error)")
        }
    }

    // MARK: AppleAudioConverter (model-free; macOS 26 gated)

    func testAppleAudioConverterConvertsFormat() throws {
        guard #available(macOS 26.0, *) else {
            throw XCTSkip("AppleAudioConverter is macOS 26+")
        }
        // 16 kHz mono source (our capture format) → a 48 kHz mono target.
        let samples: [Float] = (0..<1600).map { Float(sin(Double($0) * 0.05)) }   // 0.1 s
        let input = try XCTUnwrap(AudioSampleBridge.makeBuffer(samples: samples, sampleRate: 16_000))
        let target = try XCTUnwrap(AVAudioFormat(standardFormatWithSampleRate: 48_000, channels: 1))

        let converter = AppleAudioConverter(target: target)
        let out = try XCTUnwrap(converter.convert(input))
        XCTAssertEqual(out.format.sampleRate, 48_000)
        XCTAssertGreaterThan(out.frameLength, 0)

        // Same-format input is returned unchanged (no needless conversion).
        let passthrough = AppleAudioConverter(target: input.format)
        XCTAssertEqual(passthrough.convert(input)?.format.sampleRate, input.format.sampleRate)
    }
}
