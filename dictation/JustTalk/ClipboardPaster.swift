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
    /// `onPasteSkipped` is called when the intended target never became frontmost within the
    /// wait window — we deliberately do NOT synthesize ⌘V (it could land in the wrong app);
    /// the transcript is left on the clipboard for the user to paste manually.
    func writeAndPaste(text: String, autoPaste: Bool, targetApp: NSRunningApplication? = nil,
                       onPasteSkipped: (() -> Void)? = nil) {
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
        // The pasteboard state right after we wrote the transcript. ⌘V only *reads*, so a
        // clean paste leaves this unchanged; if it moves, the user copied something new and
        // we must not clobber it on restore (F5).
        let writtenChangeCount = pasteboard.changeCount

        // Re-focus the app that was frontmost when recording started, then paste once it's
        // actually frontmost — so the paste lands where the user intended even if the
        // menu-bar panel stole focus, and never fires into a window that isn't ready yet.
        targetApp?.activate()
        pasteWhenFocused(targetApp: targetApp, attempt: 0, myGeneration: myGeneration, paste: { [weak self] in
            guard let self else { return }
            self.sendCmdV()

            // Restore the user's clipboard only AFTER the target app has read the pasteboard.
            // 700 ms is generous on purpose — slow apps (Terminal especially) read ⌘V late.
            // Guarded so only the most recent paste restores, and only if our transcript is
            // still the top item (changeCount unchanged) — otherwise a newer user copy would
            // be destroyed (F5).
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.7) {
                guard myGeneration == self.generation else { return }
                if pasteboard.changeCount == writtenChangeCount {
                    self.restore(self.burstSnapshot ?? [], to: pasteboard)
                }
                self.burstSnapshot = nil
                self.restorePending = false
            }
        }, onSkip: { [weak self] in
            guard let self else { return }
            // Target never came frontmost — leave the transcript on the clipboard (don't restore
            // over it) and let the caller surface a "couldn't paste" notice.
            self.burstSnapshot = nil
            self.restorePending = false
            onPasteSkipped?()
        })
    }

    /// Fire `paste` once the target app is frontmost, polling at 0.1 s up to ~0.6 s. Replaces
    /// a blind fixed delay so the synthetic ⌘V lands in the right, focused window. Aborts if a
    /// newer dictation burst supersedes this one.
    private func pasteWhenFocused(targetApp: NSRunningApplication?, attempt: Int,
                                  myGeneration: Int,
                                  paste: @escaping () -> Void, onSkip: @escaping () -> Void) {
        guard myGeneration == generation else { return }
        let maxAttempts = 6
        let focused = targetApp == nil
            || NSWorkspace.shared.frontmostApplication?.processIdentifier == targetApp?.processIdentifier
        if focused {
            paste()
        } else if attempt >= maxAttempts {
            onSkip()   // never became frontmost — refuse to paste into whatever IS frontmost
        } else {
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.1) { [weak self] in
                self?.pasteWhenFocused(targetApp: targetApp, attempt: attempt + 1,
                                       myGeneration: myGeneration, paste: paste, onSkip: onSkip)
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
        // Tag our synthetic events so the hotkey tap ignores them and can't self-trigger (F7).
        source?.userData = justTalkSyntheticEventUserData

        let keyDown = CGEvent(keyboardEventSource: source, virtualKey: vKeyCode, keyDown: true)
        keyDown?.flags = .maskCommand
        keyDown?.post(tap: .cghidEventTap)

        let keyUp = CGEvent(keyboardEventSource: source, virtualKey: vKeyCode, keyDown: false)
        keyUp?.flags = .maskCommand
        keyUp?.post(tap: .cghidEventTap)
    }
}
