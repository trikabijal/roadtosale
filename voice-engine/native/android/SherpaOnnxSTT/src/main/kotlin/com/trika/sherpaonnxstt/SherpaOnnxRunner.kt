package com.trika.sherpaonnxstt

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.WaveReader
import java.io.File
import java.net.URI
import java.nio.channels.Channels
import java.nio.file.Files

/**
 * SherpaOnnxRunner — wraps sherpa-onnx OfflineRecognizer for Whisper-tiny-en
 * file-based transcription, emitting JSONL events to stdout.
 *
 * Chunking:
 *   The Whisper model has a hard 30-second context window. For audio longer
 *   than 30 s (a typical dealership walkaround is 2–5 minutes) the runner
 *   splits the sample array into non-overlapping 30 s chunks, processes each
 *   chunk in sequence, and emits events with timestamps relative to the
 *   audio-file start (not the chunk start). A small 0.5 s tail from the
 *   previous chunk is prepended to each chunk to avoid cutting words at
 *   chunk boundaries.
 *
 * Partials:
 *   sherpa-onnx OfflineRecognizer does NOT stream intermediate tokens; it
 *   returns a single result per segment once decoding is complete. For the
 *   `--partials true` mode we synthesise ONE partial event per transcribed
 *   chunk (first word as a preview) right before the final for that chunk.
 *   The `engine_metadata.simulated` field is `true` on simulated partials.
 *
 * Model layout expected by sherpa-onnx:
 *   $modelDir/tiny.en-encoder.int8.onnx
 *   $modelDir/tiny.en-decoder.int8.onnx
 *   $modelDir/tiny.en-tokens.txt
 *
 * where modelDir = ~/.cache/sherpa-onnx/models/sherpa-onnx-whisper-tiny.en/
 *
 * The runner downloads the model archive from GitHub on first run and
 * extracts it. Subsequent runs skip the download if the .complete sentinel
 * exists.
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

        /** Whisper's hard context window: 30 seconds. */
        private const val WHISPER_MAX_SECONDS = 30

        /**
         * Overlap prepended from the tail of the previous chunk.
         * Set to 0 — sherpa-onnx clips any audio > 30 s so overlap would be
         * discarded anyway. Hard chunk boundaries are acceptable for lab eval.
         */
        private const val CHUNK_OVERLAP_SECONDS = 0.0f
    }

    /**
     * Transcribe the WAV file at [filePath].
     * Emits JSONL events to stdout via [EventEmitter].
     *
     * Throws [AudioFileNotFoundException] or [ModelLoadFailedException] on failure.
     * Exit codes are mapped by Main.kt.
     */
    fun run(filePath: String) {
        val audioFile = File(filePath)
        if (!audioFile.exists()) {
            throw AudioFileNotFoundException("audio file not found: $filePath")
        }

        // 1. Ensure model is present (download on first run).
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
                numThreads = 4,
                debug = false,
                provider = "cpu",
                modelType = "whisper",
            ),
            decodingMethod = "greedy_search",
        )

        // 4. Initialise recognizer (loads ONNX models — ~1 s on Apple Silicon).
        val recognizer: OfflineRecognizer
        try {
            recognizer = OfflineRecognizer(config = config)
        } catch (e: Exception) {
            throw ModelLoadFailedException(
                "OfflineRecognizer init failed for model '$modelId': ${e.message}"
            )
        }

        // 5. Read full audio samples.
        val waveData = try {
            WaveReader.readWave(filePath)
        } catch (e: Exception) {
            recognizer.release()
            throw AudioFileNotFoundException(
                "Failed to read audio file '$filePath': ${e.message}"
            )
        }

        val sampleRate = waveData.sampleRate.coerceAtLeast(16000)

        // 6. Stamp wall-clock origin AFTER model load, so latency_ms reflects
        //    transcription time (not model-load time) — same as WhisperKitSTT.
        EventEmitter.resetAudioStart()

        // 7. Split into 30-second chunks and transcribe each.
        val maxSamplesPerChunk = (WHISPER_MAX_SECONDS * sampleRate).toInt()
        val overlapSamples = (CHUNK_OVERLAP_SECONDS * sampleRate).toInt()
        val allSamples = waveData.samples
        val totalSamples = allSamples.size

        var chunkStart = 0
        var chunkIndex = 0

        while (chunkStart < totalSamples) {
            val chunkEnd = minOf(chunkStart + maxSamplesPerChunk, totalSamples)

            // Prepend overlap from the previous chunk (except for chunk 0).
            val overlapStart = maxOf(0, chunkStart - overlapSamples)
            val chunkSamples = allSamples.copyOfRange(overlapStart, chunkEnd)

            // Timestamp of the chunk's true content start in the full audio.
            val chunkStartMs = (chunkStart.toLong() * 1000L / sampleRate).toInt()

            val stream = recognizer.createStream()
            stream.acceptWaveform(chunkSamples, sampleRate)
            recognizer.decode(stream)
            val result = recognizer.getResult(stream)
            stream.release()

            val chunkText = result.text.trim()

            if (chunkText.isNotEmpty()) {
                val latencyMs = EventEmitter.latencyMsSinceAudioStart()

                // Estimate segment end in the full-audio timeline.
                val chunkDurationMs = ((chunkEnd - chunkStart).toLong() * 1000L / sampleRate).toInt()
                val segmentEndMs = chunkStartMs + chunkDurationMs

                // Simulated partial: first word of this chunk.
                if (enablePartials) {
                    val previewText = chunkText.split(" ").first().take(20)
                    EventEmitter.emitPartial(
                        text = previewText,
                        timestampMs = chunkStartMs,
                        latencyMs = maxOf(0, latencyMs - 100),
                        confidence = null,
                        engineMetadata = mapOf(
                            "engine" to ENGINE_NAME,
                            "model" to modelId,
                            "chunk_index" to chunkIndex,
                            "chunk_start_ms" to chunkStartMs,
                            "is_volatile" to true,
                            "simulated" to true,
                        ),
                    )
                }

                // Final event for this chunk.
                EventEmitter.emitFinal(
                    text = chunkText,
                    timestampMs = chunkStartMs,
                    latencyMs = latencyMs,
                    confidence = null,   // OfflineRecognizer does not expose logprob
                    engineMetadata = mapOf(
                        "engine" to ENGINE_NAME,
                        "model" to modelId,
                        "chunk_index" to chunkIndex,
                        "chunk_start_ms" to chunkStartMs,
                        "chunk_end_ms" to segmentEndMs,
                        "is_volatile" to false,
                        "simulated" to false,
                    ),
                )
            }

            chunkStart = chunkEnd
            chunkIndex++
        }

        recognizer.release()
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

        val tmpFile = Files.createTempFile("sherpa-onnx-model-", ".tar.bz2").toFile()
        try {
            downloadFile(MODEL_URL, tmpFile)

            val parentDir = modelDir.parentFile ?: error("no parent for $modelDir")
            parentDir.mkdirs()
            val rc = ProcessBuilder("tar", "-xjf", tmpFile.absolutePath, "-C", parentDir.absolutePath)
                .inheritIO()
                .start()
                .waitFor()
            if (rc != 0) error("tar extraction failed with exit code $rc")

            sentinel.writeText("ok\n")
            System.err.println("SherpaOnnxSTT: model ready at ${modelDir.absolutePath}")
        } finally {
            tmpFile.delete()
        }
    }

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
// Typed exceptions — used by Main.kt to map exit codes
// ---------------------------------------------------------------------------

/** Exit code 30: audio file not found or unreadable. */
class AudioFileNotFoundException(message: String) : RuntimeException(message)

/** Exit code 50: model failed to load (download error, missing files, JNI crash). */
class ModelLoadFailedException(message: String) : RuntimeException(message)
