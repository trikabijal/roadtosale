//
//  SpeechTranscriberRunner.swift
//
//  Wraps the iOS 26 / macOS 26 (Tahoe) on-device speech stack:
//    SpeechAnalyzer  -- orchestrator (actor)
//      + SpeechTranscriber  -- long-form transcription module
//
//  - Enables volatile (interim) results and per-token audio time ranges.
//  - Uses the SpeechAnalyzer(inputAudioFile:...) convenience initializer to
//    stream an on-disk file end-to-end (Apple handles the buffer pump).
//  - Emits one JSONL line per result (partial or final) to stdout.
//
//  Confidence is intentionally null. SpeechTranscriber exposes per-token
//  attributes via AttributedString, but the scale (probability vs log-prob)
//  is undocumented (research brief OQ11) so we do NOT publish a number we
//  cannot defend.

import Foundation
import AVFoundation
import Speech

@available(macOS 26.0, iOS 26.0, *)
final class SpeechTranscriberRunner {
    private let locale: Locale
    private let enablePartials: Bool
    private let emitter: EventEmitter

    init(locale: Locale, enablePartials: Bool, emitter: EventEmitter) {
        self.locale = locale
        self.enablePartials = enablePartials
        self.emitter = emitter
    }

    /// Run the analyzer end-to-end on the file at `url`. Returns when the
    /// audio is fully consumed and the results stream finishes.
    func run(fileURL: URL) async throws {
        // 1. Authorization. SpeechTranscriber gates on the same Speech
        //    framework authorization as SFSpeechRecognizer.
        // NOTE: This may hang waiting for a TCC prompt. If it does, the process
        // will need to be terminated. The fix is to manually grant Speech Recognition
        // access in System Settings > Privacy & Security, then re-run.
        do {
            try await Self.ensureAuthorized()
        } catch let error as NSError where error.code >= 10 && error.code <= 13 {
            // Authorization error - surface with guidance
            let guidance = "\nTo fix:\n" +
                "  1. Open System Settings > Privacy & Security > Speech Recognition\n" +
                "  2. Grant access to 'AppleSTT' or 'Terminal' (depending on how you're invoking this)\n" +
                "  3. Re-run the command"
            throw NSError(
                domain: error.domain,
                code: error.code,
                userInfo: [NSLocalizedDescriptionKey: error.localizedDescription + guidance]
            )
        }

        // 2. Configure reporting + attribute sets.
        var reporting: Set<SpeechTranscriber.ReportingOption> = []
        if enablePartials {
            reporting.insert(.volatileResults)
        }
        let attributes: Set<SpeechTranscriber.ResultAttributeOption> = [.audioTimeRange]

        let transcriber = SpeechTranscriber(
            locale: locale,
            transcriptionOptions: [],
            reportingOptions: reporting,
            attributeOptions: attributes
        )

        // 3. Open the audio file. Apple handles streaming through its
        //    SpeechAnalyzer(inputAudioFile:...) initializer.
        let audioFile = try AVAudioFile(forReading: fileURL)

        let analyzer = try await SpeechAnalyzer(
            inputAudioFile: audioFile,
            modules: [transcriber],
            options: nil,
            analysisContext: AnalysisContext(),
            finishAfterFile: true,
            volatileRangeChangedHandler: nil
        )

        let localLocale = self.locale
        let enablePartialsLocal = self.enablePartials
        let emitterLocal = self.emitter

        // 4. Start a consumer task BEFORE we start the analyzer, so we do
        //    not lose any early results.
        let resultsTask = Task {
            for try await result in transcriber.results {
                let text = String(result.text.characters)
                let isFinal = result.isFinal

                if !isFinal && !enablePartialsLocal { continue }

                // Audio-relative timestamp from CMTimeRange.
                let audioRelMs = Int(result.range.start.seconds * 1000.0)

                emitterLocal.emit(TranscriptEventOut(
                    type: isFinal ? .final : .partial,
                    text: text,
                    timestampMs: audioRelMs,
                    latencyMsFromAudioStart: emitterLocal.latencyMsSinceAudioStart(),
                    confidence: nil,
                    engineMetadata: [
                        "engine": "speech_transcriber",
                        "is_volatile": !isFinal,
                        "locale": localLocale.identifier
                    ]
                ))
            }
        }

        // 5. Reset wall-clock origin right when we start the analyzer.
        emitter.resetAudioStart()

        // 6. Run the analyzer end-to-end against the input file.
        try await analyzer.start(inputAudioFile: audioFile, finishAfterFile: true)

        // 7. Make sure trailing finals are emitted.
        try await analyzer.finalizeAndFinishThroughEndOfInput()

        // 8. Drain the results consumer.
        _ = try await resultsTask.value
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
                    "Speech recognition authorization not determined. " +
                    "Re-run after the OS prompt resolves."]
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
