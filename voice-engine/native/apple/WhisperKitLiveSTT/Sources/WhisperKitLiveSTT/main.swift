//
//  main.swift
//
//  WhisperKitLiveSTT command-line entry point. Streams from the microphone
//  using WhisperKit's AudioStreamTranscriber and emits JSONL on stdout.
//
//  Usage:
//    WhisperKitLiveSTT [--model <name>]
//                      [--duration <seconds>]
//                      [--silence-threshold <float>]
//                      [--partials <true|false>]
//
//  Output:
//    One JSON line per event on stdout, identical schema to WhisperKitSTT:
//      type, text, timestamp_ms, latency_ms_from_audio_start,
//      confidence, engine_metadata.
//
//  Exit codes:
//    0   success (SIGINT or --duration elapsed)
//    1   generic / argument error
//    50  model load failed (often: no network on first run)
//    51  tokenizer unavailable for selected model
//    52  transcription failed mid-run
//
//  NOTE: Do NOT use DispatchGroup.wait() — it blocks the main thread,
//  preventing URLSession callbacks (model download) and CoreML completion
//  handlers from being delivered. Spin a Task, keep RunLoop alive, exit()
//  from within the Task (same pattern as WhisperKitSTT and AppleSTT).

import Foundation

struct CLIArgs {
    var model: String = "openai_whisper-tiny.en"
    var duration: Double? = nil
    var silenceThreshold: Float = 0.3
    var partials: Bool = true
}

func parseArgs(_ argv: [String]) -> CLIArgs {
    var args = CLIArgs()
    var i = 1
    while i < argv.count {
        let arg = argv[i]
        let next: String? = (i + 1 < argv.count) ? argv[i + 1] : nil
        switch arg {
        case "--model":
            guard let v = next else { fail("--model requires a name") }
            args.model = v
            i += 2
        case "--duration":
            guard let v = next, let d = Double(v), d > 0 else {
                fail("--duration requires a positive number of seconds")
            }
            args.duration = d
            i += 2
        case "--silence-threshold":
            guard let v = next, let f = Float(v) else {
                fail("--silence-threshold requires a float (e.g. 0.3)")
            }
            args.silenceThreshold = f
            i += 2
        case "--partials":
            guard let v = next else { fail("--partials requires true|false") }
            args.partials = (v.lowercased() == "true")
            i += 2
        case "-h", "--help":
            printUsage()
            exit(0)
        default:
            fail("unknown argument: \(arg)")
        }
    }
    return args
}

func printUsage() {
    let usage = """
    WhisperKitLiveSTT — WhisperKit live microphone speech-to-text CLI

    Optional:
      --model <name>               WhisperKit model name (default: openai_whisper-tiny.en).
                                   See https://huggingface.co/argmaxinc/whisperkit-coreml
                                   for the full list. Models are downloaded on first
                                   use into WhisperKit's cache directory.
      --duration <seconds>         Stop after this many seconds (default: run until SIGINT).
                                   Useful for lab testing (e.g. --duration 30).
      --silence-threshold <float>  Energy VAD silence threshold (default: 0.3).
                                   Lower = more sensitive; 0.3 is WhisperKit's default
                                   and matches the IPU energy literature consensus.
      --partials <true|false>      Default: true (emit volatile partial transcripts from
                                   unconfirmedSegments).

    Output:
      One JSON event per line on stdout. Fields:
        type, text, timestamp_ms, latency_ms_from_audio_start,
        confidence, engine_metadata.

      Partial (is_volatile: true)  — from AudioStreamTranscriber.State.unconfirmedSegments
      Final   (is_volatile: false) — from newly confirmed segments (LocalAgreement-2)

    Exit codes:
      0  success (SIGINT or --duration elapsed)
      1  generic / argument error
      50 model load failed (often: no network on first run)
      51 tokenizer unavailable for selected model
      52 transcription failed mid-run
    """
    FileHandle.standardError.write(Data((usage + "\n").utf8))
}

// MARK: - Entry

let argv = CommandLine.arguments
let args = parseArgs(argv)

let emitter = EventEmitter()

let runner = LiveStreamRunner(
    modelName: args.model,
    enablePartials: args.partials,
    silenceThreshold: args.silenceThreshold,
    duration: args.duration,
    emitter: emitter
)

Task {
    do {
        try await runner.run()
        exit(0)
    } catch let error as NSError {
        let code = Int32(error.code)
        let mappedCode: Int32
        switch code {
        case 50, 51, 52: mappedCode = code
        default: mappedCode = 1
        }
        fail(error.localizedDescription, code: mappedCode)
    } catch {
        fail("WhisperKitLiveSTT failed: \(error.localizedDescription)")
    }
}

RunLoop.main.run()
