//
//  main.swift
//
//  WhisperKitSTT command-line entry point. Invoked by the Python lab as a
//  subprocess. Parses flags, dispatches to WhisperKitRunner, exits 0 on
//  success or non-zero with a stderr error on failure.
//
//  Usage:
//    WhisperKitSTT --file <path>
//                  [--model <name>]
//                  [--partials <true|false>]
//                  [--vocab <path>]
//
//  Models are named per the argmaxinc/whisperkit-coreml HuggingFace
//  repo. The default below is the smallest English-only model that
//  WhisperKit knows how to download — `openai_whisper-tiny.en`.

import Foundation

struct CLIArgs {
    var file: String?
    var model: String = "openai_whisper-tiny.en"
    var partials: Bool = true
    var vocab: String?
}

func parseArgs(_ argv: [String]) -> CLIArgs {
    var args = CLIArgs()
    var i = 1
    while i < argv.count {
        let arg = argv[i]
        let next: String? = (i + 1 < argv.count) ? argv[i + 1] : nil
        switch arg {
        case "--file":
            guard let v = next else { fail("--file requires a path") }
            args.file = v
            i += 2
        case "--model":
            guard let v = next else { fail("--model requires a name") }
            args.model = v
            i += 2
        case "--partials":
            guard let v = next else { fail("--partials requires true|false") }
            args.partials = (v.lowercased() == "true")
            i += 2
        case "--vocab":
            guard let v = next else { fail("--vocab requires a path") }
            args.vocab = v
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
    WhisperKitSTT — WhisperKit on-device speech-to-text CLI

    Required:
      --file <path>             Audio file to transcribe (wav/mp3/m4a/flac)

    Optional:
      --model <name>            WhisperKit model name (default: openai_whisper-tiny.en).
                                See https://huggingface.co/argmaxinc/whisperkit-coreml
                                for the full list. Models are downloaded on first
                                use into WhisperKit's cache directory.
      --partials <true|false>   Default: true (emit volatile partials)
      --vocab <path>            Newline-delimited dealership terms. Passed to
                                WhisperKit as decoder promptTokens (soft bias).

    Output:
      One JSON event per line on stdout. Fields:
        type, text, timestamp_ms, latency_ms_from_audio_start,
        confidence, engine_metadata.

    Exit codes:
      0  success
      1  generic / argument error
      30 input file unreadable
      50 model load failed (often: no network on first run)
      51 tokenizer unavailable for selected model
      52 transcription failed mid-run
    """
    FileHandle.standardError.write(Data((usage + "\n").utf8))
}

// MARK: - Entry

let argv = CommandLine.arguments
let args = parseArgs(argv)

guard let filePath = args.file else {
    fail("--file is required (try --help)")
}

if !FileManager.default.fileExists(atPath: filePath) {
    fail("audio file not found: \(filePath)", code: 30)
}

let emitter = EventEmitter()

let runner = WhisperKitRunner(
    modelName: args.model,
    enablePartials: args.partials,
    vocabPath: args.vocab,
    emitter: emitter
)

// NOTE: Do NOT use DispatchGroup.wait() — it blocks the main thread,
// which prevents URLSession callbacks (model download) and CoreML
// completion handlers from being delivered. Same fix as AppleSTT:
// spin the Task, keep RunLoop alive, exit() from within the Task.
Task {
    do {
        try await runner.run(filePath: filePath)
        exit(0)
    } catch let error as NSError {
        let code = Int32(error.code)
        let mappedCode: Int32
        switch code {
        case 30, 50, 51, 52: mappedCode = code
        default: mappedCode = 1
        }
        fail(error.localizedDescription, code: mappedCode)
    } catch {
        fail("WhisperKit transcription failed: \(error.localizedDescription)")
    }
}

RunLoop.main.run()
