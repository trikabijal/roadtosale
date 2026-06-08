import Carbon
import CoreGraphics
import AppKit

// MARK: - Delegate

protocol HotkeyManagerDelegate: AnyObject {
    func hotkeyDidPress()
    func hotkeyDidRelease()
    /// Fired on every press of the configured key, regardless of hotkey mode. Used by
    /// the onboarding "press your key to test" step to confirm the key actually reaches us
    /// (the definitive conflict check). Default no-op.
    func hotkeyDidReceiveConfiguredKey()
}

extension HotkeyManagerDelegate {
    func hotkeyDidReceiveConfiguredKey() {}
}

// MARK: - HotkeyManager

/// Monitors the user-chosen activation key (`HotkeyConfig`) — Fn, a right-hand modifier,
/// or a function key.
///
/// Modifier keys (Fn / right ⌘⌥⌃) fire `kCGEventFlagsChanged`; function keys fire
/// `keyDown`/`keyUp`. With Accessibility granted we install a `CGEventTap` at
/// `.cghidEventTap` (the earliest interception point) and return `nil` to **suppress**
/// the event — this stops the Globe emoji-picker (Fn) or a stray F-key/modifier from
/// reaching the foreground app. Without Accessibility the tap can't be installed; we
/// fall back to `NSEvent.addGlobalMonitorForEvents`, which observes but cannot consume.
///
/// This class never prompts for Accessibility — `AppState`/`PermissionsService` own that.
final class HotkeyManager {
    weak var delegate: HotkeyManagerDelegate?

    /// When true, presses route to `hotkeyDidReceiveConfiguredKey()` only (no record
    /// start/stop) so the onboarding test can confirm the key without recording.
    var isTesting = false

    private(set) var config: HotkeyConfig

    private var eventTap: CFMachPort?
    private var runLoopSource: CFRunLoopSource?
    private var fallbackMonitor: Any?
    private var keyIsDown = false

    init(delegate: HotkeyManagerDelegate, config: HotkeyConfig) {
        self.delegate = delegate
        self.config = config
    }

    /// Install the listener. Assumes Accessibility is already granted (caller's
    /// responsibility); degrades to a non-suppressing monitor if the tap can't be made.
    /// Returns true if the suppressing event tap was installed.
    @discardableResult
    func start() -> Bool {
        stop()
        keyIsDown = false
        if AXIsProcessTrusted(), startEventTap() {
            return true
        }
        startFallbackMonitor()
        return false
    }

    func stop() {
        if let tap = eventTap {
            CGEvent.tapEnable(tap: tap, enable: false)
        }
        if let src = runLoopSource {
            CFRunLoopRemoveSource(CFRunLoopGetMain(), src, .commonModes)
        }
        eventTap = nil
        runLoopSource = nil
        if let monitor = fallbackMonitor {
            NSEvent.removeMonitor(monitor)
            fallbackMonitor = nil
        }
        keyIsDown = false
    }

    /// Switch to a new activation key and reinstall the listener live.
    func setConfig(_ newConfig: HotkeyConfig) {
        guard newConfig != config else { return }
        config = newConfig
        start()
    }

    // MARK: - CGEventTap (requires Accessibility — suppresses the event)

    private func startEventTap() -> Bool {
        let mask: CGEventMask =
            (1 << CGEventType.flagsChanged.rawValue) |
            (1 << CGEventType.keyDown.rawValue) |
            (1 << CGEventType.keyUp.rawValue)

        let tap = CGEvent.tapCreate(
            tap: .cghidEventTap,
            place: .headInsertEventTap,
            options: .defaultTap,
            eventsOfInterest: mask,
            callback: { _, type, event, refcon -> Unmanaged<CGEvent>? in
                guard let refcon else { return Unmanaged.passRetained(event) }
                let manager = Unmanaged<HotkeyManager>.fromOpaque(refcon).takeUnretainedValue()
                return manager.handleCGEvent(type: type, event: event)
            },
            userInfo: Unmanaged.passUnretained(self).toOpaque()
        )

        guard let tap else { return false }

        let src = CFMachPortCreateRunLoopSource(kCFAllocatorDefault, tap, 0)
        CFRunLoopAddSource(CFRunLoopGetMain(), src, .commonModes)
        CGEvent.tapEnable(tap: tap, enable: true)
        self.eventTap = tap
        self.runLoopSource = src
        return true
    }

