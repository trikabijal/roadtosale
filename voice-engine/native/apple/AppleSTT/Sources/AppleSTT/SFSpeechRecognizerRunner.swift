//
//  SFSpeechRecognizerRunner.swift
//
//  Legacy Speech framework path. Used for the vocab-biasing strategy because
//  the new SpeechTranscriber does NOT support custom vocabulary (see
//  dev/docs/ROAD_TO_SALE_AUDIO_RESEARCH_APPLE.md section 4).
//
//  Configures SFSpeechURLRecognitionRequest with:
//    - shouldReportPartialResults = true   (so we emit partials + finals)
//    - requiresOnDeviceRecognition = true  (no cloud)
//    - contextualStrings = [dealership terms]
//
//  SFTranscriptionSegment exposes a per-segment Float confidence (0...1),
//  which we surface as the event-level confidence by averaging across
//  segments of the current best transcription.

import Foundation
import AVFoundation
import Speech

final class SFSpeechRecognizerRunner {
    private let locale: Locale
    private let enablePartials: Bool
    private let contextualStrings: [String]
    private let emitter: EventEmitter

    init(
        locale: Locale,
        enablePartials: Bool,
        contextualStrings: [String],
        emitter: EventEmitter
    ) {
        self.locale = locale
        self.enablePartials = enablePartials
        self.contextualStrings = contextualStrings
        self.emitter = emitter
    }

    func run(fileURL: URL) async throws {
        try await Self.ensureAuthorized()

        guard let recognizer = SFSpeechRecognizer(locale: locale) else {
            throw NSError(
                domain: "AppleSTT",
                code: 20,
                userInfo: [NSLocalizedDescriptionKey:
                    "SFSpeechRecognizer unavailable for locale \(locale.identifier)."]
            )
        }
        guard recognizer.isAvailable else {
            throw NSError(
                domain: "AppleSTT",
                code: 21,
                userInfo: [NSLocalizedDescriptionKey:
                    "SFSpeechRecognizer is currently unavailable."]
            )
        }
        // Prefer on-device. If unsupported on this device/OS, fall back to
        // the default behavior rather than failing the run.
        let request = SFSpeechURLRecognitionRequest(url: fileURL)
        request.shouldReportPartialResults = enablePartials
        if recognizer.supportsOnDeviceRecognition {
            request.requiresOnDeviceRecognition = true
        }
        if !contextualStrings.isEmpty {
            request.contextualStrings = contextualStrings
        }

        // Reset the wall-clock origin right before we kick off the request.
        emitter.resetAudioStart()

        // Bridge the delegate-style callback into async/await.
        let contextualStringsLocal = self.contextualStrings
        try await withCheckedThrowingContinuation { [emitter, enablePartials, locale] (cont: CheckedContinuation<Void, Error>) in
            var resumed = false
            let task = recognizer.recognitionTask(with: request) { result, error in
                if let error = error {
                    if !resumed {
                        resumed = true
                        cont.resume(throwing: error)
                    }
                    return
                }
                guard let result = result else { return }

                let best = result.bestTranscription
                let text = best.formattedString
                let isFinal = result.isFinal

                if !isFinal && !enablePartials { return }

                // Audio-relative timestamp: SFTranscriptionSegment.timestamp
                // is seconds from the start of the audio. Use the first
                // segment's timestamp; 0 if no segments yet.
                let audioRelMs: Int = {
                    if let first = best.segments.first {
                        return Int(first.timestamp * 1000.0)
                    }
                    return 0
                }()

                // Confidence: average non-zero segment confidences. Apple
                // returns 0.0 for non-final segments — those should NOT
                // pollute the partial event confidence, so we treat partial
                // confidence as nil and only publish it on finals.
                let confidence: Double? = {
                    guard isFinal else { return nil }
                    let nonZero = best.segments.map { Double($0.confidence) }.filter { $0 > 0 }
                    guard !nonZero.isEmpty else { return nil }
                    return nonZero.reduce(0, +) / Double(nonZero.count)
                }()

                emitter.emit(TranscriptEventOut(
                    type: isFinal ? .final : .partial,
                    text: text,
                    timestampMs: audioRelMs,
                    latencyMsFromAudioStart: emitter.latencyMsSinceAudioStart(),
                    confidence: confidence,
                    engineMetadata: [
                        "engine": "sfspeech_recognizer",
                        "is_volatile": !isFinal,
                        "locale": locale.identifier,
                        "on_device": recognizer.supportsOnDeviceRecognition,
                        "vocab_terms": contextualStringsLocal.count
                    ]
                ))

                if isFinal && !resumed {
                    resumed = true
                    cont.resume(returning: ())
                }
            }
            _ = task
        }
    }

    private static func ensureAuthorized() async throws {
        let status = await withCheckedContinuation { (cont: CheckedContinuation<SFSpeechRecognizerAuthorizationStatus, Never>) in
            SFSpeechRecognizer.requestAuthorization { status in
                cont.resume(returning: status)
            }
        }
        switch status {
        case .authorized:
            return
        case .denied:
            throw NSError(
                domain: "AppleSTT",
                code: 10,
                userInfo: [NSLocalizedDescriptionKey:
                    "Speech recognition authorization denied. " +
                    "Grant access in System Settings > Privacy & Security > Speech Recognition."]
            )
        case .restricted:
            throw NSError(
                domain: "AppleSTT",
                code: 11,
                userInfo: [NSLocalizedDescriptionKey:
                    "Speech recognition restricted on this device."]
            )
        case .notDetermined:
            throw NSError(
                domain: "AppleSTT",
                code: 12,
                userInfo: [NSLocalizedDescriptionKey:
                    "Speech recognition authorization not determined."]
            )
        @unknown default:
            throw NSError(
                domain: "AppleSTT",
                code: 13,
                userInfo: [NSLocalizedDescriptionKey:
                    "Unknown speech recognition authorization status."]
            )
        }
    }
}
