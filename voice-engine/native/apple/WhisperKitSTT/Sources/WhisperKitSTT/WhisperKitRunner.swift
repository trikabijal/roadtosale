//
//  WhisperKitRunner.swift
//
//  Wraps Argmax's open-source WhisperKit (https://github.com/argmaxinc/WhisperKit,
//  MIT) and emits the voice-engine lab's JSONL event format.
//
//  Mapping WhisperKit -> our two-stream contract:
//
//    - PARTIAL events come from WhisperKit's TranscriptionCallback, which
//      fires repeatedly during decoding with the in-flight TranscriptionProgress
//      (text + avgLogprob + windowId). Per-token text is unstable until the
//      segment is sealed, so we mark these `partial` and tag
//      `is_volatile: true`.
//
//    - FINAL events come from WhisperKit's SegmentDiscoveryCallback, which
//      fires once per TranscriptionSegment as it is sealed by the segment
//      seeker (start, end, text, avgLogprob, words?). These are the stable
//      audit-grade transcripts.
//
//  Custom vocabulary:
//
//    WhisperKit (open source) does not have a dedicated "custom vocabulary"
//    API like the Argmax Pro SDK. The closest free-tier hook is the
//    decoder prompt — `DecodingOptions.promptTokens` accepts a sequence
//    of token IDs that get prefilled before generation, biasing the
//    decoder toward the prompt's lexicon. We tokenize the user-supplied
//    `--vocab` lines (one term per line) with the loaded WhisperTokenizer
//    and pass them through as promptTokens. This is a soft bias, not a
//    grammar / hotword boost — see README.md for the trade-off.
//
//  Confidence:
//
//    WhisperKit exposes `avgLogprob` per segment (log-domain mean of the
//    chosen tokens' log-probabilities). We expose confidence as
//    `exp(avgLogprob)` clamped to [0.0, 1.0]. avgLogprob is non-positive
//    in practice, so `exp(.)` lands in (0, 1]. Documented choice; see
//    `convertLogprobToConfidence` below.

import Foundation
import WhisperKit

final class WhisperKitRunner {
    private let modelName: String
    private let enablePartials: Bool
    private let vocabPath: String?
    private let emitter: EventEmitter

    init(
        modelName: String,
        enablePartials: Bool,
        vocabPath: String?,
        emitter: EventEmitter
    ) {
        self.modelName = modelName
        self.enablePartials = enablePartials
        self.vocabPath = vocabPath
        self.emitter = emitter
    }

