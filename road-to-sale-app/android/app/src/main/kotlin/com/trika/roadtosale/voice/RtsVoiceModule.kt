package com.trika.roadtosale.voice

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import androidx.core.app.ActivityCompat
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.ReadableArray
import com.facebook.react.modules.core.DeviceEventManagerModule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.TenVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.net.URL

private const val SAMPLE_RATE = 16000
private const val VAD_FRAME_SAMPLES = 512
private const val VAD_THRESHOLD = 0.5f
private const val VAD_MIN_SILENCE_S = 0.4f
private const val VAD_MIN_SPEECH_S = 0.2f
private const val VAD_MAX_SPEECH_S = 29.0f

class RtsVoiceModule(private val reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    override fun getName() = "RtsVoiceModule"

    private var recordingJob: Job? = null
    private var isMuted = false
    private var sessionStartMs = 0L
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ── Permission ──────────────────────────────────────────────────────────────

    @ReactMethod
    fun requestPermissions(promise: Promise) {
        val granted = ActivityCompat.checkSelfPermission(
            reactContext, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        promise.resolve(if (granted) "granted" else "denied")
    }

    // ── Start ────────────────────────────────────────────────────────────────────

    @ReactMethod
    fun startListening(language: String, customVocabulary: ReadableArray, promise: Promise) {
        scope.launch {
            try {
                startForegroundService()
                sessionStartMs = System.currentTimeMillis()
                isMuted = false

                val vadModelPath = ensureVadModel()
                val recognizerModelDir = ensureRecognizerModel()

                // Build SherpaOnnx VAD
                val vadConfig = VadModelConfig(
                    sileroVadModelConfig = SileroVadModelConfig(
                        model = vadModelPath,
                        threshold = VAD_THRESHOLD,
                        minSilenceDuration = VAD_MIN_SILENCE_S,
                        minSpeechDuration = VAD_MIN_SPEECH_S,
                        windowSize = VAD_FRAME_SAMPLES,
                        maxSpeechDuration = VAD_MAX_SPEECH_S,
                    ),
                    tenVadModelConfig = TenVadModelConfig(), // required by JNI (DC53)
                    sampleRate = SAMPLE_RATE,
                )
                val vad = Vad(config = vadConfig)

                // Build OfflineRecognizer (Whisper small.en)
                val recognizerConfig = OfflineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                    modelConfig = OfflineModelConfig(
                        whisper = OfflineWhisperModelConfig(
                            encoder = "$recognizerModelDir/encoder.int8.onnx",
                            decoder = "$recognizerModelDir/decoder.int8.onnx",
                            language = "en",
                            task = "transcribe",
                        ),
                        numThreads = 2,
                        debug = false,
                        provider = "cpu",
                    ),
                )
                val recognizer = OfflineRecognizer(config = recognizerConfig)

                // Start AudioRecord
                val minBuf = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                )
                val audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minBuf, VAD_FRAME_SAMPLES * 2),
                )
                audioRecord.startRecording()
                emitState("listening")
                promise.resolve(null)

                recordingJob = scope.launch {
                    val shortBuf = ShortArray(VAD_FRAME_SAMPLES)
                    try {
                        while (isActive) {
                            val read = audioRecord.read(shortBuf, 0, VAD_FRAME_SAMPLES)
                            if (read <= 0 || isMuted) continue

                            val floatBuf = FloatArray(read) { shortBuf[it] / 32768f }
                            vad.acceptWaveform(floatBuf)

                            while (!vad.empty()) {
                                val segment = vad.front()
                                vad.pop()
                                val segStartMs = sessionStartMs + (segment.start.toLong() * 1000L / SAMPLE_RATE)
                                val result = transcribeSegment(recognizer, segment.samples, segStartMs)
                                if (result.isNotBlank()) {
                                    emitTranscript(result, segStartMs)
                                }
                            }
                        }
                    } finally {
                        audioRecord.stop()
                        audioRecord.release()
                    }
                }
            } catch (e: Exception) {
                emitError(e.message ?: "Unknown error")
                promise.reject("start_error", e.message, e)
            }
        }
    }

    @ReactMethod
    fun stopListening(promise: Promise) {
        recordingJob?.cancel()
        recordingJob = null
        stopForegroundService()
        emitState("stopped")
        promise.resolve(null)
    }

    @ReactMethod
    fun mute() {
        isMuted = true
        emitState("muted")
    }

    @ReactMethod
    fun unmute() {
        isMuted = false
        emitState("listening")
    }

    // ── Private helpers ──────────────────────────────────────────────────────────

    private fun transcribeSegment(recognizer: OfflineRecognizer, samples: FloatArray, segStartMs: Long): String {
        val stream = recognizer.createStream()
        stream.acceptWaveform(samples, SAMPLE_RATE)
        recognizer.decode(stream)
        return recognizer.getResult(stream).text.trim()
    }

    private fun emitTranscript(text: String, timestampMs: Long) {
        val nowMs = System.currentTimeMillis()
        val event = mapOf(
            "text" to text,
            "stability" to "final",
            "timestamp_ms" to nowMs,
            "latency_ms_from_audio_start" to (nowMs - sessionStartMs),
            "confidence" to null,
            "engine_metadata" to mapOf("engine" to "sherpa_onnx_vad", "segment_start_ms" to timestampMs)
        )
        val args = Arguments.createMap().also { map ->
            event.forEach { (k, v) ->
                when (v) {
                    is String -> map.putString(k, v)
                    is Long -> map.putDouble(k, v.toDouble())
                    is Int -> map.putInt(k, v)
                    null -> map.putNull(k)
                    is Map<*, *> -> map.putMap(k, Arguments.createMap().also { inner ->
                        @Suppress("UNCHECKED_CAST")
                        (v as Map<String, Any?>).forEach { (ik, iv) ->
                            when (iv) {
                                is String -> inner.putString(ik, iv)
                                is Long -> inner.putDouble(ik, iv.toDouble())
                                null -> inner.putNull(ik)
                                else -> {}
                            }
                        }
                    })
                    else -> {}
                }
            }
        }
        sendEvent("onTranscriptEvent", args)
    }

    private fun emitState(state: String) = sendEvent("onVoiceStateChange", state)
    private fun emitError(msg: String) = sendEvent("onVoiceError", msg)

    private fun sendEvent(name: String, body: Any?) {
        reactContext
            .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
            .emit(name, body)
    }

    private fun startForegroundService() {
        val intent = Intent(reactContext, RtsVoiceForegroundService::class.java).apply {
            action = RtsVoiceForegroundService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            reactContext.startForegroundService(intent)
        } else {
            reactContext.startService(intent)
        }
    }

    private fun stopForegroundService() {
        val intent = Intent(reactContext, RtsVoiceForegroundService::class.java).apply {
            action = RtsVoiceForegroundService.ACTION_STOP
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            reactContext.startForegroundService(intent)
        } else {
            reactContext.startService(intent)
        }
    }

    private fun ensureVadModel(): String {
        val cacheDir = reactContext.cacheDir
        val modelFile = File(cacheDir, "silero_vad.onnx")
        if (!modelFile.exists()) {
            val url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx"
            URL(url).openStream().use { input ->
                modelFile.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return modelFile.absolutePath
    }

    private fun ensureRecognizerModel(): String {
        // Use the same model path pattern as the lab runner.
        // Model must be present on-device (side-loaded or downloaded at app startup).
        val homeDir = System.getProperty("user.home") ?: "/data"
        val modelDir = File("$homeDir/.cache/sherpa-onnx/models/sherpa-onnx-whisper-small.en")
        return modelDir.absolutePath
    }

    @ReactMethod
    fun addListener(eventName: String) {} // Required for RN event emitter

    @ReactMethod
    fun removeListeners(count: Int) {} // Required for RN event emitter
}
