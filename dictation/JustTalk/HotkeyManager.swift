import Carbon
import CoreGraphics
import AppKit

// MARK: - Synthetic-event tag (F7)

/// Stamped on synthetic events Just Talk posts itself (the ⌘V paste in `ClipboardPaster`)
/// via `CGEventSource.userData`. The hotkey tap ignores any event carrying this tag so the
/// app can never self-trigger from its own paste. Shared across the JustTalk target.
let justTalkSyntheticEventUserData: Int64 = 0x4A_54_4B_42  // "JTKB"

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
/// `keyDown`/`keyUp`. When the required permissions are present we install a `CGEventTap`
/// at `.cghidEventTap` (the earliest interception point) and return `nil` to **suppress**
/// the event — this stops the Globe emoji-picker (Fn) or a stray F-key/modifier from
/// reaching the foreground app. Otherwise the tap can't be installed; we fall back to
/// `NSEvent.addGlobalMonitorForEvents`, which observes but cannot consume.
///
/// **Threading (F1):** the tap is pumped on a dedicated background thread, never the main
/// run loop. An active `.cghidEventTap` sits inline in HID event delivery — if its callback
/// can't return promptly, *every keystroke system-wide stalls*. Pumping it on its own thread
/// means a busy main thread (AI cleanup, transcription) can never freeze the keyboard.
///
/// This class never prompts for permissions — `AppState`/`PermissionsService` own that.
final class HotkeyManager {
    weak var delegate: HotkeyManagerDelegate?

    /// When true, presses route to `hotkeyDidReceiveConfiguredKey()` only (no record
    /// start/stop) so the onboarding test can confirm the key without recording.
    var isTesting = false

    private(set) var config: HotkeyConfig

    private var eventTap: CFMachPort?
    private var runLoopSource: CFRunLoopSource?
    // The dedicated thread + run loop the tap is pumped on (F1). Never the main thread.
    private var tapThread: Thread?
    private var tapRunLoop: CFRunLoop?
    private let tapRunLoopLock = NSLock()
    private var fallbackMonitor: Any?
    // Guards `keyIsDown` — the tap callback runs on the tap thread while start/stop run on
    // main, so the edge-detection flag is touched from two threads.
    private let stateLock = NSLock()
    private var keyIsDown = false
    // Watchdog: macOS silently disables the tap after sleep/wake or a stall, and the
    // in-callback re-arm can't fire if no events flow. This timer revives it.
    private var watchdog: Timer?
    private var wakeObserver: NSObjectProtocol?
    // Throttles full rebuilds so a tap that can't enable (e.g. permission pending) never
    // loops on recreate — recreating re-prompts for permission every time (F3).
    private var lastRebuild = Date.distantPast

    init(delegate: HotkeyManagerDelegate, config: HotkeyConfig) {
        self.delegate = delegate
        self.config = config
    }

    /// A keyboard event tap requires **Input Monitoring**; posting the synthetic ⌘V paste
    /// requires **Accessibility**. Require both before creating the tap — creating one that
    /// can never enable is what drove the watchdog rebuild/permission-prompt loop (F2/F3).
    static func canInstallTap() -> Bool {
        AXIsProcessTrusted() && CGPreflightListenEventAccess()
    }

    /// Install the listener. Degrades to a non-suppressing monitor if the tap can't be made.
    /// Returns true if the suppressing event tap was installed.
    @discardableResult
    func start() -> Bool {
        stop()
        setKeyDown(false)
        observeWake()
        let installed: Bool
        if Self.canInstallTap(), startEventTap() {
            installed = true
        } else {
            startFallbackMonitor()
            installed = false
        }
        startWatchdog()
        return installed
    }

