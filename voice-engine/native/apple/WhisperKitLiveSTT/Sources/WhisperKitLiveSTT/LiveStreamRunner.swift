//
//  LiveStreamRunner.swift
//
//  Streams live microphone audio through WhisperKit's AudioStreamTranscriber
//  and emits JSONL events on stdout, matching the WhisperKitSTT file-based
//  sibling's output schema exactly.
//
//  Mapping AudioStreamTranscriber.State -> our two-stream contract:
//
//    PARTIAL events — from State.unconfirmedSegments.
//      These are the trailing segments WhisperKit has decoded but not yet
//      confirmed (fewer than `requiredSegmentsForConfirmation` segments
//      have accumulated after them). They are re-emitted on every callback
//      and tagged `is_volatile: true`.
//
//    FINAL events — from newly added entries in State.confirmedSegments.
//      LocalAgreement-2 (requiredSegmentsForConfirmation = 2) seals a
//      segment once two later segments have accumulated, following
//      Macháček et al. (2023). We diff old vs new confirmedSegments on
//      every callback to emit each newly sealed segment exactly once,
//      tagged `is_volatile: false, confirmed: true`.
//
//  Confidence:
//    Same as WhisperKitRunner — `exp(avgLogprob)` clamped to [0, 1].
//
//  RunLoop pattern:
//    We use RunLoop.main.run() + exit() rather than DispatchGroup.wait()
//    to keep URLSession / CoreML / AVFoundation completion handlers
//    deliverable on the main thread. SIGINT is caught via a DispatchSource
//    that calls stopStreamTranscription() before exiting.

import Foundation
import WhisperKit

final class LiveStreamRunner {
    private let modelName: String
    private let enablePartials: Bool
    private let silenceThreshold: Float
    private let duration: Double?
    private let emitter: EventEmitter

    init(
        modelName: String,
        enablePartials: Bool,
        silenceThreshold: Float,
        duration: Double?,
        emitter: EventEmitter
    ) {
        self.modelName = modelName
        self.enablePartials = enablePartials
        self.silenceThreshold = silenceThreshold
        self.duration = duration
        self.emitter = emitter
    }

