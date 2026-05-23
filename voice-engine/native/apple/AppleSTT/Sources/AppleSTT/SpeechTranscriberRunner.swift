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

    /// Run the analyzer end-to-end on the file at `fileURL`. Returns when the
    /// audio is fully consumed and the results stream finishes.
    func run(fileURL: URL) async throws {
        // 1. Build the transcriber with the correct options.
        //    .volatileResults = emit partials as they arrive (aggressive path).
        //    .audioTimeRange  = include audio-relative timestamps per result.
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

        // 2. Ensure the on-device language model is downloaded.
        //    AssetInventory returns nil if the model is already present.
        //    On first run this downloads ~100MB; subsequent runs are instant.
        if let downloader = try await AssetInventory.assetInstallationRequest(supporting: [transcriber]) {
            try await downloader.downloadAndInstall()
        }

        // 3. Wire up the results consumer BEFORE starting the analyzer so
        //    no early events are lost.
        let localLocale = self.locale
        let enablePartialsLocal = self.enablePartials
        let emitterLocal = self.emitter

        let resultsTask = Task {
            for try await result in transcriber.results {
                let text = String(result.text.characters)
                let isFinal = result.isFinal

                if !isFinal && !enablePartialsLocal { continue }

                // Audio-relative timestamp from the result's CMTimeRange.
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

        // 4. Initialize the analyzer with the transcriber module and feed the file.
        //    analyzeSequence(from:) reads the file and returns when all audio
        //    is fed through. finalizeAndFinish(through:) seals any in-flight
        //    segments and drains the results stream.
        emitter.resetAudioStart()
        let audioFile = try AVAudioFile(forReading: fileURL)
        let analyzer = SpeechAnalyzer(modules: [transcriber])
        if let lastSample = try await analyzer.analyzeSequence(from: audioFile) {
            try await analyzer.finalizeAndFinish(through: lastSample)
        }

        // 5. Drain the results consumer.
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
