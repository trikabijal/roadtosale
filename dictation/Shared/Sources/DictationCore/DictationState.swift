import Foundation

// MARK: - Semantic dictation state (the `onState` rename)
//
// Two orthogonal axes replace the tangled `dictationState` / `engineLoaded` / free-floating
// `statusMessage` surface in the app coordinator:
//
//   • `DictationPhase`      — what is THIS dictation doing right now (per-utterance lifecycle).
//   • `EngineAvailability`  — can the system work AT ALL right now (mic + model readiness).
//
// The coordinator derives both from its existing published state, and the menu bar reads them
// (status glyph + warming spinner) instead of poking `engineLoaded`/`dictationState` directly.
// `dictationState`/`engineLoaded` remain the underlying source until the `DictationEngine` facade
// emits these directly — at which point the terminal cases (`.inserted`, `.failed`) light up too.

/// What the current dictation is doing right now. Replaces `DictationState {idle,recording,
/// transcribing}` with success (`inserted`) and failure (`failed`) terminals made explicit.
public enum DictationPhase: Equatable {
    /// Nothing in flight — idle, ready for the next dictation.
    case idle
    /// Capturing audio from the mic (was `recording`).
    case capturing
    /// Capture stopped; transcribing + cleaning the audio (was `transcribing`).
    case finishing
    /// The cleaned text was pasted/inserted successfully (the success flash).
    case inserted
    /// The dictation failed; `FailReason` says why (was a bare status string).
    case failed(FailReason)

    /// Why a dictation failed.
    public enum FailReason: Equatable {
        case transcription
        case cleanup
        case noSpeech
        case timeout
    }
}

/// Whether the system can dictate at all right now. Replaces `engineLoaded: Bool` plus the
/// load-related `statusMessage`, and composes mic-permission (a capture fact) with model-warmth
/// (an engine fact) — something a single Bool cannot express.
public enum EngineAvailability: Equatable {
    /// The model is still loading/warming; not ready yet.
    case warmingUp
    /// Mic granted and model loaded — ready to dictate.
    case ready
    /// Cannot work until the given block is cleared.
    case blocked(BlockReason)

    /// Why the system is blocked.
    public enum BlockReason: Equatable {
        case microphoneDenied
        case accessibilityDenied
        case modelUnavailable
    }
}
