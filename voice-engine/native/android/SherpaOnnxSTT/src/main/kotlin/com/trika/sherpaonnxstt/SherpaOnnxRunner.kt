package com.trika.sherpaonnxstt

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.HomophoneReplacerConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.WaveReader
import java.io.File
import java.net.URI
import java.nio.channels.Channels
import java.nio.file.Files
import kotlin.io.path.Path

/**
 * SherpaOnnxRunner — wraps sherpa-onnx OfflineRecognizer for Whisper-tiny-en
 * file-based transcription, emitting JSONL events to stdout.
 *
 * Partials note:
 *   sherpa-onnx OfflineRecognizer does NOT stream intermediate tokens; it
 *   returns a single result per segment once decoding is complete. For the
 *   `--partials true` mode we therefore simulate ONE partial event per
 *   transcribed segment (first word as a preview) right before we emit the
 *   `final`. This keeps the JSONL contract compatible with the Python lab
 *   consumer which expects both event types, while being honest in the
 *   `engine_metadata` that it is simulated.
 *
 * Model layout expected by sherpa-onnx:
 *   $modelDir/tiny.en-encoder.int8.onnx
 *   $modelDir/tiny.en-decoder.int8.onnx
 *   $modelDir/tiny.en-tokens.txt
 *
 * where modelDir = ~/.cache/sherpa-onnx/models/sherpa-onnx-whisper-tiny.en/
 *
 * The runner downloads the model archive from GitHub on first run and
 * extracts it. Subsequent runs skip the download if the directory exists.
 */
