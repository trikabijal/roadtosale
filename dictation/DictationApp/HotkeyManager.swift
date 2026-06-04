import Carbon
import CoreGraphics
import AppKit

// MARK: - Delegate

protocol HotkeyManagerDelegate: AnyObject {
    func hotkeyDidPress()
    func hotkeyDidRelease()
}

// MARK: - HotkeyManager

/// Monitors the **Fn / Globe** key.
///
/// Fn is a modifier key — pressing or releasing it fires `kCGEventFlagsChanged`
/// (not `keyDown`/`keyUp`). We detect `CGEventFlags.maskSecondaryFn` being
/// set (press) or cleared (release).
///
/// With Accessibility granted we install a `CGEventTap` at `.cghidEventTap`
/// (the earliest possible interception point) and return `nil` to suppress
/// the event — this prevents the Globe emoji-picker from opening while the
/// tap is active. Without Accessibility we fall back to
/// `NSEvent.addGlobalMonitorForEvents`, which sees the events but cannot
/// consume them, so the emoji-picker may open alongside recording.
final class HotkeyManager {
    weak var delegate: HotkeyManagerDelegate?

    private var eventTap: CFMachPort?
    private var runLoopSource: CFRunLoopSource?
    private var fallbackMonitor: Any?
    private var fnIsDown = false

    init(delegate: HotkeyManagerDelegate) {
        self.delegate = delegate
    }

    func start() {
        // Prompt for Accessibility permission; if already granted, tap starts immediately.
        let options: NSDictionary = [
            kAXTrustedCheckOptionPrompt.takeUnretainedValue() as NSString: true
        ]
        if AXIsProcessTrustedWithOptions(options) {
            startEventTap()
        } else {
            // CGEventTap cannot be installed without Accessibility.
            // Fall back to NSEvent global monitor — recording works, but
            // the Fn key event reaches the OS (emoji-picker may appear).
            startFallbackMonitor()
        }
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
    }

    // MARK: - CGEventTap (requires Accessibility — suppresses the Fn/Globe event)

    private func startEventTap() {
        // Fn fires kCGEventFlagsChanged. We also include keyDown/keyUp so the
        // tap is wired for future key additions without rebuilding it.
        let mask: CGEventMask =
            (1 << CGEventType.flagsChanged.rawValue) |
            (1 << CGEventType.keyDown.rawValue) |
            (1 << CGEventType.keyUp.rawValue)

        let tap = CGEvent.tapCreate(
            tap: .cghidEventTap,
            place: .headInsertEventTap,
            options: .defaultTap,
            eventsOfInterest: mask,
            callback: { proxy, type, event, refcon -> Unmanaged<CGEvent>? in
                guard let refcon else { return Unmanaged.passRetained(event) }
                let manager = Unmanaged<HotkeyManager>.fromOpaque(refcon).takeUnretainedValue()
                return manager.handleCGEvent(proxy: proxy, type: type, event: event)
            },
            userInfo: Unmanaged.passUnretained(self).toOpaque()
        )

        guard let tap else {
            // Failed to install tap even though AXIsProcessTrusted returned true.
            startFallbackMonitor()
            return
        }

        let src = CFMachPortCreateRunLoopSource(kCFAllocatorDefault, tap, 0)
        CFRunLoopAddSource(CFRunLoopGetMain(), src, .commonModes)
        CGEvent.tapEnable(tap: tap, enable: true)

        self.eventTap = tap
        self.runLoopSource = src
    }

    private func handleCGEvent(
        proxy: CGEventTapProxy,
        type: CGEventType,
        event: CGEvent
    ) -> Unmanaged<CGEvent>? {
        // macOS disables an event tap that responds too slowly, or after the system
        // wakes from sleep. If we don't re-enable it, the Fn hotkey silently dies and
        // the app stops responding until relaunch. Re-arm it here.
        if type == .tapDisabledByTimeout || type == .tapDisabledByUserInput {
            if let tap = eventTap {
                CGEvent.tapEnable(tap: tap, enable: true)
            }
            return nil
        }

        // Only act on flags-changed events — that's what modifier keys fire.
        guard type == .flagsChanged else {
            return Unmanaged.passRetained(event)
        }

        let fnNowDown = event.flags.contains(.maskSecondaryFn)

        if fnNowDown && !fnIsDown {
            fnIsDown = true
            DispatchQueue.main.async { self.delegate?.hotkeyDidPress() }
            return nil  // suppress — prevents Globe/emoji-picker from opening

        } else if !fnNowDown && fnIsDown {
            fnIsDown = false
            DispatchQueue.main.async { self.delegate?.hotkeyDidRelease() }
            return nil  // suppress release too

        }

        return Unmanaged.passRetained(event)
    }

    // MARK: - Fallback: NSEvent global monitor (no Accessibility — no event suppression)

    private func startFallbackMonitor() {
        fallbackMonitor = NSEvent.addGlobalMonitorForEvents(matching: .flagsChanged) { [weak self] event in
            guard let self else { return }
            // NSEvent uses .function for the Fn modifier flag.
            let fnNowDown = event.modifierFlags.contains(.function)

            if fnNowDown && !self.fnIsDown {
                self.fnIsDown = true
                self.delegate?.hotkeyDidPress()
            } else if !fnNowDown && self.fnIsDown {
                self.fnIsDown = false
                self.delegate?.hotkeyDidRelease()
            }
        }
    }
}
