package com.trika.sherpaonnxstt

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import com.k2fsa.sherpa.onnx.WaveReader
import java.io.File
import java.net.URI
import java.nio.channels.Channels
import java.nio.file.Files

/**
 * SherpaOnnxRunner — wraps sherpa-onnx for Whisper-tiny-en file transcription.
 *
 * Two modes:
 *
 * VAD mode (default, --mode vad):
 *   Uses Silero VAD to segment audio at natural speech boundaries (pauses between
 *   sentences). Each speech segment is fed independently to OfflineRecognizer.
 *   Segments are typically 2–8 seconds long — sentence level. This is the
 *   production simulation: on Android, the same SileroVAD feeds audio from the
 *   live microphone to the same OfflineRecognizer. Produces short events that
 *   are semantically comparable to Apple SpeechTranscriber and WhisperKit finals.
 *
 * Batch mode (--mode batch):
 *   Splits audio into fixed 30-second non-overlapping chunks (Whisper's hard
 *   context limit). Each chunk is one event. Fast but produces 70–130 word
 *   events that dilute semantic embedding similarity — not suitable for
 *   semantic cue matching. Use only when debugging transcript coverage.
 *
 * Partials:
 *   OfflineRecognizer does not stream tokens; one result per segment/chunk.
 *   In VAD mode, a simulated partial (first word) is emitted before each
 *   final. In batch mode, one simulated partial per 30 s chunk.
 *   engine_metadata.simulated=true marks all synthetic partials.
 *
 * Model layout (both modes):
 *   Whisper: ~/.cache/sherpa-onnx/models/sherpa-onnx-whisper-tiny.en/
 *     tiny.en-encoder.int8.onnx
 *     tiny.en-decoder.int8.onnx
 *     tiny.en-tokens.txt
 *   Silero VAD: ~/.cache/sherpa-onnx/models/silero_vad.onnx
 *
 * Models are downloaded from GitHub on first run; subsequent runs skip download.
 */