    /// Load WhisperKit, build the AudioStreamTranscriber, and begin streaming.
    /// This function returns only after transcription stops (SIGINT, duration
    /// elapsed, or error). Designed to be called from a Task on the main
    /// RunLoop — it does NOT block the RunLoop itself.
    func run() async throws {
        // 1. Load the model.
        let config = WhisperKitConfig(
            model: modelName,
            verbose: false,
            logLevel: .error,
            prewarm: false,
            load: true,
            download: true
        )

        let whisperKit: WhisperKit
        do {
            whisperKit = try await WhisperKit(config)
        } catch {
            throw NSError(
                domain: "WhisperKitLiveSTT",
                code: 50,
                userInfo: [NSLocalizedDescriptionKey:
                    "Failed to initialize WhisperKit with model '\(modelName)': " +
                    "\(error.localizedDescription). " +
                    "Confirm network is available on first run (model download)."]
            )
        }

        guard let tokenizer = whisperKit.tokenizer else {
            throw NSError(
                domain: "WhisperKitLiveSTT",
                code: 51,
                userInfo: [NSLocalizedDescriptionKey:
                    "WhisperKit tokenizer unavailable for model '\(modelName)'."]
            )
        }

        // 2. Build DecodingOptions. VAD chunking matches the file-based
        //    sibling's chunking strategy for consistent segment boundaries.
        let options = DecodingOptions(
            verbose: false,
            task: .transcribe,
            language: "en",
            temperature: 0.0,
            sampleLength: 224,
            usePrefillPrompt: true,
            skipSpecialTokens: true,
            withoutTimestamps: false,
            wordTimestamps: false,
            chunkingStrategy: .vad
        )

        // 3. Build the AudioStreamTranscriber.
        //
        //    requiredSegmentsForConfirmation = 2 implements LocalAgreement-2,
        //    the canonical simultaneous-interpretation confirmation strategy
        //    from Macháček et al. (2023). A segment is sealed once two later
        //    segments have been decoded after it.
        //
        //    The stateChangeCallback receives (oldState, newState). We capture
        //    the previously confirmed count to emit only newly sealed segments.

        let emitterRef = self.emitter
        let modelNameRef = self.modelName
        let enablePartialsRef = self.enablePartials

        // Track how many confirmedSegments we have already emitted so we
        // can diff on each callback without a mutable class wrapper.
        // Actor isolation: the callback fires from the actor's executor, so
        // it is already serialized — a plain var in a local sendable capture
        // is safe here.
        final class ConfirmationTracker: @unchecked Sendable {
            var emittedConfirmedCount: Int = 0
        }
        let tracker = ConfirmationTracker()

        let transcriber = AudioStreamTranscriber(
            audioEncoder: whisperKit.audioEncoder,
            featureExtractor: whisperKit.featureExtractor,
            segmentSeeker: whisperKit.segmentSeeker,
            textDecoder: whisperKit.textDecoder,
            tokenizer: tokenizer,
            audioProcessor: whisperKit.audioProcessor,
            decodingOptions: options,
            requiredSegmentsForConfirmation: 2,
            silenceThreshold: silenceThreshold,
            compressionCheckWindow: 60,
            useVAD: true
        ) { _, newState in
            // FINAL: emit any newly confirmed segments since last callback.
            let allConfirmed = newState.confirmedSegments
            let alreadyEmitted = tracker.emittedConfirmedCount
            if allConfirmed.count > alreadyEmitted {
                let newlyConfirmed = allConfirmed[alreadyEmitted...]
                for seg in newlyConfirmed {
                    let text = seg.text.trimmingCharacters(in: .whitespacesAndNewlines)
                    if text.isEmpty { continue }
                    let audioRelMs = Int(seg.start * 1000.0)
                    let confidence = Self.convertLogprobToConfidence(seg.avgLogprob)
                    emitterRef.emit(TranscriptEventOut(
                        type: .final,
                        text: text,
                        timestampMs: audioRelMs,
                        latencyMsFromAudioStart: emitterRef.latencyMsSinceAudioStart(),
                        confidence: confidence,
                        engineMetadata: [
                            "engine": "whisperkit_live",
                            "model": modelNameRef,
                            "is_volatile": false,
                            "confirmed": true,
                            "segment_start_s": Double(seg.start),
                            "segment_end_s": Double(seg.end),
                            "avg_logprob": Double(seg.avgLogprob),
                            "no_speech_prob": Double(seg.noSpeechProb),
                            "compression_ratio": Double(seg.compressionRatio)
                        ]
                    ))
                }
                tracker.emittedConfirmedCount = allConfirmed.count
            }

            // PARTIAL: emit current unconfirmed segments (volatile).
            guard enablePartialsRef else { return }
            for seg in newState.unconfirmedSegments {
                let text = seg.text.trimmingCharacters(in: .whitespacesAndNewlines)
                if text.isEmpty { continue }
                let audioRelMs = Int(seg.start * 1000.0)
                let confidence = Self.convertLogprobToConfidence(seg.avgLogprob)
                emitterRef.emit(TranscriptEventOut(
                    type: .partial,
                    text: text,
                    timestampMs: audioRelMs,
                    latencyMsFromAudioStart: emitterRef.latencyMsSinceAudioStart(),
                    confidence: confidence,
                    engineMetadata: [
                        "engine": "whisperkit_live",
                        "model": modelNameRef,
                        "is_volatile": true,
                        "segment_start_s": Double(seg.start),
                        "segment_end_s": Double(seg.end),
                        "avg_logprob": Double(seg.avgLogprob)
                    ]
                ))
            }
        }

        // 4. Install SIGINT handler. When Ctrl+C arrives, stop the
        //    transcriber cleanly before exit so AVAudioSession is released.
        let sigintSource = DispatchSource.makeSignalSource(
            signal: SIGINT,
            queue: .main
        )
        signal(SIGINT, SIG_IGN) // Hand off to DispatchSource
        sigintSource.setEventHandler {
            Task {
                await transcriber.stopStreamTranscription()
                exit(0)
            }
        }
        sigintSource.resume()

        // 5. If --duration was supplied, schedule a timer to stop after N
        //    seconds. This is used by the lab test harness so runs don't
        //    hang waiting for Ctrl+C.
        if let duration = duration {
            DispatchQueue.main.asyncAfter(deadline: .now() + duration) {
                Task {
                    await transcriber.stopStreamTranscription()
                    exit(0)
                }
            }
        }

        // 6. Set wall-clock origin and start streaming.
        emitter.resetAudioStart()

        do {
            try await transcriber.startStreamTranscription()
        } catch {
            throw NSError(
                domain: "WhisperKitLiveSTT",
                code: 52,
                userInfo: [NSLocalizedDescriptionKey:
                    "AudioStreamTranscriber failed: \(error.localizedDescription)"]
            )
        }
    }

    // MARK: - Helpers

    /// Convert a Whisper-style average log-probability to a [0, 1] confidence.
    /// Matches WhisperKitRunner.convertLogprobToConfidence exactly.
    static func convertLogprobToConfidence(_ avgLogprob: Float) -> Double {
        let p = exp(Double(avgLogprob))
        if !p.isFinite { return 0.0 }
        return max(0.0, min(1.0, p))
    }
}
