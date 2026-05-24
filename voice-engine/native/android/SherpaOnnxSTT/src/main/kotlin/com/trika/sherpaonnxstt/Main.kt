package com.trika.sherpaonnxstt

/**
 * SherpaOnnxSTT — CLI entry point.
 *
 * Usage:
 *   SherpaOnnxSTT --file <path> [--model <name>] [--partials <true|false>]
 *
 * Options:
 *   --file <path>           Required. WAV audio file to transcribe (16kHz mono preferred).
 *   --model <name>          Optional. Model identifier (default: whisper-tiny-en).
 *                           Only whisper-tiny-en is supported in this release.
 *   --partials <true|false> Optional. Emit simulated partial events (default: true).
 *                           sherpa-onnx OfflineRecognizer does not stream tokens;
 *                           partials are synthesised from the first word of each
 *                           segment for JSONL contract compatibility.
 *
 * Output:
 *   One JSON line per event on stdout.  Fields:
 *     type, text, timestamp_ms, latency_ms_from_audio_start,
 *     confidence, engine_metadata.
 *   Errors go to stderr.
 *
 * Exit codes:
 *   0  success
 *   1  generic / argument error
 *   30 audio file not found or unreadable
 *   50 model load failed (download error, ONNX init error, missing files)
 *
 * native library setup:
 *   The JVM must locate libsherpa-onnx-jni.dylib on System.loadLibrary lookup.
 *   Run via the build script (sherpa_onnx_build.sh) which sets
 *   -Djava.library.path automatically, or set the env var manually:
 *
 *     java -Djava.library.path=<dir-with-dylibs> -jar SherpaOnnxSTT.jar --file …
 *
 *   The build script extracts the native dylibs from the native-lib jar into
 *   build/native-libs/ at build time.
 */
fun main(argv: Array<String>) {
    val args = parseArgs(argv)

    if (args.help) {
        printUsage(System.err)
        System.exit(0)
    }

    val filePath = args.file ?: run {
        System.err.println("SherpaOnnxSTT error: --file is required (try --help)")
        System.exit(1)
        return
    }

    val runner = SherpaOnnxRunner(
        modelId = args.model,
        enablePartials = args.partials,
    )

    try {
        runner.run(filePath)
        System.exit(0)
    } catch (e: AudioFileNotFoundException) {
        System.err.println("SherpaOnnxSTT error: ${e.message}")
        System.exit(30)
    } catch (e: ModelLoadFailedException) {
        System.err.println("SherpaOnnxSTT error: ${e.message}")
        System.exit(50)
    } catch (e: Exception) {
        System.err.println("SherpaOnnxSTT error: ${e.message}")
        System.exit(1)
    }
}

// ---------------------------------------------------------------------------
// Argument parsing
// ---------------------------------------------------------------------------

data class CliArgs(
    val file: String? = null,
    val model: String = "whisper-tiny-en",
    val partials: Boolean = true,
    val mode: String = "vad",   // "vad" | "batch"
    val help: Boolean = false,
)

fun parseArgs(argv: Array<String>): CliArgs {
    var file: String? = null
    var model = "whisper-tiny-en"
    var partials = true
    var mode = "vad"
    var help = false

    var i = 0
    while (i < argv.size) {
        when (argv[i]) {
            "--file" -> {
                i++
                file = argv.getOrNull(i) ?: run {
                    System.err.println("SherpaOnnxSTT error: --file requires a path")
                    System.exit(1)
                    return CliArgs()
                }
            }
            "--model" -> {
                i++
                model = argv.getOrNull(i) ?: run {
                    System.err.println("SherpaOnnxSTT error: --model requires a name")
                    System.exit(1)
                    return CliArgs()
                }
            }
            "--partials" -> {
                i++
                val v = argv.getOrNull(i) ?: run {
                    System.err.println("SherpaOnnxSTT error: --partials requires true|false")
                    System.exit(1)
                    return CliArgs()
                }
                partials = v.lowercase() == "true"
            }
            "--mode" -> {
                i++
                val v = argv.getOrNull(i) ?: run {
                    System.err.println("SherpaOnnxSTT error: --mode requires vad|batch")
                    System.exit(1)
                    return CliArgs()
                }
                if (v != "vad" && v != "batch") {
                    System.err.println("SherpaOnnxSTT error: --mode must be vad or batch, got: $v")
                    System.exit(1)
                }
                mode = v
            }
            "-h", "--help" -> help = true
            else -> {
                System.err.println("SherpaOnnxSTT error: unknown argument: ${argv[i]}")
                System.exit(1)
            }
        }
        i++
    }
    return CliArgs(file = file, model = model, partials = partials, mode = mode, help = help)
}

fun printUsage(out: java.io.PrintStream) {
    out.println("""
SherpaOnnxSTT — sherpa-onnx Whisper speech-to-text CLI

Required:
  --file <path>             Audio file to transcribe (WAV, 16kHz mono preferred).

Optional:
  --model <name>            Model identifier (default: whisper-tiny-en).
                            Model files are downloaded from GitHub on first run to
                            ~/.cache/sherpa-onnx/models/sherpa-onnx-whisper-tiny.en/
  --partials <true|false>   Emit simulated partial events (default: true).
                            Note: sherpa-onnx OfflineRecognizer does not stream
                            tokens; partials are synthesised from the first word
                            of the transcribed segment.

Output:
  One JSON event per line on stdout. Fields:
    type, text, timestamp_ms, latency_ms_from_audio_start,
    confidence, engine_metadata.
  engine_metadata.engine = "sherpa_onnx"

Exit codes:
  0  success
  1  generic / argument error
  30 audio file not found or unreadable
  50 model load failed (download, ONNX init, or missing files)

Native library:
  Requires libsherpa-onnx-jni.dylib on java.library.path.
  Use sherpa_onnx_build.sh which sets this up automatically.
    """.trimIndent())
}
