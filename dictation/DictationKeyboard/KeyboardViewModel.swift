import Foundation
import DictationCoreBase

// MARK: - KeyboardPresentation

/// The COMPLETE visible state of the keyboard, as one value. Every element the user sees — the wave, the
/// button, the accent colour, the label — reads from a single `KeyboardPresentation`, and it is
/// recomputed as ONE unit from the shared App-Group variables on every poll (see `render`).
///
/// Bundling these fields in one struct is the whole point, not a convenience: it makes it *impossible*
/// for a future change to update one element's state without the others. They are a single snapshot of
/// "speaking or not speaking", never four independently-mutated variables — which is exactly what caused
/// the golden-wave-vs-gray-wave / stuck-button desync. If you touch one field here, you touch them all,
/// together, derived from the same read of `capturing`.
struct KeyboardPresentation: Equatable {
    /// The only two states a keyboard can be in. Derived from the shared `capturing` flag — nothing else.
    enum Mode: Equatable { case notSpeaking, speaking }

    var mode: Mode
    /// Wave amplitude 0…~1. Zero (and the wave hidden) unless speaking.
    var level: Float
    /// The status line under the stage.
    var label: String

    var isSpeaking: Bool { mode == .speaking }

    static let idle = KeyboardPresentation(mode: .notSpeaking, level: 0, label: "Tap anywhere to dictate")
}

// MARK: - KeyboardViewModel

/// The keyboard has exactly TWO states — **speaking** or **not speaking** — and its entire appearance is
/// a pure function of the shared `capturing` App-Group variable, never of local memory. See
/// docs/ios-dictation-architecture.md.
///
/// WHY there is no local state machine here:
/// A keyboard extension cannot access the microphone, so a separate container-app process records, and
/// the two processes coordinate ONLY through three App-Group variables (heartbeat, capturing,
/// pendingText). iOS also destroys + recreates this keyboard on every host-app switch, wiping any
/// in-memory state. So the single reliable truth is the shared variable — a local `state` enum that we
/// "keep in sync" will always eventually drift (that was the desync bug).
///
/// Therefore this view model keeps **no authoritative state of its own**. One continuous poll reads the
/// shared variables and renders a single `KeyboardPresentation`; the view draws only that. The
/// keyboard's only outputs are the two Darwin signals START / STOP — it never decides its own
/// appearance, it only reflects the variables.
@MainActor
final class KeyboardViewModel: ObservableObject {

    // MARK: Published — a SINGLE snapshot. All UI reads from this one value.

    @Published private(set) var presentation = KeyboardPresentation.idle
    /// Compact usage stats (words / WPM / streak) the app publishes — shown in the idle carousel. Read
    /// from the App Group, never computed here (the keyboard can't open the telemetry DB). Not part of
    /// the speaking/not-speaking state; it's static content refreshed when the keyboard appears.
    @Published private(set) var stats: DictationHandoff.KbdStats?

    /// Wired by `KeyboardViewController` — inserts the final text into the host text field.
    var insertText: ((String) -> Void)?
    /// Wired by `KeyboardViewController` — opens the container app to record (a keyboard can't use the mic).
    var openApp: ((URL) -> Void)?

    // MARK: Private

    /// The single continuous reader of the shared variables. Runs while the keyboard is on screen.
    private var pollTask: Task<Void, Never>?
    /// Post-STOP bookkeeping — NOT a visual state. We posted STOP and are waiting for the transcript;
    /// lets the render show "Transcribing…" and lets the watchdog know a stop is outstanding.
    private var awaitingTranscript = false
    /// Give-up time for a transcript that never lands (app alive but lost it).
    private var transcriptDeadline: Date?
    /// A short-lived label ("Inserted ✓", "Lost that one…") that overrides the state label until it expires.
    private var hint: String?
    private var hintDeadline: Date?
    /// Verifies a seamless START actually woke the app (else it was a stale-heartbeat corpse → launch).
    private var startAckTask: Task<Void, Never>?
    /// Verifies a STOP was processed (else the app died mid-record, leaving `capturing` stuck true).
    private var stopAckTask: Task<Void, Never>?

    private static let pollInterval: Duration = .milliseconds(80)
    private static let transcriptTimeout: TimeInterval = 20
    private static let hintDuration: TimeInterval = 3

    // MARK: - Poll lifecycle (driven by viewWillAppear / viewWillDisappear)

    /// Begin continuously reflecting the shared variables into the UI. Idempotent. This is the ONLY
    /// writer of `presentation`, so the UI can never drift from `capturing`.
    func startReflecting() {
        stats = DictationHandoff.readStats()   // refresh the idle carousel each time we appear
        guard pollTask == nil else { return }
        pollTask = Task { @MainActor [weak self] in
            while !Task.isCancelled {
                self?.render()
                try? await Task.sleep(for: Self.pollInterval)
            }
        }
    }

    /// Stop polling when the keyboard leaves the screen. iOS may recreate this keyboard on the next
    /// appear; it re-reads the shared variables from scratch, so nothing is lost by stopping here.
    func stopReflecting() {
        pollTask?.cancel(); pollTask = nil
        // Cancel the watchdogs too — a stray stopAckTask outliving this instance would fire a GLOBAL
        // `setCapturing(false)` (could clear a live session), and a stray startAckTask would `openApp`
        // after the user navigated away. Also clear the post-STOP bookkeeping so a fresh appear starts clean.
        startAckTask?.cancel(); startAckTask = nil
        stopAckTask?.cancel(); stopAckTask = nil
        awaitingTranscript = false
        transcriptDeadline = nil
        hint = nil; hintDeadline = nil
        presentation = .idle
    }

