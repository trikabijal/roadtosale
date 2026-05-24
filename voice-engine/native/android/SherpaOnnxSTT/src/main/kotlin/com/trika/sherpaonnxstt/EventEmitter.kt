package com.trika.sherpaonnxstt

import java.io.PrintStream

/**
 * JSONL event emitter — mirrors the Swift EventEmitter used by WhisperKitSTT.
 *
 * Shape (one line per event):
 *   {
 *     "type": "partial" | "final",
 *     "text": "...",
 *     "timestamp_ms": Int,                  // audio-relative (ms)
 *     "latency_ms_from_audio_start": Int,   // wall-clock from audio start (ms)
 *     "confidence": Double | null,
 *     "engine_metadata": { ... }
 *   }
 *
 * Stdout is flushed after every event so the Python subprocess driver sees
 * lines as they arrive (equivalent to Swift's _IOLBF line-buffering).
 */
object EventEmitter {

    /** Wall-clock origin set once by the runner just before transcription. */
    @Volatile
    private var audioStartMs: Long = System.currentTimeMillis()

    private val out: PrintStream = System.out

    fun resetAudioStart() {
        audioStartMs = System.currentTimeMillis()
    }

    fun latencyMsSinceAudioStart(): Int =
        (System.currentTimeMillis() - audioStartMs).toInt()

    /**
     * Emit a `partial` event. `confidence` may be null (Whisper offline gives
     * no per-token probability for the simulated partial).
     */
    fun emitPartial(
        text: String,
        timestampMs: Int,
        latencyMs: Int,
        confidence: Double?,
        engineMetadata: Map<String, Any?>,
    ) = emitEvent(
        type = "partial",
        text = text,
        timestampMs = timestampMs,
        latencyMs = latencyMs,
        confidence = confidence,
        engineMetadata = engineMetadata,
    )

    /**
     * Emit a `final` event.
     */
    fun emitFinal(
        text: String,
        timestampMs: Int,
        latencyMs: Int,
        confidence: Double?,
        engineMetadata: Map<String, Any?>,
    ) = emitEvent(
        type = "final",
        text = text,
        timestampMs = timestampMs,
        latencyMs = latencyMs,
        confidence = confidence,
        engineMetadata = engineMetadata,
    )

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    @Synchronized
    private fun emitEvent(
        type: String,
        text: String,
        timestampMs: Int,
        latencyMs: Int,
        confidence: Double?,
        engineMetadata: Map<String, Any?>,
    ) {
        val sb = StringBuilder()
        sb.append("{")
        sb.appendJsonString("type", type); sb.append(",")
        sb.appendJsonString("text", text); sb.append(",")
        sb.appendJsonInt("timestamp_ms", timestampMs); sb.append(",")
        sb.appendJsonInt("latency_ms_from_audio_start", latencyMs); sb.append(",")
        if (confidence == null) {
            sb.append("\"confidence\":null")
        } else {
            sb.append("\"confidence\":${confidence}")
        }
        sb.append(",")
        sb.append("\"engine_metadata\":{")
        val metaEntries = engineMetadata.entries.toList()
        metaEntries.forEachIndexed { i, (k, v) ->
            sb.appendJsonValue(k, v)
            if (i < metaEntries.size - 1) sb.append(",")
        }
        sb.append("}")
        sb.append("}")

        out.println(sb.toString())
        out.flush()
    }

    // Minimal JSON helpers — avoids a heavy JSON library dep at runtime.

    private fun StringBuilder.appendJsonString(key: String, value: String) {
        append("\"").append(key).append("\":\"").append(escapeJson(value)).append("\"")
    }

    private fun StringBuilder.appendJsonInt(key: String, value: Int) {
        append("\"").append(key).append("\":").append(value)
    }

    private fun StringBuilder.appendJsonValue(key: String, value: Any?) {
        append("\"").append(escapeJson(key)).append("\":")
        when (value) {
            null -> append("null")
            is Boolean -> append(value.toString())
            is Int -> append(value.toString())
            is Long -> append(value.toString())
            is Float -> append(value.toString())
            is Double -> append(value.toString())
            is String -> append("\"").append(escapeJson(value)).append("\"")
            else -> append("\"").append(escapeJson(value.toString())).append("\"")
        }
    }

    private fun escapeJson(s: String): String = s
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")
}
