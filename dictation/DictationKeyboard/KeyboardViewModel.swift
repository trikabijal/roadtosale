import Foundation
import DictationCoreBase

// MARK: - State

enum KeyboardDictationState: Equatable {
    case idle, recording, transcribing
}

// MARK: - KeyboardViewModel

/// Drives the keyboard extension's idle → recording → transcribing state machine.
///
/// A keyboard extension cannot access the microphone, so it does not record or transcribe itself.
/// Instead it hands off to the container app: tap-to-start launches the app (which records
/// invisibly), tap-to-stop Darwin-signals the app to stop; the app transcribes, writes the result
/// to the shared App Group, and posts `done`. We then read the App Group and insert the text.
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
    /// Guards against a lost handoff: if the app never posts `done`, resets out of `.transcribing`.
    private var handoffTimeoutTask: Task<Void, Never>?
    /// How long to wait for the app's `done` before assuming the handoff was lost.
    private static let handoffTimeout: Duration = .seconds(20)

    // MARK: - Init

    init() {
        startListeningForDone()
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
        if state == .transcribing { return }

        if DictationHandoff.isCapturing() {
            state = .transcribing
            statusMessage = "Transcribing…"
            DictationHandoff.trace("kbd", "stop tap → posting stop")
            DictationHandoff.post(DictationHandoff.stopNotification)
            startHandoffTimeout()
        } else {
            state = .recording
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
            if state != .recording {
                state = .recording
                statusMessage = "Listening… tap to stop"
                DictationHandoff.trace("kbd", "reappear — synced to RECORDING (session live)")
            }
        } else if state == .recording {
            state = .idle
            statusMessage = "Tap mic to dictate"
        }
    }

    // MARK: - Handoff

    /// Register for the app's `done` signal so we insert the moment the transcript is ready — the app
    /// finishes asynchronously in the background after the keyboard has already reappeared.
    private func startListeningForDone() {
        DictationHandoff.observe(DictationHandoff.doneNotification,
                                 observer: Unmanaged.passUnretained(self).toOpaque()) { _, observer, _, _, _ in
            guard let observer else { return }
            let vm = Unmanaged<KeyboardViewModel>.fromOpaque(observer).takeUnretainedValue()
            Task { @MainActor in vm.checkForHandoff() }
        }
    }

    /// Insert any transcript the container app left in the App Group (called on the `done` signal and
    /// when the keyboard reappears). No-op when there's nothing pending.
    func checkForHandoff() {
        guard let text = DictationHandoff.consume() else {
            DictationHandoff.trace("kbd", "checkForHandoff — nothing pending")
            return
        }
        DictationHandoff.trace("kbd", "checkForHandoff — inserting \(text.count) chars")
        handoffTimeoutTask?.cancel()
        insertText?(text)
        state = .idle
        statusMessage = "Inserted ✓"
        scheduleStatusReset()
    }

    /// Fall back to idle if the container app never reports `done` — a lost handoff must not leave the
    /// keyboard permanently stuck in `.transcribing` with the mic button disabled.
    private func startHandoffTimeout() {
        handoffTimeoutTask?.cancel()
        handoffTimeoutTask = Task { [weak self] in
            try? await Task.sleep(for: Self.handoffTimeout)
            guard let self, !Task.isCancelled, self.state == .transcribing else { return }
            self.state = .idle
            self.statusMessage = "Didn't catch that — tap to retry"
            self.scheduleStatusReset()
            KBLog.error("handoff timed out — no `done` from container app")
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