    /// Run WhisperKit end-to-end on the file at `url`. Returns when the
    /// audio is fully consumed and all segments have been emitted.
    func run(filePath: String) async throws {
        // 1. Load the model. First-run downloads from HuggingFace
        //    (~argmaxinc/whisperkit-coreml) into the WhisperKit download
        //    cache; subsequent runs are offline.
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
                domain: "WhisperKitSTT",
                code: 50,
                userInfo: [NSLocalizedDescriptionKey:
                    "Failed to initialize WhisperKit with model '\(modelName)': " +
                    "\(error.localizedDescription). " +
                    "Confirm network is available on first run (model download), or " +
                    "pre-download via `argmax-cli download-model MODEL=\(modelName)`."]
            )
        }

        guard let tokenizer = whisperKit.tokenizer else {
            throw NSError(
                domain: "WhisperKitSTT",
                code: 51,
                userInfo: [NSLocalizedDescriptionKey:
                    "WhisperKit tokenizer unavailable for model '\(modelName)'."]
            )
        }

        // 2. Build DecodingOptions. promptTokens carries the custom vocab
        //    (if any) as a soft decoder bias.
        var options = DecodingOptions(
            verbose: false,
            task: .transcribe,
            language: "en",
            temperature: 0.0,
            sampleLength: 224,
            usePrefillPrompt: true,
            skipSpecialTokens: true,
            withoutTimestamps: false,
            wordTimestamps: false,
            // NOTE: chunkingStrategy: .vad causes SIGSEGV on certain audio files
            // in WhisperKit 0.18.0 (batch/file path only). AudioStreamTranscriber
            // uses its own rolling-buffer VAD which is stable for live streaming.
            // The file-based CLI uses no chunking strategy until the upstream
            // VAD chunker bug is fixed. Track: github.com/argmaxinc/WhisperKit
            chunkingStrategy: ChunkingStrategy.none
        )

        if let vocabPath = vocabPath {
            let terms = try loadVocabTerms(path: vocabPath)
            if !terms.isEmpty {
                // Join with commas — WhisperKit's CLI does the same when
                // passing a `--prompt` string. We tokenize the joined string
                // to a list of token IDs, then strip any special tokens.
                let promptString = " " + terms.joined(separator: ", ")
                let promptTokens = tokenizer
                    .encode(text: promptString)
                    .filter { $0 < tokenizer.specialTokens.specialTokenBegin }
                options.promptTokens = promptTokens
            }
        }

        // 3. Wire callbacks BEFORE kicking off transcribe(), so we don't
        //    miss any early signal.
        let emitterLocal = self.emitter
        let modelNameLocal = self.modelName
        let enablePartialsLocal = self.enablePartials

        if enablePartials {
            // PARTIAL stream: TranscriptionCallback fires with in-flight
            // TranscriptionProgress. Empty text is filtered.
            //
            // The callback returns Bool? — `false` aborts decoding; we
            // always return nil to let WhisperKit run to completion.
            whisperKit.transcriptionStateCallback = nil
        }

        // FINAL stream: segmentDiscoveryCallback fires once per sealed
        // segment. We translate to JSONL `final`.
        whisperKit.segmentDiscoveryCallback = { segments in
            for seg in segments {
                let text = seg.text.trimmingCharacters(in: .whitespacesAndNewlines)
                if text.isEmpty { continue }
                let audioRelMs = Int(seg.start * 1000.0)
                let confidence = Self.convertLogprobToConfidence(seg.avgLogprob)
                emitterLocal.emit(TranscriptEventOut(
                    type: .final,
                    text: text,
                    timestampMs: audioRelMs,
                    latencyMsFromAudioStart: emitterLocal.latencyMsSinceAudioStart(),
                    confidence: confidence,
                    engineMetadata: [
                        "engine": "whisperkit",
                        "model": modelNameLocal,
                        "is_volatile": false,
                        "segment_start_s": Double(seg.start),
                        "segment_end_s": Double(seg.end),
                        "avg_logprob": Double(seg.avgLogprob),
                        "no_speech_prob": Double(seg.noSpeechProb),
                        "compression_ratio": Double(seg.compressionRatio)
                    ]
                ))
            }
        }

        // PARTIAL stream as a TranscriptionCallback. We dedupe by text
        // and windowId so we don't emit duplicate identical partials when
        // the decoder is stable.
        var lastPartialKey: String = ""
        let partialCallback: TranscriptionCallback = { progress in
            guard enablePartialsLocal else { return nil }
            let text = progress.text.trimmingCharacters(in: .whitespacesAndNewlines)
            if text.isEmpty { return nil }
            let key = "\(progress.windowId)::\(text)"
            if key == lastPartialKey { return nil }
            lastPartialKey = key

            // Audio-relative timestamp for partials: WhisperKit's
            // TranscriptionProgress does not carry a segment start time
            // directly, so we use the latency-from-audio-start as a
            // best-effort surrogate. The lab can recompute audio-relative
            // timing from finals' segment_start_s if needed.
            let audioRelMs = emitterLocal.latencyMsSinceAudioStart()
            let confidence: Double? = {
                guard let lp = progress.avgLogprob else { return nil }
                return Self.convertLogprobToConfidence(lp)
            }()
            emitterLocal.emit(TranscriptEventOut(
                type: .partial,
                text: text,
                timestampMs: audioRelMs,
                latencyMsFromAudioStart: emitterLocal.latencyMsSinceAudioStart(),
                confidence: confidence,
                engineMetadata: [
                    "engine": "whisperkit",
                    "model": modelNameLocal,
                    "is_volatile": true,
                    "window_id": progress.windowId,
                    "avg_logprob": progress.avgLogprob.map { Double($0) } as Any
                ]
            ))
            return nil
        }

        // 4. Reset wall-clock origin just before kicking off transcribe.
        emitter.resetAudioStart()

        // 5. Run transcription. The path-based overload handles audio
        //    loading and chunking internally; we just consume callbacks.
        do {
            _ = try await whisperKit.transcribe(
                audioPath: filePath,
                decodeOptions: options,
                callback: partialCallback
            )
        } catch {
            throw NSError(
                domain: "WhisperKitSTT",
                code: 52,
                userInfo: [NSLocalizedDescriptionKey:
                    "WhisperKit transcription failed: \(error.localizedDescription)"]
            )
        }
    }

    // MARK: - Helpers

    /// Convert a Whisper-style average log-probability to a [0, 1]
    /// confidence. avgLogprob is the natural-log mean of per-token
    /// probabilities the decoder assigned to its chosen tokens; it is
    /// non-positive (perfect = 0, low confidence = very negative). exp(.)
    /// inverts that into a probability-like scalar in (0, 1]. We clamp
    /// defensively to [0, 1] to absorb any numerical edge cases.
    static func convertLogprobToConfidence(_ avgLogprob: Float) -> Double {
        let p = exp(Double(avgLogprob))
        if !p.isFinite { return 0.0 }
        return max(0.0, min(1.0, p))
    }

    /// Load newline-delimited vocab terms from a file. Blank lines and
    /// lines starting with `#` are ignored.
    private func loadVocabTerms(path: String) throws -> [String] {
        let url = URL(fileURLWithPath: path)
        let text: String
        do {
            text = try String(contentsOf: url, encoding: .utf8)
        } catch {
            throw NSError(
                domain: "WhisperKitSTT",
                code: 30,
                userInfo: [NSLocalizedDescriptionKey:
                    "Could not read vocab file: \(path) (\(error.localizedDescription))"]
            )
        }
        return text
            .split(whereSeparator: { $0.isNewline })
            .map { String($0).trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty && !$0.hasPrefix("#") }
    }
}
