//
//  main.swift
//
//  AppleSTT command-line entry point. Invoked by the Python lab as a
//  subprocess. Parses flags, dispatches to one of the two runners,
//  exits 0 on success or non-zero with a stderr error on failure.
//
//  Usage:
//    AppleSTT --file <path> --mode <speech_transcriber|sfspeech_recognizer>
//             [--locale <BCP-47>] [--partials <true|false>] [--vocab <path>]

import Foundation

struct CLIArgs {
    var file: String?
    var mode: String?
    var locale: String = "en-US"
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
        case "--mode":
            guard let v = next else { fail("--mode requires a value") }
            args.mode = v
            i += 2
        case "--locale":
            guard let v = next else { fail("--locale requires a BCP-47 tag") }
            args.locale = v
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
    AppleSTT — Apple on-device speech-to-text CLI

    Required:
      --file <path>                       Audio file to transcribe
      --mode <speech_transcriber|sfspeech_recognizer>

    Optional:
      --locale <BCP-47>                   Default: en-US
      --partials <true|false>             Default: true (emit volatile partials)
      --vocab <path>                      sfspeech_recognizer only: newline-
                                          delimited dealership terms file used
                                          as contextualStrings biasing.

    Output:
      One JSON event per line on stdout. Fields:
        type, text, timestamp_ms, latency_ms_from_audio_start,
        confidence, engine_metadata.

    Exit codes:
      0 success
      1 generic / argument error
      10-13 speech authorization errors
      20-21 SFSpeechRecognizer unavailable
      30 audio file unreadable
      40 SpeechTranscriber unavailable on this OS (requires macOS 26+)
    """
    FileHandle.standardError.write(Data((usage + "\n").utf8))
}

func loadVocabFile(_ path: String) -> [String] {
    let url = URL(fileURLWithPath: path)
    guard let text = try? String(contentsOf: url, encoding: .utf8) else {
        fail("could not read vocab file: \(path)", code: 30)
    }
    return text
        .split(whereSeparator: { $0.isNewline })
        .map { String($0).trimmingCharacters(in: .whitespaces) }
        .filter { !$0.isEmpty && !$0.hasPrefix("#") }
}

// MARK: - Entry

let argv = CommandLine.arguments
let args = parseArgs(argv)

guard let filePath = args.file else {
    fail("--file is required (try --help)")
}
guard let mode = args.mode else {
    fail("--mode is required (try --help)")
}

let fileURL = URL(fileURLWithPath: filePath)
if !FileManager.default.fileExists(atPath: filePath) {
    fail("audio file not found: \(filePath)", code: 30)
}

let locale = Locale(identifier: args.locale)
let emitter = EventEmitter()

// NOTE: We must NOT use DispatchGroup.wait() here — it blocks the main
// thread, which prevents SFSpeechRecognizer.requestAuthorization (and
// SpeechAnalyzer) from delivering their callbacks, causing a deadlock.
//
// Instead: spin Task on the cooperative pool, keep the main RunLoop alive
// with RunLoop.main.run(), and exit() from within the Task when done.
// The run loop delivers TCC callbacks and framework events on the main thread.

switch mode {
case "speech_transcriber":
    if #available(macOS 26.0, iOS 26.0, *) {
        let runner = SpeechTranscriberRunner(
            locale: locale,
            enablePartials: args.partials,
            emitter: emitter
        )
        Task {
            do {
                try await runner.run(fileURL: fileURL)
                exit(0)
            } catch {
                fail("speech_transcriber failed: \(error.localizedDescription)")
            }
        }
    } else {
        fail(
            "SpeechTranscriber requires macOS 26 (Tahoe) / iOS 26 or newer. " +
            "Use --mode sfspeech_recognizer on older systems.",
            code: 40
        )
    }

case "sfspeech_recognizer":
    let vocab: [String] = {
        if let path = args.vocab {
            return loadVocabFile(path)
        }
        return []
    }()
    let runner = SFSpeechRecognizerRunner(
        locale: locale,
        enablePartials: args.partials,
        contextualStrings: vocab,
        emitter: emitter
    )
    Task {
        do {
            try await runner.run(fileURL: fileURL)
            exit(0)
        } catch {
            fail("sfspeech_recognizer failed: \(error.localizedDescription)")
        }
    }

default:
    fail("unknown --mode value: \(mode) (expected speech_transcriber|sfspeech_recognizer)")
}

// Keep the main run loop alive so TCC callbacks and framework events can
// be delivered on the main thread. The Task above calls exit() when done.
RunLoop.main.run()
