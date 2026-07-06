import Foundation

/// One snapshot of a streaming transcription in progress: the stable `confirmed` prefix and the
/// tentative `hypothesis` tail. The live pill currently renders `confirmed` only (a product decision
/// — see `AppState.startStreamingSession`); `hypothesis` is exposed for completeness because
/// WhisperKit's LocalAgreement naturally produces it.
public struct StreamingTranscript: Sendable, Equatable {
    public let confirmed: String
    public let hypothesis: String

    public init(confirmed: String, hypothesis: String = "") {
        self.confirmed = confirmed
        self.hypothesis = hypothesis
    }

    public static let empty = StreamingTranscript(confirmed: "")
}

/// The streaming sibling of `SpeechTranscriber` (PRD 0008) — feeds a growing audio buffer and emits a
/// LocalAgreement confirmed/hypothesis split for the live pill. Distinct from `SpeechTranscriber`
/// because streaming has different semantics (incremental, stateful, revisable tail) and only some
/// providers support it; the accurate final pasted text still comes from the batch
/// `SpeechTranscriber.transcribe`.
///
/// Non-reentrant: the caller must not overlap `step` calls (drive it from a single throttled tick).
@MainActor
public protocol StreamingTranscriber: AnyObject {
    /// Run one incremental pass over ALL captured samples so far (16 kHz mono Float32) and return the
    /// current transcript. A pass that throws internally is swallowed and the last transcript is
    /// returned (preview must never abort a dictation).
    func step(samples: [Float]) async -> StreamingTranscript
    /// Finish the session's input and release its resources so it can deallocate — called by
    /// `AppState` on every teardown (stop / discard).
    func reset()
}