    private func handleCGEvent(type: CGEventType, event: CGEvent) -> Unmanaged<CGEvent>? {
        // macOS disables a tap that responds too slowly or after wake-from-sleep; re-arm it
        // or the hotkey silently dies until relaunch.
        if type == .tapDisabledByTimeout || type == .tapDisabledByUserInput {
            if let tap = eventTap { CGEvent.tapEnable(tap: tap, enable: true) }
            return nil
        }

        let pass = Unmanaged.passRetained(event)

        switch config.match {
        case .fnModifier:
            guard type == .flagsChanged else { return pass }
            return transition(down: event.flags.contains(.maskSecondaryFn), pass: pass)

        case .modifier(let keyCode, let flag):
            guard type == .flagsChanged,
                  event.getIntegerValueField(.keyboardEventKeycode) == keyCode else { return pass }
            return transition(down: event.flags.contains(flag), pass: pass)

        case .function(let keyCode):
            guard (type == .keyDown || type == .keyUp),
                  event.getIntegerValueField(.keyboardEventKeycode) == keyCode else { return pass }
            // Swallow auto-repeat keyDowns without re-firing.
            if type == .keyDown, event.getIntegerValueField(.keyboardEventAutorepeat) != 0 {
                return nil
            }
            return transition(down: type == .keyDown, pass: pass)
        }
    }

    /// Apply a press/release edge. Suppresses the key only when the config says to
    /// (Fn / function keys); real modifiers pass through so shortcuts keep working.
    private func transition(down: Bool, pass: Unmanaged<CGEvent>) -> Unmanaged<CGEvent>? {
        let consume = config.suppresses
        if down && !keyIsDown {
            keyIsDown = true
            DispatchQueue.main.async { [weak self] in self?.firePress() }
            return consume ? nil : pass
        } else if !down && keyIsDown {
            keyIsDown = false
            DispatchQueue.main.async { [weak self] in self?.fireRelease() }
            return consume ? nil : pass
        }
        // No edge (e.g. another modifier changed in the same event) — let it through.
        return pass
    }

    private func firePress() {
        delegate?.hotkeyDidReceiveConfiguredKey()
        if !isTesting { delegate?.hotkeyDidPress() }
    }

    private func fireRelease() {
        guard !isTesting else { return }
        delegate?.hotkeyDidRelease()
    }

    // MARK: - Fallback: NSEvent global monitor (no Accessibility — cannot suppress)

    private func startFallbackMonitor() {
        let matching: NSEvent.EventTypeMask = [.flagsChanged, .keyDown, .keyUp]
        fallbackMonitor = NSEvent.addGlobalMonitorForEvents(matching: matching) { [weak self] event in
            guard let self else { return }
            switch self.config.match {
            case .fnModifier:
                guard event.type == .flagsChanged else { return }
                self.fallbackTransition(down: event.modifierFlags.contains(.function))
            case .modifier(let keyCode, _):
                guard event.type == .flagsChanged, Int64(event.keyCode) == keyCode else { return }
                // On flagsChanged NSEvent gives no clean per-key down flag; infer the edge
                // from tracked state (toggle on each matching event).
                self.fallbackTransition(down: !self.keyIsDown)
            case .function(let keyCode):
                guard Int64(event.keyCode) == keyCode else { return }
                if event.type == .keyDown { self.fallbackTransition(down: true) }
                else if event.type == .keyUp { self.fallbackTransition(down: false) }
            }
        }
    }

    private func fallbackTransition(down: Bool) {
        if down && !keyIsDown {
            keyIsDown = true
            firePress()
        } else if !down && keyIsDown {
            keyIsDown = false
            fireRelease()
        }
    }
}