class SherpaOnnxRunner(
    private val modelId: String = "whisper-tiny-en",
    private val enablePartials: Boolean = true,
    private val mode: String = "vad",  // "vad" | "batch"
) {

    companion object {
        private const val MODEL_CACHE_DIR = ".cache/sherpa-onnx/models"
        private const val WHISPER_SUBDIR = "sherpa-onnx-whisper-tiny.en"
        private const val WHISPER_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-tiny.en.tar.bz2"
        private const val SILERO_FILENAME = "silero_vad.onnx"
        private const val SILERO_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx"
        private const val ENGINE_NAME = "sherpa_onnx"

        /** Whisper hard context window — 30 seconds at 16 kHz = 480,000 samples. */
        private const val WHISPER_MAX_SECONDS = 30

        /**
         * VAD frame size. Silero VAD requires exactly 512 samples (32 ms) at 16 kHz.
         * Passing a different size will crash the JNI call.
         */
        private const val VAD_FRAME_SAMPLES = 512

        /**
         * Silence threshold for VAD segmentation. 0.5 is the Silero default.
         * Lower = more sensitive (detects quieter speech, more segments).
         * Higher = less sensitive (only loud speech, fewer segments).
         */
        private const val VAD_THRESHOLD = 0.5f

        /** Min silence between segments to declare end-of-utterance. 0.4 s works
         *  well for dealer speech with brief pauses between sentences. */
        private const val VAD_MIN_SILENCE_S = 0.4f

        /** Min speech duration to emit as a segment. Filters out clicks/pops. */
        private const val VAD_MIN_SPEECH_S = 0.2f

        /** Max segment duration before forced cut. Whisper's 30 s hard limit. */
        private const val VAD_MAX_SPEECH_S = 29.0f
    }

    fun run(filePath: String) {
        val audioFile = File(filePath)
        if (!audioFile.exists()) throw AudioFileNotFoundException("audio file not found: $filePath")

        val whisperDir = resolveWhisperDir()
        val sileroPath = resolveSileroPath()

        try {
            ensureWhisperDownloaded(whisperDir)
            if (mode == "vad") ensureSileroDownloaded(sileroPath)
        } catch (e: Exception) {
            throw ModelLoadFailedException("Failed to load/download models: ${e.message}")
        }

        val encoderPath = File(whisperDir, "tiny.en-encoder.int8.onnx")
        val decoderPath = File(whisperDir, "tiny.en-decoder.int8.onnx")
        val tokensPath  = File(whisperDir, "tiny.en-tokens.txt")
        for (f in listOf(encoderPath, decoderPath, tokensPath)) {
            if (!f.exists()) throw ModelLoadFailedException("Missing model file: ${f.absolutePath}")
        }

        val recognizerConfig = OfflineRecognizerConfig(
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

        val recognizer = try {
            OfflineRecognizer(config = recognizerConfig)
        } catch (e: Exception) {
            throw ModelLoadFailedException("OfflineRecognizer init failed: ${e.message}")
        }

        val waveData = try {
            WaveReader.readWave(filePath)
        } catch (e: Exception) {
            recognizer.release()
            throw AudioFileNotFoundException("Failed to read audio '$filePath': ${e.message}")
        }

        val sampleRate = waveData.sampleRate.coerceAtLeast(16000)

        // Stamp wall-clock origin after model load so latency_ms reflects
        // transcription time, not model load time — same convention as WhisperKitSTT.
        EventEmitter.resetAudioStart()

        when (mode) {
            "vad" -> runVad(waveData.samples, sampleRate, recognizer, sileroPath)
            else  -> runBatch(waveData.samples, sampleRate, recognizer)
        }

        recognizer.release()
    }

    // -------------------------------------------------------------------------
    // VAD mode — Silero VAD → sentence-level segments → OfflineRecognizer
    // -------------------------------------------------------------------------

    private fun runVad(
        allSamples: FloatArray,
        sampleRate: Int,
        recognizer: OfflineRecognizer,
        sileroPath: File,
    ) {
        val vadConfig = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = sileroPath.absolutePath,
                threshold = VAD_THRESHOLD,
                minSilenceDuration = VAD_MIN_SILENCE_S,
                minSpeechDuration = VAD_MIN_SPEECH_S,
                windowSize = VAD_FRAME_SAMPLES,
                maxSpeechDuration = VAD_MAX_SPEECH_S,
            ),
            sampleRate = sampleRate,
            numThreads = 1,
            provider = "cpu",
            debug = false,
        )

        val vad = try {
            Vad(config = vadConfig)
        } catch (e: Exception) {
            throw ModelLoadFailedException("Silero VAD init failed: ${e.message}")
        }

        val totalSamples = allSamples.size
        var segmentIndex = 0

        // Feed audio in VAD_FRAME_SAMPLES frames (32 ms each).
        var frameStart = 0
        while (frameStart < totalSamples) {
            val frameEnd = minOf(frameStart + VAD_FRAME_SAMPLES, totalSamples)
            val frame = allSamples.copyOfRange(frameStart, frameEnd)

            // Pad the last frame to VAD_FRAME_SAMPLES if needed.
            val paddedFrame = if (frame.size < VAD_FRAME_SAMPLES) {
                FloatArray(VAD_FRAME_SAMPLES).also { frame.copyInto(it) }
            } else {
                frame
            }

            vad.acceptWaveform(paddedFrame)

            // Drain completed segments.
            while (!vad.empty()) {
                val segment = vad.front()
                vad.pop()
                transcribeSegment(segment.samples, segment.start, sampleRate, segmentIndex, recognizer)
                segmentIndex++
            }

            frameStart = frameEnd
        }

        // Flush: force VAD to emit any buffered speech at end-of-file.
        vad.flush()
        while (!vad.empty()) {
            val segment = vad.front()
            vad.pop()
            transcribeSegment(segment.samples, segment.start, sampleRate, segmentIndex, recognizer)
            segmentIndex++
        }

        vad.release()
    }

    private fun transcribeSegment(
        samples: FloatArray,
        segmentStartInAudio: Int,   // sample offset within the full audio file
        sampleRate: Int,
        segmentIndex: Int,
        recognizer: OfflineRecognizer,
    ) {
        if (samples.isEmpty()) return

        val segmentStartMs = (segmentStartInAudio.toLong() * 1000L / sampleRate).toInt()
        val segmentDurationMs = (samples.size.toLong() * 1000L / sampleRate).toInt()

        val stream = recognizer.createStream()
        stream.acceptWaveform(samples, sampleRate)
        recognizer.decode(stream)
        val result = recognizer.getResult(stream)
        stream.release()

        val text = result.text.trim()
        if (text.isEmpty()) return

        val latencyMs = EventEmitter.latencyMsSinceAudioStart()

        if (enablePartials) {
            val previewText = text.split(" ").first().take(20)
            EventEmitter.emitPartial(
                text = previewText,
                timestampMs = segmentStartMs,
                latencyMs = maxOf(0, latencyMs - 50),
                confidence = null,
                engineMetadata = mapOf(
                    "engine" to ENGINE_NAME,
                    "model" to modelId,
                    "mode" to "vad",
                    "segment_index" to segmentIndex,
                    "segment_start_ms" to segmentStartMs,
                    "is_volatile" to true,
                    "simulated" to true,
                ),
            )
        }

        EventEmitter.emitFinal(
            text = text,
            timestampMs = segmentStartMs,
            latencyMs = latencyMs,
            confidence = null,
            engineMetadata = mapOf(
                "engine" to ENGINE_NAME,
                "model" to modelId,
                "mode" to "vad",
                "segment_index" to segmentIndex,
                "segment_start_ms" to segmentStartMs,
                "segment_end_ms" to (segmentStartMs + segmentDurationMs),
                "segment_duration_ms" to segmentDurationMs,
                "is_volatile" to false,
                "simulated" to false,
            ),
        )
    }

    // -------------------------------------------------------------------------
    // Batch mode — fixed 30-second chunks (for debugging transcript coverage)
    // -------------------------------------------------------------------------

    private fun runBatch(
        allSamples: FloatArray,
        sampleRate: Int,
        recognizer: OfflineRecognizer,
    ) {
        val maxSamplesPerChunk = WHISPER_MAX_SECONDS * sampleRate
        val totalSamples = allSamples.size
        var chunkStart = 0
        var chunkIndex = 0

        while (chunkStart < totalSamples) {
            val chunkEnd = minOf(chunkStart + maxSamplesPerChunk, totalSamples)
            val chunkSamples = allSamples.copyOfRange(chunkStart, chunkEnd)
            val chunkStartMs = (chunkStart.toLong() * 1000L / sampleRate).toInt()

            val stream = recognizer.createStream()
            stream.acceptWaveform(chunkSamples, sampleRate)
            recognizer.decode(stream)
            val result = recognizer.getResult(stream)
            stream.release()

            val text = result.text.trim()
            if (text.isNotEmpty()) {
                val latencyMs = EventEmitter.latencyMsSinceAudioStart()
                val chunkDurationMs = ((chunkEnd - chunkStart).toLong() * 1000L / sampleRate).toInt()

                if (enablePartials) {
                    EventEmitter.emitPartial(
                        text = text.split(" ").first().take(20),
                        timestampMs = chunkStartMs,
                        latencyMs = maxOf(0, latencyMs - 100),
                        confidence = null,
                        engineMetadata = mapOf(
                            "engine" to ENGINE_NAME, "model" to modelId, "mode" to "batch",
                            "chunk_index" to chunkIndex, "is_volatile" to true, "simulated" to true,
                        ),
                    )
                }

                EventEmitter.emitFinal(
                    text = text,
                    timestampMs = chunkStartMs,
                    latencyMs = latencyMs,
                    confidence = null,
                    engineMetadata = mapOf(
                        "engine" to ENGINE_NAME, "model" to modelId, "mode" to "batch",
                        "chunk_index" to chunkIndex,
                        "chunk_start_ms" to chunkStartMs,
                        "chunk_end_ms" to (chunkStartMs + chunkDurationMs),
                        "is_volatile" to false, "simulated" to false,
                    ),
                )
            }

            chunkStart = chunkEnd
            chunkIndex++
        }
    }

    // -------------------------------------------------------------------------
    // Model management
    // -------------------------------------------------------------------------

    private fun resolveWhisperDir(): File =
        File(System.getProperty("user.home")!!, "$MODEL_CACHE_DIR/$WHISPER_SUBDIR")

    private fun resolveSileroPath(): File =
        File(System.getProperty("user.home")!!, "$MODEL_CACHE_DIR/$SILERO_FILENAME")

    private fun ensureWhisperDownloaded(modelDir: File) {
        val sentinel = File(modelDir, ".complete")
        if (sentinel.exists()) return
        System.err.println("SherpaOnnxSTT: downloading Whisper-tiny-en model …")
        System.err.println("SherpaOnnxSTT: source: $WHISPER_URL")
        val tmpFile = Files.createTempFile("sherpa-onnx-whisper-", ".tar.bz2").toFile()
        try {
            downloadFile(WHISPER_URL, tmpFile)
            val parent = modelDir.parentFile ?: error("no parent for $modelDir")
            parent.mkdirs()
            val rc = ProcessBuilder("tar", "-xjf", tmpFile.absolutePath, "-C", parent.absolutePath)
                .inheritIO().start().waitFor()
            if (rc != 0) error("tar extraction failed with exit code $rc")
            sentinel.writeText("ok\n")
            System.err.println("SherpaOnnxSTT: Whisper model ready at $modelDir")
        } finally {
            tmpFile.delete()
        }
    }

    private fun ensureSileroDownloaded(sileroPath: File) {
        if (sileroPath.exists()) return
        System.err.println("SherpaOnnxSTT: downloading Silero VAD model (~1.8 MB) …")
        System.err.println("SherpaOnnxSTT: source: $SILERO_URL")
        sileroPath.parentFile?.mkdirs()
        downloadFile(SILERO_URL, sileroPath)
        System.err.println("SherpaOnnxSTT: Silero VAD ready at $sileroPath")
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
// Typed exceptions — exit codes mapped by Main.kt
// ---------------------------------------------------------------------------

class AudioFileNotFoundException(message: String) : RuntimeException(message)
class ModelLoadFailedException(message: String) : RuntimeException(message)
