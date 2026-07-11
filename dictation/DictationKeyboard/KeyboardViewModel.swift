import Foundation
import DictationCoreBase

// MARK: - State

/// Names match docs/ios-dictation-architecture.md. `dictating` = a dictation is live (mirrors the app's
/// `capturing` phase); `awaiting` = stop posted, polling the App Group for the transcript.
enum KeyboardDictationState: Equatable {
    case idle, dictating, awaiting
}

// MARK: - KeyboardViewModel

/// Drives the keyboard extension's idle → dictating → awaiting state machine (see
/// docs/ios-dictation-architecture.md).
///
/// A keyboard extension cannot access the microphone, so it does not record itself. It hands off to the
/// container app via two Darwin signals only — START and STOP. The app writes the three App-Group
/// variables (heartbeat, capturing, pendingText); this keyboard READS them and never trusts local
/// memory. The app never signals back: we POLL `pendingText` and insert.
@MainActor
final class KeyboardViewModel: ObservableObject {

    // MARK: Published

    @Published private(set) var state: KeyboardDictationState = .idle
    @Published private(set) var statusMessage: String = "Tap mic to dictate"

    /// Wired by `KeyboardViewController` — inserts the final text into the host text field.
    var insertText: ((String) -> Void)?
    /// Wired by `KeyboardViewController` — opens the container app to record, since a keyboard
    /// extension can't access the microphone.
    var openApp: ((URL) -> Void)?

    // MARK: Private

    /// Resets the transient "Inserted ✓" status back to the idle prompt after a short delay.
    private var statusResetTask: Task<Void, Never>?
    /// Guards against a lost handoff: if `pendingText` never arrives, resets out of `.awaiting`.
    private var handoffTimeoutTask: Task<Void, Never>?
    /// How long to poll for `pendingText` before assuming the transcript was lost.
    private static let handoffTimeout: Duration = .seconds(20)

    // MARK: - Init

    init() {
        // NOTE: deliberately NO `done` Darwin observer. iOS keeps stale keyboard instances alive; a
        // dead instance's observer would fire, consume the transcript, and insert it into its
        // disconnected text proxy — so the text vanishes and the visible keyboard stays stuck. Instead
        // the VISIBLE instance (the one that posted stop) polls for the transcript and inserts via its
        // live proxy; viewWillAppear also checks on reappear. See toggleRecording / startHandoffTimeout.
    }

    deinit {
        // The Darwin observer was registered with an unretained pointer to self; remove it before we
        // deallocate so a later notification can't invoke a callback on a dangling pointer.
        CFNotificationCenterRemoveEveryObserver(
            CFNotificationCenterGetDarwinNotifyCenter(),
            Unmanaged.passUnretained(self).toOpaque())
    }

    // MARK: - Public control

    func toggleRecording() {
        // Decide START vs STOP from the SHARED capturing flag, not local `state` — iOS recreates the
        // keyboard when the user leaves+returns to the host app, wiping local state. Reading the App
        // Group means the mic button reliably stops the dictation that's actually running.
        if state == .awaiting { return }

        if DictationHandoff.isCapturing() {
            state = .awaiting
            statusMessage = "Transcribing…"
            DictationHandoff.trace("kbd", "stop tap → posting stop")
            DictationHandoff.post(DictationHandoff.stopNotification)
            startHandoffTimeout()
        } else {
            state = .dictating
            statusMessage = "Listening… tap to stop"
            // Seamless path (Wispr's "Flow Session"): if the container app is still alive in the
            // background from a recent dictation, just signal it — no `openURL`, so iOS never
            // foregrounds it and the user stays in the app they're typing in. Only when the session
            // has gone cold do we launch the app (the one-time app switch).
            if DictationHandoff.isSessionAlive() {
                DictationHandoff.trace("kbd", "start tap → session ALIVE, posting start (seamless)")
                DictationHandoff.post(DictationHandoff.startNotification)
            } else {
                DictationHandoff.trace("kbd", "start tap → session COLD, openURL (launch app)")
                openApp?(DictationHandoff.recordURL)
            }
        }
    }

    /// Called when the keyboard (re)appears: sync the button state to the actual session. If a
    /// dictation is live (e.g. the user returned from the launch), show "tap to stop"; otherwise idle.
    func syncFromSession() {
        if DictationHandoff.isCapturing() {
            if state != .dictating {
                state = .dictating
                statusMessage = "Listening… tap to stop"
                DictationHandoff.trace("kbd", "reappear — synced to RECORDING (session live)")
            }
        } else if state == .dictating {
            state = .idle
            statusMessage = "Tap mic to dictate"
        }
    }

    // MARK: - Handoff

    /// Insert any transcript the container app left in the App Group (called by the visible instance's
    /// poll after a stop, and on reappear). No-op when there's nothing pending.
    func checkForHandoff() {
        guard let text = DictationHandoff.consume() else { return }
        DictationHandoff.trace("kbd", "checkForHandoff — inserting \(text.count) chars")
        handoffTimeoutTask?.cancel()
        insertText?(text)
        state = .idle
        statusMessage = "Inserted ✓"
        scheduleStatusReset()
    }

    /// Poll `pendingText` until the transcript lands (or time out) — the app never signals us, and iOS
    /// may recreate this keyboard mid-transcribe, so polling is the only reliable pickup. Must never
    /// leave the keyboard stuck in `.awaiting`.
    private func startHandoffTimeout() {
        handoffTimeoutTask?.cancel()
        handoffTimeoutTask = Task { [weak self] in
            // checkForHandoff() synchronizes the App Group + consumes pendingText; keep trying until it lands.
            let deadline = Date().addingTimeInterval(20)
            while Date() < deadline {
                try? await Task.sleep(for: .milliseconds(250))
                guard let self, !Task.isCancelled, self.state == .awaiting else { return }
                self.checkForHandoff()
                if self.state != .awaiting { return }   // inserted — done
            }
            guard let self, !Task.isCancelled, self.state == .awaiting else { return }
            self.state = .idle
            self.statusMessage = "Didn't catch that — tap to retry"
            self.scheduleStatusReset()
            KBLog.error("handoff timed out — no transcript after 20s")
        }
    }

    /// Restore the idle prompt a few seconds after a terminal status message.
    private func scheduleStatusReset() {
        statusResetTask?.cancel()
        statusResetTask = Task { [weak self] in
            do { try await Task.sleep(for: .seconds(3)) } catch { return }
            guard let self, self.state == .idle else { return }
            self.statusMessage = "Tap mic to dictate"
        }
    }
}
