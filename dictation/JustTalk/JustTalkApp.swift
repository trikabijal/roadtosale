import AppKit
import Combine
import Darwin
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
/// item. Idle/recording use the **Just Talk mark** (the brand mic); transcribing keeps the waveform.
/// All are template images — `contentTintColor` colors them (nil idle = adapts to the menu bar).
enum MenuBarGlyph {
    /// The menu-bar image for a state. Recording/transcribing are pre-tinted **non-template** images
    /// so their color actually renders — a template image + `contentTintColor` is ignored by the
    /// status bar until it's hovered, which is why "recording" looked black. Idle stays a template
    /// so it adapts to the light/dark menu bar.
    static func image(for state: DictationState) -> NSImage? {
        switch state {
        case .idle:         return mark()                                                   // template — adapts
        case .recording:    return tinted(mark(), NSColor(srgbRed: 1.0, green: 0.271, blue: 0.227, alpha: 1))  // #FF453A red — matches the HUD wave
        case .transcribing: return tinted(symbol("waveform"), NSColor(srgbRed: 1.0, green: 0.624, blue: 0.039, alpha: 1))  // #FF9F0A amber
        }
    }

    /// The Just Talk mark from the asset catalog, as a menu-bar-sized template.
    private static func mark() -> NSImage? {
        let img = NSImage(named: "JustTalkMark")
        img?.isTemplate = true
        img?.size = NSSize(width: 18, height: 18)
        return img
    }

    private static func symbol(_ name: String) -> NSImage? {
        let img = NSImage(systemSymbolName: name, accessibilityDescription: "Just Talk")
        img?.isTemplate = true
        img?.size = NSSize(width: 18, height: 18)
        return img
    }

    /// Render a template image filled with a solid color as a NON-template image, so the status bar
    /// shows exactly this color instead of applying its own menu-bar tint. Draws the base image FIRST
    /// (rasterizing the vector mark), THEN tints with source-atop — the previous copy-then-fill left
    /// vector images un-rasterized, so the recording glyph came out blank (no red in the menu bar).
    private static func tinted(_ base: NSImage?, _ color: NSColor) -> NSImage? {
        guard let base else { return nil }
        let size = NSSize(width: 18, height: 18)
        let out = NSImage(size: size)
        out.lockFocus()
        let rect = NSRect(origin: .zero, size: size)
        base.draw(in: rect, from: .zero, operation: .sourceOver, fraction: 1)
        color.set()
        rect.fill(using: .sourceAtop)
        out.unlockFocus()
        out.isTemplate = false
        return out
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
    // Held open for the whole process lifetime — closing (or process death) releases the lock.
    private var instanceLockFD: Int32 = -1

    func applicationDidFinishLaunching(_ notification: Notification) {
        // Single-instance guard. Two copies (a stale /Applications build + the dev build, or a
        // duplicated login item) each install a global hotkey tap and grab the mic, so the instance
        // you're watching often isn't the one recording — the "icon won't light up / no HUD / wrong
        // History" confusion. A process-wide file lock is the authority: it covers copies with
        // DIFFERENT bundle ids/paths (which Info.plist's LSMultipleInstancesProhibited and a
        // runningApplications-by-bundle-id check both miss) and closes the both-launch-at-once race.
        guard acquireSingleInstanceLock() else {
            NSLog("JustTalk: another instance already holds the single-instance lock — exiting.")
            // Best-effort UX: bring the existing instance forward if it shares our bundle id.
            let me = NSRunningApplication.current
            NSWorkspace.shared.runningApplications.first {
                $0.bundleIdentifier == me.bundleIdentifier && $0.processIdentifier != me.processIdentifier
            }?.activate()
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
        button.image = MenuBarGlyph.image(for: state)
        // Color is baked into the image now (recording = red, transcribing = amber, idle = template
        // that adapts to the menu bar) — no contentTintColor, which the status bar was ignoring.
        button.contentTintColor = nil
    }

    /// Take an exclusive, non-blocking advisory lock on a fixed file. Returns false if another
    /// process already holds it (→ this instance should exit). The lock lives at a path derived
    /// from a constant, NOT the bundle id, so every copy of the app contends for the SAME lock.
    /// flock is released automatically when the holder process exits — even on crash — so there's
    /// no stale-lock problem. The fd is retained in `instanceLockFD` for the process lifetime.
    private func acquireSingleInstanceLock() -> Bool {
        guard let base = try? FileManager.default.url(
            for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true
        ) else { return true }  // can't resolve a lock location — don't block launch
        let dir = base.appendingPathComponent("com.trika.dictation", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let path = dir.appendingPathComponent("instance.lock").path

        let fd = open(path, O_CREAT | O_RDWR, 0o644)
        guard fd != -1 else { return true }  // can't open the lock file — fail open, don't block
        if flock(fd, LOCK_EX | LOCK_NB) != 0 {
            close(fd)
            return false                      // EWOULDBLOCK → another instance holds the lock
        }
        instanceLockFD = fd                   // keep open for the whole process lifetime
        return true
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
