import Carbon
import CoreGraphics
import AppKit

// MARK: - Delegate

protocol HotkeyManagerDelegate: AnyObject {
    func hotkeyDidPress()
    func hotkeyDidRelease()
}

// MARK: - HotkeyManager

final class HotkeyManager {
    weak var delegate: HotkeyManagerDelegate?

    /// Virtual key code. Default kVK_F5 = 0x60.
    var keyCode: CGKeyCode {
        get {
            let stored = UserDefaults.standard.integer(forKey: "hotkeyCode")
            return CGKeyCode(stored.nonZero ?? Int(kVK_F5))
        }
        set {
            UserDefaults.standard.set(Int(newValue), forKey: "hotkeyCode")
        }
    }

    private var eventTap: CFMachPort?
    private var runLoopSource: CFRunLoopSource?
    private var isKeyDown = false

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
            // Fall back to NSEvent global monitor — recording still works,
            // but the F5 key event will reach the frontmost app as well.
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
    }

    // MARK: - CGEventTap (requires Accessibility — suppresses the original F5 event)

    private func startEventTap() {
        let mask: CGEventMask =
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
        let code = CGKeyCode(event.getIntegerValueField(.keyboardEventKeycode))
        guard code == keyCode else { return Unmanaged.passRetained(event) }

        switch type {
        case .keyDown:
            // Ignore key-repeat events (isKeyDown already true)
            guard !isKeyDown else { return nil }
            isKeyDown = true
            DispatchQueue.main.async { self.delegate?.hotkeyDidPress() }
            return nil  // suppress F5 from reaching any other app

        case .keyUp:
            guard isKeyDown else { return nil }
            isKeyDown = false
            DispatchQueue.main.async { self.delegate?.hotkeyDidRelease() }
            return nil

        default:
            return nil
        }
    }

    // MARK: - Fallback: NSEvent global monitor (no Accessibility — no event suppression)

    private func startFallbackMonitor() {
        NSEvent.addGlobalMonitorForEvents(matching: [.keyDown, .keyUp]) { [weak self] event in
            guard let self else { return }
            guard CGKeyCode(event.keyCode) == self.keyCode else { return }

            if event.type == .keyDown && !self.isKeyDown {
                self.isKeyDown = true
                self.delegate?.hotkeyDidPress()
            } else if event.type == .keyUp && self.isKeyDown {
                self.isKeyDown = false
                self.delegate?.hotkeyDidRelease()
            }
        }
    }
}

// MARK: - Helpers

private extension Int {
    /// Returns nil if self is 0, otherwise self. Used to treat a missing UserDefaults key (0) as absent.
    var nonZero: Int? { self == 0 ? nil : self }
}
