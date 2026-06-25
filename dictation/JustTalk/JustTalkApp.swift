import AppKit
import Combine
import SwiftUI
import DictationCore

@main
struct JustTalkApp: App {
    // The menu-bar presence is an AppKit NSStatusItem owned by the delegate (see below) rather
    // than a SwiftUI MenuBarExtra. MenuBarExtra gives no control over the status item's position
    // and is the first thing macOS hides when the menu bar runs out of room (notch + a frontmost
    // app with many menus) — the "our icon disappears when other software loads" report. An
    // NSStatusItem with an `autosaveName` gets a STABLE, user-draggable slot the OS remembers
    // across launches, which is the strongest guarantee macOS actually offers here.
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate

    var body: some Scene {
        // Settings stays a SwiftUI scene (opened via the standard selector from the popover).
        Settings {
            SettingsView()
                .environmentObject(AppState.shared)
        }
    }
}

// MARK: - Menu-bar glyph mapping

/// Single source of truth for the menu-bar glyph per dictation state. Used by the AppKit status
/// item. Recording/transcribing get a tint; idle is a template image that adapts to the menu bar.
enum MenuBarGlyph {
    static func symbolAndTint(for state: DictationState) -> (symbol: String, tint: NSColor?) {
        switch state {
        case .idle:         return ("mic", nil)
        case .recording:    return ("mic.fill", .systemRed)
        case .transcribing: return ("waveform", .systemOrange)
        }
    }
}

// MARK: - AppDelegate (status item + popover)

/// Owns the menu-bar status item and its popover. AppKit-managed so we control the item's
/// position/persistence — the fix for the vanishing icon — and so the popover content can open
/// the History/Settings windows directly.
final class AppDelegate: NSObject, NSApplicationDelegate {
    private var statusItem: NSStatusItem?
    private let popover = NSPopover()
    private var stateCancellable: AnyCancellable?

    func applicationDidFinishLaunching(_ notification: Notification) {
        // Single-instance guard. Two copies (a stale /Applications build + the dev build, or a
        // duplicated login item) each install a global hotkey tap and grab the mic, so the instance
        // you're watching often isn't the one recording — the "icon won't light up / no HUD / wrong
        // History" confusion. If another instance with our bundle id is already running, hand off to
        // it and quit, so exactly one menu-bar presence exists.
        let me = NSRunningApplication.current
        let other = NSWorkspace.shared.runningApplications.first {
            $0.bundleIdentifier == me.bundleIdentifier && $0.processIdentifier != me.processIdentifier
        }
        if let other {
            NSLog("JustTalk: another instance (pid \(other.processIdentifier)) is already running — exiting to stay single-instance.")
            other.activate()
            NSApp.terminate(nil)
            return
        }

        let appState = AppState.shared

        // Popover hosting the existing SwiftUI menu content. `.transient` closes it on an
        // outside click, matching the old MenuBarExtra(.window) behaviour.
        popover.behavior = .transient
        popover.contentViewController = NSHostingController(
            rootView: MenuBarView().environmentObject(appState)
        )

        // Variable-length item so the glyph sizes naturally; autosaveName pins a stable slot the
        // user can ⌘-drag and macOS remembers — unlike MenuBarExtra, which reshuffles and hides.
        let item = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
        item.autosaveName = "JustTalkStatusItem"
        item.button?.action = #selector(togglePopover)
        item.button?.target = self
        statusItem = item

        updateIcon(for: appState.dictationState)
        // Keep the glyph in sync with recording state.
        stateCancellable = appState.$dictationState
            .receive(on: RunLoop.main)
            .sink { [weak self] state in self?.updateIcon(for: state) }
    }

    private func updateIcon(for state: DictationState) {
        guard let button = statusItem?.button else { return }
        let (symbol, tint) = MenuBarGlyph.symbolAndTint(for: state)
        let image = NSImage(systemSymbolName: symbol, accessibilityDescription: "Just Talk")
        image?.isTemplate = (tint == nil)   // template adapts to light/dark menu bar when idle
        button.image = image
        button.contentTintColor = tint
    }

    @objc private func togglePopover() {
        guard let button = statusItem?.button else { return }
        if popover.isShown {
            popover.performClose(nil)
        } else {
            popover.show(relativeTo: button.bounds, of: button, preferredEdge: .minY)
            // Bring the popover's window forward so its controls are immediately interactive.
            popover.contentViewController?.view.window?.makeKey()
        }
    }
}
