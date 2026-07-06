import Foundation

/// One transcribed segment reduced to just what LocalAgreement needs — text plus its audio
/// time-span. Deliberately model-free (no WhisperKit type) so the agreement logic is pure and
/// unit-testable without loading a model. `WhisperKitStreamingSession` maps WhisperKit's
/// `TranscriptionSegment` onto this.
public struct AgreedSegment: Sendable, Equatable {
    public let text: String
    public let start: Double
    public let end: Double

    public init(text: String, start: Double, end: Double) {
        self.text = text
        self.start = start
        self.end = end
    }
}

/// Pure LocalAgreement-2 state for streaming STT (PRD 0008).
///
/// This is the confirmed/unconfirmed split from WhisperKit's `AudioStreamTranscriber`
/// (`AudioStreamTranscriber.swift:166-189`), lifted out of that actor so it can run over audio we
/// feed from our own `RecordingEngine` (WhisperKit's version owns the microphone and can't take
/// fed buffers — see PRD 0008 §0). The algorithm is procured, not reinvented.
///
/// The idea (LocalAgreement-2, Macháček et al. 2023): re-transcribe the growing audio periodically;
/// Whisper keeps revising the last few segments as more context arrives, so only **commit**
/// segments once they're old enough to be stable — everything except the trailing
/// `requiredUnconfirmed` segments. Committed ("confirmed") text never changes under the user; the
/// trailing "hypothesis" is shown tentatively (dimmed) and may still change next pass.
///
/// Robust to either feed: passing `clipTimestamps=[lastConfirmedEnd]` (segments already trimmed to
/// new audio) OR re-decoding the whole buffer — segments ending at/before `lastConfirmedEnd` are
/// filtered out either way, so already-confirmed text is never duplicated or rewritten.
public struct StreamingAgreement: Sendable {
    /// How many trailing segments to hold back as unstable hypothesis. WhisperKit's default is 2.
    /// Lower = lower latency to confirm, higher = more stability against tail revisions.
    public let requiredUnconfirmed: Int

    /// Segments committed as stable, in order. Never rewritten once appended.
    public private(set) var confirmed: [AgreedSegment] = []
    /// The trailing, still-in-flux segments from the latest pass.
    public private(set) var hypothesis: [AgreedSegment] = []
    /// Audio time (seconds) through which text is confirmed. Monotonic non-decreasing.
    public private(set) var lastConfirmedEnd: Double = 0

    public init(requiredUnconfirmed: Int = 2) {
        self.requiredUnconfirmed = max(0, requiredUnconfirmed)
    }

    /// Feed the segments from one transcription pass. Confirms everything except the trailing
    /// `requiredUnconfirmed` *new* segments; the rest become the current hypothesis.
    public mutating func integrate(_ segments: [AgreedSegment]) {
        // Only segments extending past what we've already confirmed are new material. This dedups a
        // whole-buffer re-decode and is a no-op filter for a clipTimestamps-trimmed feed.
        let fresh = segments.filter { $0.end > lastConfirmedEnd + 1e-3 }

        guard fresh.count > requiredUnconfirmed else {
            // Not enough new segments to safely confirm any — all of them stay hypothesis.
            hypothesis = fresh
            return
        }

        let confirmCount = fresh.count - requiredUnconfirmed
        for seg in fresh.prefix(confirmCount) {
            confirmed.append(seg)
            lastConfirmedEnd = Swift.max(lastConfirmedEnd, seg.end)
        }
        hypothesis = Array(fresh.suffix(requiredUnconfirmed))
    }

    /// At stop, promote whatever is still in hypothesis to confirmed (no more audio is coming, so
    /// the tail is as stable as it will get). Returns the full confirmed text.
    @discardableResult
    public mutating func flushHypothesis() -> String {
        for seg in hypothesis where seg.end > lastConfirmedEnd + 1e-3 {
            confirmed.append(seg)
            lastConfirmedEnd = Swift.max(lastConfirmedEnd, seg.end)
        }
        hypothesis = []
        return confirmedText
    }

    /// Stable committed text — safe to keep, won't change under the user.
    public var confirmedText: String { Self.join(confirmed) }
    /// Tentative trailing text — show dimmed, may still change.
    public var hypothesisText: String { Self.join(hypothesis) }

    private static func join(_ segs: [AgreedSegment]) -> String {
        segs.map { $0.text.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
            .joined(separator: " ")
    }
}
