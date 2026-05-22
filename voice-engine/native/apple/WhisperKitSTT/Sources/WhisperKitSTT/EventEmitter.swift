//
//  EventEmitter.swift
//
//  Shared JSONL event emission. One line per event on stdout. Mirrors the
//  TranscriptEvent shape used by the Python lab and the AppleSTT sibling
//  CLI; the lab's strategy wrappers all parse the same field set.
//
//  Shape:
//    {
//      "type": "partial" | "final",
//      "text": "...",
//      "timestamp_ms": Int,                  // audio-relative
//      "latency_ms_from_audio_start": Int,   // wall-clock from audio start
//      "confidence": Double?,                // exp(avg_logprob) clamped 0..1
//      "engine_metadata": { ... }            // free-form
//    }
//
//  We hand-encode the JSON line to keep ordering stable, keys consistent,
//  and avoid pulling in extra dependencies. Stdout is line-buffered via
//  setvbuf so consumers see partials as they happen.

import Foundation

#if canImport(Darwin)
import Darwin
#endif

enum EventType: String {
    case partial
    case final
}

struct TranscriptEventOut {
    let type: EventType
    let text: String
    let timestampMs: Int
    let latencyMsFromAudioStart: Int
    let confidence: Double?
    let engineMetadata: [String: Any]
}

final class EventEmitter {
    /// Wall-clock origin used to compute `latency_ms_from_audio_start`.
    /// The runner sets this once, right before it kicks off transcription.
    private(set) var audioStart: Date

    /// Serialize emit() calls. WhisperKit fires callbacks from arbitrary
    /// Tasks, so emissions can race; an NSLock guarantees one JSONL line
    /// at a time on stdout.
    private let lock = NSLock()

    init(audioStart: Date = Date()) {
        self.audioStart = audioStart
        // Best-effort line buffering on stdout so Python sees partials live.
        setvbuf(stdout, nil, _IOLBF, 0)
    }

    func resetAudioStart(_ date: Date = Date()) {
        self.audioStart = date
    }

    func emit(_ event: TranscriptEventOut) {
        let payload: [String: Any?] = [
            "type": event.type.rawValue,
            "text": event.text,
            "timestamp_ms": event.timestampMs,
            "latency_ms_from_audio_start": event.latencyMsFromAudioStart,
            "confidence": event.confidence,
            "engine_metadata": event.engineMetadata
        ]
        // Preserve `confidence: null` rather than dropping the key.
        let cleaned: [String: Any] = payload.compactMapValues { value in
            if value == nil {
                return NSNull()
            }
            return value
        }
        do {
            let data = try JSONSerialization.data(
                withJSONObject: cleaned,
                options: []
            )
            if let line = String(data: data, encoding: .utf8) {
                lock.lock()
                defer { lock.unlock() }
                FileHandle.standardOutput.write(Data((line + "\n").utf8))
            }
        } catch {
            FileHandle.standardError.write(
                Data("error: failed to encode event: \(error)\n".utf8)
            )
        }
    }

    /// Convenience to compute the wall-clock latency from audio start.
    func latencyMsSinceAudioStart() -> Int {
        return Int(Date().timeIntervalSince(audioStart) * 1000.0)
    }
}

/// Emit a structured error to stderr and exit non-zero. Python wrapper
/// surfaces these as TranscriptionError with a fix-it hint.
func fail(_ message: String, code: Int32 = 1) -> Never {
    FileHandle.standardError.write(Data("WhisperKitSTT error: \(message)\n".utf8))
    exit(code)
}
