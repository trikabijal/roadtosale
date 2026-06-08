import AppKit
import CoreGraphics

// MARK: - ClipboardPaster

/// Writes transcripts to the clipboard, pastes them, and restores the user's prior
/// clipboard. All methods run on the main thread (called from `@MainActor` `AppState`
/// and from `DispatchQueue.main`), so the mutable bookkeeping below needs no locking.
final class ClipboardPaster {

    /// Increments on every paste. A scheduled restore only fires if it's still the
    /// latest paste — so back-to-back dictations don't clobber each other.
    private var generation = 0
    /// The user's clipboard captured at the START of a paste burst, restored at the end.
    private var burstSnapshot: [NSPasteboardItem]?
    private var restorePending = false

    /// Writes `text` to the clipboard and, when `autoPaste` is true, fires a synthetic
    /// ⌘V into the frontmost window — then **restores the user's previous clipboard**
    /// so dictation never clobbers what they had copied.
    ///
    /// When `autoPaste` is false the transcript is intentionally left on the clipboard
    /// for the user to paste manually, so there is nothing to restore.
    func writeAndPaste(text: String, autoPaste: Bool, targetApp: NSRunningApplication? = nil) {
        let pasteboard = NSPasteboard.general

        guard autoPaste else {
            // Invalidate any in-flight restore so a pending burst restore can't clobber
            // this manual write.
            generation += 1
            restorePending = false
            burstSnapshot = nil
            pasteboard.clearContents()
            pasteboard.setString(text, forType: .string)
            return
        }

        // Snapshot the user's real clipboard only at the start of a burst — intermediate
        // transcripts must not become the "saved" contents.
        if !restorePending {
            burstSnapshot = snapshot(pasteboard)
            restorePending = true
        }
        generation += 1
        let myGeneration = generation

        pasteboard.clearContents()
        pasteboard.setString(text, forType: .string)

        // Re-focus the app that was frontmost when recording started, so the paste lands
        // where the user intended even if the menu-bar panel or anything else stole focus.
        targetApp?.activate()

        // Let activation/focus settle, then paste.
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.15) { [weak self] in
            self?.sendCmdV()

            // Restore the user's clipboard only AFTER the target app has read the pasteboard.
            // 700 ms is generous on purpose — slow apps (Terminal especially) read ⌘V late,
            // and restoring too early left them pasting an already-restored, empty clipboard.
            // Only the most recent paste restores; superseded bursts do nothing.
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.7) {
                guard let self, myGeneration == self.generation else { return }
                self.restore(self.burstSnapshot ?? [], to: pasteboard)
                self.burstSnapshot = nil
                self.restorePending = false
            }
        }
    }

    // MARK: - Snapshot / restore

    /// Deep-copies every item and every representation currently on the pasteboard.
    /// We copy into fresh `NSPasteboardItem`s because an item already owned by a
    /// pasteboard cannot be re-written.
    private func snapshot(_ pasteboard: NSPasteboard) -> [NSPasteboardItem] {
        var copies: [NSPasteboardItem] = []
        for item in pasteboard.pasteboardItems ?? [] {
            let copy = NSPasteboardItem()
            for type in item.types {
                if let data = item.data(forType: type) {
                    copy.setData(data, forType: type)
                }
            }
            copies.append(copy)
        }
        return copies
    }

    private func restore(_ items: [NSPasteboardItem], to pasteboard: NSPasteboard) {
        pasteboard.clearContents()
        guard !items.isEmpty else { return }  // clipboard was empty before — leave it empty
        pasteboard.writeObjects(items)
    }

    // MARK: - Synthetic ⌘V

    private func sendCmdV() {
        let vKeyCode: CGKeyCode = 0x09  // kVK_ANSI_V

        let source = CGEventSource(stateID: .hidSystemState)

        let keyDown = CGEvent(keyboardEventSource: source, virtualKey: vKeyCode, keyDown: true)
        keyDown?.flags = .maskCommand
        keyDown?.post(tap: .cghidEventTap)

        let keyUp = CGEvent(keyboardEventSource: source, virtualKey: vKeyCode, keyDown: false)
        keyUp?.flags = .maskCommand
        keyUp?.post(tap: .cghidEventTap)
    }
}
