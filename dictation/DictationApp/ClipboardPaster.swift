import AppKit
import CoreGraphics

// MARK: - ClipboardPaster

final class ClipboardPaster {

    /// Writes `text` to the system clipboard. If `autoPaste` is true, also fires a synthetic
    /// ⌘V event 50 ms later so the text lands in the frontmost window without user action.
    func writeAndPaste(text: String, autoPaste: Bool) {
        // Write to clipboard
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(text, forType: .string)

        guard autoPaste else { return }

        // 50 ms delay — gives the frontmost window time to regain focus after any
        // interaction with the menu bar popover.
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.05) {
            self.sendCmdV()
        }
    }

    // MARK: - Private

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