    /// Read the shared variables ONCE and rebuild the entire `KeyboardPresentation` as a single unit —
    /// wave, colour, and label are all derived from the same read of `capturing`, so they cannot disagree.
    private func render() {
        let capturing = DictationHandoff.isCapturing()

        // Pick up a finished transcript the app wrote. The app never signals us — we poll and insert
        // from the VISIBLE instance's live text proxy (a stale recreated instance would insert nowhere).
        if let text = DictationHandoff.consume() {
            awaitingTranscript = false; transcriptDeadline = nil
            insertText?(text)   // the text appearing in the field IS the feedback — no "Inserted" hint
            DictationHandoff.trace("kbd", "poll — inserted \(text.count) chars")
        }

        // Fast path: the app signalled the take produced nothing (empty / error) — clear immediately
        // instead of waiting out the transcript timeout.
        if awaitingTranscript, DictationHandoff.consumeNoResult() {
            awaitingTranscript = false; transcriptDeadline = nil
            setHint("Didn't catch that — tap to retry")
        }

        // App alive but the transcript never landed → give up (backstop for a lost signal).
        if awaitingTranscript, let d = transcriptDeadline, Date() > d {
            awaitingTranscript = false; transcriptDeadline = nil
            setHint("Didn't catch that — tap to retry")
            KBLog.error("handoff timed out — no transcript after \(Self.transcriptTimeout)s")
        }

        // Build the ONE snapshot. Every field comes from this same tick's reads — they update together.
        let label: String
        if let hint, let hd = hintDeadline, Date() < hd {
            label = hint
        } else {
            self.hint = nil; self.hintDeadline = nil
            label = capturing ? "Listening… tap to finish"
                  : awaitingTranscript ? "Transcribing…"
                  : "Tap anywhere to dictate"
        }
        presentation = KeyboardPresentation(
            mode: capturing ? .speaking : .notSpeaking,
            level: capturing ? DictationHandoff.readLevel() : 0,
            label: label)
    }

    // MARK: - The only output: START / STOP

    /// A tap on the keyboard surface. Decides START vs STOP purely from the shared `capturing` flag,
    /// then posts a Darwin signal. Deliberately does NOT set any visual state — the poll reflects
    /// `capturing` once the app flips it, so the button/wave can never lie about what's really happening.
    func toggleRecording() {
        if DictationHandoff.isCapturing() {
            DictationHandoff.trace("kbd", "tap → capturing → post STOP")
            DictationHandoff.post(DictationHandoff.stopNotification)
            awaitingTranscript = true
            transcriptDeadline = Date().addingTimeInterval(Self.transcriptTimeout)
            armStopAck()
        } else if DictationHandoff.isSessionAlive() {
            // Seamless path (Wispr's "Flow Session"): the app is still warm in the background, so just
            // signal it — no openURL, so iOS never foregrounds it and the user stays where they're typing.
            DictationHandoff.trace("kbd", "tap → session ALIVE → post START (seamless)")
            DictationHandoff.post(DictationHandoff.startNotification)
            armStartAck()
        } else {
            DictationHandoff.trace("kbd", "tap → session COLD → openURL (launch app)")
            openApp?(DictationHandoff.recordURL)
        }
    }

    // MARK: - Watchdogs (operate on the shared variable; the poll reflects the result)

    /// After a seamless START, if `capturing` hasn't gone true the app was a stale-heartbeat corpse
    /// (killed but its <8s heartbeat still read "alive") → cold-launch it via openURL.
    private func armStartAck() {
        startAckTask?.cancel()
        startAckTask = Task { @MainActor [weak self] in
            try? await Task.sleep(for: .milliseconds(800))
            guard let self, !Task.isCancelled, !DictationHandoff.isCapturing() else { return }
            DictationHandoff.trace("kbd", "START not acked in 800ms → app dead → openURL fallback")
            self.openApp?(DictationHandoff.recordURL)
        }
    }

    /// After a STOP, if `capturing` is still true after 2s the app died mid-record (suspended) with the
    /// flag stuck — the keyboard would otherwise jam forever (every tap re-posts stop to a corpse).
    /// Recovery: clear the stuck flag ourselves. The one-writer rule (app is sole writer) is suspended
    /// ONLY for this death case, because the writer is gone. The poll then flips to not-speaking and the
    /// next tap is a clean START that relaunches the app.
    private func armStopAck() {
        stopAckTask?.cancel()
        stopAckTask = Task { @MainActor [weak self] in
            try? await Task.sleep(for: .seconds(2))
            guard let self, !Task.isCancelled, DictationHandoff.isCapturing() else { return }  // acked → fine
            DictationHandoff.trace("kbd", "STOP not acked in 2s → app died mid-record → clear stuck flag")
            DictationHandoff.setCapturing(false)
            self.awaitingTranscript = false; self.transcriptDeadline = nil
            self.setHint("Lost that one — tap to retry")
        }
    }

    // MARK: - Transient hint

    /// Arm a short-lived label the render will show (over the state label) until it expires.
    private func setHint(_ message: String) {
        hint = message
        hintDeadline = Date().addingTimeInterval(Self.hintDuration)
    }

    deinit {
        // No Darwin observer is registered (the app never signals us — we poll), but remove defensively
        // so a stray registration can never call back into freed memory.
        CFNotificationCenterRemoveEveryObserver(
            CFNotificationCenterGetDarwinNotifyCenter(),
            Unmanaged.passUnretained(self).toOpaque())
    }
}