    func stop() {
        watchdog?.invalidate()
        watchdog = nil
        if let tap = eventTap {
            CGEvent.tapEnable(tap: tap, enable: false)
        }
        // Stop the dedicated run loop; its thread then returns from CFRunLoopRun() and exits,
        // so no callback can race teardown.
        if let rl = currentTapRunLoop() {
            CFRunLoopStop(rl)
        }
        setTapRunLoop(nil)
        eventTap = nil
        runLoopSource = nil
        tapThread = nil
        if let monitor = fallbackMonitor {
            NSEvent.removeMonitor(monitor)
            fallbackMonitor = nil
        }
        setKeyDown(false)
    }

    // MARK: - Thread-safe state accessors

    private func setKeyDown(_ value: Bool) {
        stateLock.lock(); keyIsDown = value; stateLock.unlock()
    }

    private func currentTapRunLoop() -> CFRunLoop? {
        tapRunLoopLock.lock(); defer { tapRunLoopLock.unlock() }
        return tapRunLoop
    }

    private func setTapRunLoop(_ rl: CFRunLoop?) {
        tapRunLoopLock.lock(); tapRunLoop = rl; tapRunLoopLock.unlock()
    }

    // MARK: - Watchdog — keep the tap alive across sleep/timeout

    private func startWatchdog() {
        watchdog?.invalidate()
        let timer = Timer(timeInterval: 2.0, repeats: true) { [weak self] _ in
            self?.ensureTapAlive()
        }
        RunLoop.main.add(timer, forMode: .common)
        watchdog = timer
    }

    /// If macOS disabled our tap, re-enable it in place (cheap, never re-prompts). Only if
    /// that fails do we rebuild — throttled to once per 10s and gated on permissions, so a
    /// tap that genuinely can't install never loops on recreate-and-reprompt (F3).
    private func ensureTapAlive() {
        guard let tap = eventTap else { return }   // fallback-monitor path needs no watchdog
        if CGEvent.tapIsEnabled(tap: tap) { return }
        CGEvent.tapEnable(tap: tap, enable: true)
        if CGEvent.tapIsEnabled(tap: tap) { return }
        guard Self.canInstallTap(), Date().timeIntervalSince(lastRebuild) > 10 else { return }
        lastRebuild = Date()
        start()
    }

    /// Rebuild the tap fresh whenever the machine wakes — the most reliable recovery point.
    private func observeWake() {
        guard wakeObserver == nil else { return }
        wakeObserver = NSWorkspace.shared.notificationCenter.addObserver(
            forName: NSWorkspace.didWakeNotification, object: nil, queue: .main
        ) { [weak self] _ in
            self?.start()
        }
    }

    /// Switch to a new activation key and reinstall the listener live.
    func setConfig(_ newConfig: HotkeyConfig) {
        guard newConfig != config else { return }
        config = newConfig
        start()
    }

    // MARK: - CGEventTap (requires Accessibility + Input Monitoring — suppresses the event)