class SherpaOnnxRunner(
    private val modelId: String = "whisper-tiny-en",
    private val enablePartials: Boolean = true,
) {

    companion object {
        private const val MODEL_CACHE_DIR = ".cache/sherpa-onnx/models"
        private const val MODEL_SUBDIR = "sherpa-onnx-whisper-tiny.en"
        private const val MODEL_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-tiny.en.tar.bz2"
        private const val ENGINE_NAME = "sherpa_onnx"
    }

    /**
     * Transcribe the WAV file at [filePath].
     * Emits JSONL events to stdout via [EventEmitter].
     *
     * Throws [RuntimeException] with a descriptive message on failure.
     * Exit codes are enforced by Main.kt.
     */
    fun run(filePath: String) {
        val audioFile = File(filePath)
        if (!audioFile.exists()) {
            throw AudioFileNotFoundException("audio file not found: $filePath")
        }

        // 1. Ensure model is present (download if needed).
        val modelDir = resolveModelDir()
        try {
            ensureModelDownloaded(modelDir)
        } catch (e: Exception) {
            throw ModelLoadFailedException(
                "Failed to load/download model '$modelId': ${e.message}. " +
                    "Check network access or manually download to $modelDir."
            )
        }

        // 2. Verify model files exist.
        val encoderPath = File(modelDir, "tiny.en-encoder.int8.onnx")
        val decoderPath = File(modelDir, "tiny.en-decoder.int8.onnx")
        val tokensPath  = File(modelDir, "tiny.en-tokens.txt")

        for (f in listOf(encoderPath, decoderPath, tokensPath)) {
            if (!f.exists()) {
                throw ModelLoadFailedException(
                    "Missing model file: ${f.absolutePath}. " +
                        "Remove $modelDir and re-run to re-download."
                )
            }
        }

        // 3. Build the recognizer config.
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
            modelConfig = OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = encoderPath.absolutePath,
                    decoder = decoderPath.absolutePath,
                    language = "en",
                    task = "transcribe",
                    tailPaddings = 1000,
                ),
                tokens = tokensPath.absolutePath,
                numThreads = 1,
                debug = false,
                provider = "cpu",
                modelType = "whisper",
            ),
            decodingMethod = "greedy_search",
        )

        // 4. Initialise recognizer (loads ONNX models — may take 1–2 s).
        val recognizer: OfflineRecognizer
        try {
            recognizer = OfflineRecognizer(config = config)
        } catch (e: Exception) {
            throw ModelLoadFailedException(
                "OfflineRecognizer init failed for model '$modelId': ${e.message}"
            )
        }

        // 5. Read audio samples from the WAV file.
        //    WaveReader.readWaveFromFile is a JNI native call that handles
        //    WAV parsing and converts to 32-bit float mono.
        val waveData = try {
            WaveReader.readWave(filePath)
        } catch (e: Exception) {
            recognizer.release()
            throw AudioFileNotFoundException(
                "Failed to read audio file '$filePath': ${e.message}"
            )
        }

        // 6. Stamp wall-clock origin AFTER model load so latency_ms reflects
        //    transcription latency, not model-load time (same as WhisperKitSTT).
        EventEmitter.resetAudioStart()

        // 7. Create stream, feed samples, decode.
        val stream = recognizer.createStream()
        stream.acceptWaveform(waveData.samples, waveData.sampleRate)
        recognizer.decode(stream)
        val result = recognizer.getResult(stream)
        stream.release()
        recognizer.release()

        val fullText = result.text.trim()
        if (fullText.isEmpty()) return

        val latencyMs = EventEmitter.latencyMsSinceAudioStart()

        // 8. Estimate segment timing from timestamps if available.
        //    timestamps is a FloatArray of token-level start times in seconds
        //    (populated if enableTokenTimestamps was set — we don't request it,
        //    so it may be empty). Fall back to 0 / latencyMs.
        val segmentStartMs = if (result.timestamps.isNotEmpty())
            (result.timestamps.first() * 1000f).toInt() else 0
        val segmentEndMs   = if (result.timestamps.isNotEmpty())
            (result.timestamps.last()  * 1000f).toInt() else latencyMs

        // 9. Emit simulated partial (first word or first 20 chars).
        if (enablePartials) {
            val previewText = fullText.split(" ").first().take(20)
            EventEmitter.emitPartial(
                text = previewText,
                timestampMs = segmentStartMs,
                latencyMs = maxOf(0, latencyMs - 100),  // slightly before final
                confidence = null,
                engineMetadata = mapOf(
                    "engine" to ENGINE_NAME,
                    "model" to modelId,
                    "is_volatile" to true,
                    "simulated" to true,
                ),
            )
        }

        // 10. Emit final event.
        EventEmitter.emitFinal(
            text = fullText,
            timestampMs = segmentStartMs,
            latencyMs = latencyMs,
            confidence = null,       // OfflineRecognizer does not expose logprob
            engineMetadata = mapOf(
                "engine" to ENGINE_NAME,
                "model" to modelId,
                "is_volatile" to false,
                "segment_start_s" to (segmentStartMs / 1000.0),
                "segment_end_s"   to (segmentEndMs   / 1000.0),
            ),
        )
    }

    // -------------------------------------------------------------------------
    // Model management
    // -------------------------------------------------------------------------

    private fun resolveModelDir(): File {
        val home = System.getProperty("user.home") ?: error("user.home not set")
        return File(home, "$MODEL_CACHE_DIR/$MODEL_SUBDIR")
    }

    /**
     * Download and extract the Whisper-tiny-en model if not already present.
     * Uses a sentinel file (.complete) to avoid partial-extract re-runs.
     */
    private fun ensureModelDownloaded(modelDir: File) {
        val sentinel = File(modelDir, ".complete")
        if (sentinel.exists()) return  // already downloaded and extracted

        System.err.println("SherpaOnnxSTT: downloading model to ${modelDir.absolutePath} …")
        System.err.println("SherpaOnnxSTT: source: $MODEL_URL")

        // Download to a temp file.
        val tmpFile = Files.createTempFile("sherpa-onnx-model-", ".tar.bz2").toFile()
        try {
            downloadFile(MODEL_URL, tmpFile)

            // Extract with the system `tar` (available on macOS and Linux).
            val parentDir = modelDir.parentFile ?: error("no parent for $modelDir")
            parentDir.mkdirs()
            val extractResult = ProcessBuilder("tar", "-xjf", tmpFile.absolutePath, "-C", parentDir.absolutePath)
                .inheritIO()
                .start()
                .waitFor()
            if (extractResult != 0) {
                error("tar extraction failed with exit code $extractResult")
            }

            // Write sentinel so next run skips download.
            sentinel.writeText("ok\n")
            System.err.println("SherpaOnnxSTT: model ready at ${modelDir.absolutePath}")
        } finally {
            tmpFile.delete()
        }
    }

    /**
     * Download [url] to [dest] using Java's built-in URL/channel API.
     * No external HTTP client dependency needed.
     */
    private fun downloadFile(url: String, dest: File) {
        URI(url).toURL().openStream().use { input ->
            Channels.newChannel(input).use { src ->
                dest.outputStream().channel.use { dst ->
                    dst.transferFrom(src, 0, Long.MAX_VALUE)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Typed exceptions used by Main.kt to map exit codes
// ---------------------------------------------------------------------------

/** Exit code 30: audio file not found or unreadable. */
class AudioFileNotFoundException(message: String) : RuntimeException(message)

/** Exit code 50: model failed to load (download error, missing files, JNI crash). */
class ModelLoadFailedException(message: String) : RuntimeException(message)
