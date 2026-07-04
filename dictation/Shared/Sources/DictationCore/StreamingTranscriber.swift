import AVFoundation

/// One snapshot of a streaming transcription in progress: the stable `confirmed` prefix (safe to
/// keep/show solid) and the tentative `hypothesis` tail (show dimmed, may still change).
public struct StreamingTranscript: Sendable, Equatable {
    public let confirmed: String
    public let hypothesis: String
    public let confidence: Double
    public let latencyMs: Int

    public init(confirmed: String, hypothesis: String, confidence: Double = 0, latencyMs: Int = 0) {
        self.confirmed = confirmed
        self.hypothesis = hypothesis
        self.confidence = confidence
        self.latencyMs = latencyMs
    }

    /// Confirmed + hypothesis joined — what the live pill renders (hypothesis dimmed in the UI).
    public var display: String {
        if confirmed.isEmpty { return hypothesis }
        if hypothesis.isEmpty { return confirmed }
        return confirmed + " " + hypothesis
    }

    public static let empty = StreamingTranscript(confirmed: "", hypothesis: "")
}

/// The streaming sibling of `SpeechTranscriber` (PRD 0008) — feeds a growing audio buffer and emits
/// a LocalAgreement confirmed/hypothesis split for the live pill. Distinct from `SpeechTranscriber`
/// because streaming has different semantics (incremental, stateful, revisable tail) and only some
/// providers support it; the accurate final pasted text still comes from the batch
/// `SpeechTranscriber.transcribe`.
///
/// Non-reentrant: the caller must not overlap `step` calls (drive it from a single throttled tick).
@MainActor
public protocol StreamingTranscriber: AnyObject {
    /// Run one incremental pass over ALL captured samples so far (16 kHz mono Float32). Advances the
    /// confirmed prefix and refreshes the hypothesis tail. Returns the current transcript. A pass
    /// that throws internally is swallowed and the last transcript is returned (preview must never
    /// abort a dictation).
    func step(samples: [Float]) async -> StreamingTranscript
    /// Promote the trailing hypothesis to confirmed (no more audio is coming) and return the final
    /// transcript. Optionally runs a last pass over `samples` first if provided.
    func finish(samples: [Float]?) async -> StreamingTranscript
    /// Drop all state so the session can back a fresh recording.
    func reset()
}

public extension StreamingTranscriber {
    func finish() async -> StreamingTranscript { await finish(samples: nil) }
}

/// Deterministic streaming transcriber for tests/pipeline wiring — no model. Emits a fixed script of
/// words, moving one word from hypothesis to confirmed on each `step`, so callers can exercise the
/// confirmed/hypothesis wiring without loading WhisperKit.
@MainActor
public final class MockStreamingTranscriber: StreamingTranscriber {
    private let words: [String]
    private var revealed = 0

    public init(script: String = "the quick brown fox jumps over") {
        self.words = script.split(separator: " ").map(String.init)
    }

    public func step(samples: [Float]) async -> StreamingTranscript {
        revealed = Swift.min(words.count, revealed + 1)
        let confirmedCount = Swift.max(0, revealed - 1)     // trail the last word as hypothesis
        let confirmed = words.prefix(confirmedCount).joined(separator: " ")
        let hypothesis = revealed > confirmedCount ? words[confirmedCount] : ""
        return StreamingTranscript(confirmed: confirmed, hypothesis: hypothesis, confidence: 1)
    }

    public func finish(samples: [Float]?) async -> StreamingTranscript {
        StreamingTranscript(confirmed: words.joined(separator: " "), hypothesis: "", confidence: 1)
    }

    public func reset() { revealed = 0 }
}