    private func startEventTap() -> Bool {
        let mask: CGEventMask =
            (1 << CGEventType.flagsChanged.rawValue) |
            (1 << CGEventType.keyDown.rawValue) |
            (1 << CGEventType.keyUp.rawValue)

        // CRITICAL: only suppressing hotkeys (Fn / function keys) need an ACTIVE tap, which
        // sits inline in HID delivery — every keystroke system-wide waits for our callback,
        // so under heavy CPU load (transcription/cleanup) a delayed callback freezes the
        // whole keyboard. A non-suppressing hotkey (right ⌘/⌥/⌃) only OBSERVES the key, so
        // we use a LISTEN-ONLY tap: it sees the key just as well but is NOT in the delivery
        // path and therefore can never freeze input, regardless of load.
        let tapOptions: CGEventTapOptions = config.suppresses ? .defaultTap : .listenOnly

        guard let tap = CGEvent.tapCreate(
            tap: .cghidEventTap,
            place: .headInsertEventTap,
            options: tapOptions,
            eventsOfInterest: mask,
            callback: { _, type, event, refcon -> Unmanaged<CGEvent>? in
                guard let refcon else { return Unmanaged.passUnretained(event) }
                let manager = Unmanaged<HotkeyManager>.fromOpaque(refcon).takeUnretainedValue()
                return manager.handleCGEvent(type: type, event: event)
            },
            userInfo: Unmanaged.passUnretained(self).toOpaque()
        ) else { return false }

        let src = CFMachPortCreateRunLoopSource(kCFAllocatorDefault, tap, 0)
        self.eventTap = tap
        self.runLoopSource = src
        lastRebuild = Date()

        // Pump the tap on a dedicated thread (F1) so a busy main thread can never stall input.
        let ready = DispatchSemaphore(value: 0)
        let thread = Thread { [weak self] in
            let rl = CFRunLoopGetCurrent()
            self?.setTapRunLoop(rl)
            CFRunLoopAddSource(rl, src, .commonModes)
            CGEvent.tapEnable(tap: tap, enable: true)
            ready.signal()
            CFRunLoopRun()                                  // blocks until stop() → CFRunLoopStop
            CFRunLoopRemoveSource(rl, src, .commonModes)
        }
        thread.name = "com.justtalk.hotkey-tap"
        thread.qualityOfService = .userInteractive
        self.tapThread = thread
        thread.start()
        _ = ready.wait(timeout: .now() + 1.0)

        // Verify the tap actually enabled. If not (permission revoked mid-flight, etc.), report
        // failure so the caller falls back to the non-suppressing monitor rather than looping.
        if !CGEvent.tapIsEnabled(tap: tap) {
            if let rl = currentTapRunLoop() { CFRunLoopStop(rl) }
            setTapRunLoop(nil)
            self.eventTap = nil
            self.runLoopSource = nil
            self.tapThread = nil
            return false
        }
        return true
    }

    private func handleCGEvent(type: CGEventType, event: CGEvent) -> Unmanaged<CGEvent>? {
        // macOS disables a tap that responds too slowly or after wake-from-sleep; re-arm it
        // and clear the edge state so a press that straddled the disable can't get stuck (F4).
        if type == .tapDisabledByTimeout || type == .tapDisabledByUserInput {
            if let tap = eventTap { CGEvent.tapEnable(tap: tap, enable: true) }
            setKeyDown(false)
            return nil
        }

        // Never react to events we synthesized ourselves (the ⌘V paste) — F7.
        if event.getIntegerValueField(.eventSourceUserData) == justTalkSyntheticEventUserData {
            return Unmanaged.passUnretained(event)
        }

        let pass = Unmanaged.passUnretained(event)

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
        stateLock.lock()
        let wasDown = keyIsDown
        if down && !wasDown {
            keyIsDown = true
            stateLock.unlock()
            DispatchQueue.main.async { [weak self] in self?.firePress() }
            return consume ? nil : pass
        } else if !down && wasDown {
            keyIsDown = false
            stateLock.unlock()
            DispatchQueue.main.async { [weak self] in self?.fireRelease() }
            return consume ? nil : pass
        }
        stateLock.unlock()
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

    // MARK: - Fallback: NSEvent global monitor (no tap permission — cannot suppress)

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
                self.stateLock.lock(); let down = !self.keyIsDown; self.stateLock.unlock()
                self.fallbackTransition(down: down)
            case .function(let keyCode):
                guard Int64(event.keyCode) == keyCode else { return }
                if event.type == .keyDown { self.fallbackTransition(down: true) }
                else if event.type == .keyUp { self.fallbackTransition(down: false) }
            }
        }
    }

    private func fallbackTransition(down: Bool) {
        stateLock.lock()
        let wasDown = keyIsDown
        if down && !wasDown {
            keyIsDown = true
            stateLock.unlock()
            firePress()
        } else if !down && wasDown {
            keyIsDown = false
            stateLock.unlock()
            fireRelease()
        } else {
            stateLock.unlock()
        }
    }
}
