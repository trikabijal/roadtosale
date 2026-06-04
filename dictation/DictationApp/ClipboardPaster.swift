import AppKit
import CoreGraphics

// MARK: - ClipboardPaster

final class ClipboardPaster {

    /// Writes `text` to the clipboard and, when `autoPaste` is true, fires a synthetic
    /// ⌘V into the frontmost window — then **restores the user's previous clipboard**
    /// so dictation never clobbers what they had copied.
    ///
    /// When `autoPaste` is false the transcript is intentionally left on the clipboard
    /// for the user to paste manually, so there is nothing to restore.
    func writeAndPaste(text: String, autoPaste: Bool) {
        let pasteboard = NSPasteboard.general

        guard autoPaste else {
            pasteboard.clearContents()
            pasteboard.setString(text, forType: .string)
            return
        }

        // Snapshot the current clipboard before we overwrite it.
        let saved = snapshot(pasteboard)

        pasteboard.clearContents()
        pasteboard.setString(text, forType: .string)

        // 50 ms: let the frontmost window regain focus after the menu-bar interaction,
        // then paste.
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.05) { [weak self] in
            self?.sendCmdV()

            // 200 ms after the paste: the target app has read the pasteboard, so it is
            // safe to put the user's original contents back.
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.2) {
                self?.restore(saved, to: pasteboard)
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
