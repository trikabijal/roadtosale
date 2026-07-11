import Foundation

/// The bridge for the iOS "Flow Session" dictation handoff. A keyboard extension cannot record audio
/// (iOS blocks mic capture in extensions), so the keyboard launches the container app, the app records
/// + transcribes, writes the result here, and the keyboard reads it back and inserts it. Backed by the
/// shared App Group so both processes see the same store.
public enum DictationHandoff {
    public static let appGroup = "group.com.trika.dictation"
    public static let urlScheme = "justtalk"

    /// URL the keyboard opens to start a Flow Session in the container app.
    public static var recordURL: URL { URL(string: "\(urlScheme)://record")! }

    private static let key = "pendingDictation"
    private static let sessionKey = "flowSessionHeartbeat"
    private static let levelKey = "flowSessionLevel"
    private static var store: UserDefaults? { UserDefaults(suiteName: appGroup) }

    // MARK: - Flow Session liveness (the "no app-switch" mechanism)
    //
    // Once the container app is launched it holds a background audio session alive for a bounded
    // window (UIBackgroundModes: audio). While alive, a keyboard mic tap only posts `start`/`stop`
    // Darwin signals — NO openURL — so iOS never foregrounds the app and the user stays in the app
    // they're typing in (Wispr's "Flow Session"). The app heartbeats here; the keyboard reads it to
    // choose the seamless (signal) path vs the cold (launch) path.

    /// Container app: mark the session alive (call on start + on a periodic heartbeat).
    public static func markSessionAlive() {
        store?.set(Date().timeIntervalSince1970, forKey: sessionKey)
    }

    /// Container app: session ended (idle timeout / torn down) — next keyboard tap must relaunch.
    public static func markSessionEnded() {
        store?.removeObject(forKey: sessionKey)
    }

    /// Keyboard: is a background session alive (heartbeat fresh)? If so, signal it instead of launching.
    /// The window is short so a suspended/killed app can't look "alive" — a stale beat forces a relaunch.
    public static func isSessionAlive(maxAgeSeconds: TimeInterval = 8) -> Bool {
        guard let ts = store?.object(forKey: sessionKey) as? TimeInterval else { return false }
        return Date().timeIntervalSince1970 - ts <= maxAgeSeconds
    }

    /// Container app → keyboard: publish the live mic level (0…1) so the keyboard can draw a waveform
    /// while the app records invisibly in the background.
    public static func writeLevel(_ level: Float) {
        store?.set(level, forKey: levelKey)
    }

    /// Keyboard: read the latest mic level published by the recording app.
    public static func readLevel() -> Float {
        (store?.object(forKey: levelKey) as? Float) ?? 0
    }

    /// Container app: store the finished transcript for the keyboard to pick up.
    public static func write(_ text: String) {
        store?.set(["text": text, "ts": Date().timeIntervalSince1970], forKey: key)
    }

    /// Keyboard: read + clear the pending transcript (nil if none). Ignores stale entries older than
    /// the window, so a long-abandoned session can't inject text into an unrelated field later.
    public static func consume(maxAgeSeconds: TimeInterval = 120) -> String? {
        guard let dict = store?.dictionary(forKey: key),
              let text = dict["text"] as? String, !text.isEmpty,
              let ts = dict["ts"] as? TimeInterval,
              Date().timeIntervalSince1970 - ts <= maxAgeSeconds
        else {
            store?.removeObject(forKey: key)
            return nil
        }
        store?.removeObject(forKey: key)
        return text
    }

    // MARK: - Cross-process signals (Darwin notifications)

    /// App → keyboard: the transcript is ready in the App Group, come read it.
    public static let doneNotification = "com.trika.dictation.handoff.done"
    /// Keyboard → app (session ALIVE): begin a new dictation without relaunching the app.
    public static let startNotification = "com.trika.dictation.handoff.start"
    /// Keyboard → app: stop recording now (transcribe + hand back).
    public static let stopNotification = "com.trika.dictation.handoff.stop"

    /// Post a Darwin notification (delivered cross-process, keyboard ⇄ container app).
    public static func post(_ name: String) {
        CFNotificationCenterPostNotification(
            CFNotificationCenterGetDarwinNotifyCenter(),
            CFNotificationName(name as CFString), nil, nil, true)
    }

    /// Observe a Darwin notification. `observer` must be a stable pointer (e.g. Unmanaged.passUnretained).
    public static func observe(_ name: String, observer: UnsafeRawPointer,
                               callback: @escaping CFNotificationCallback) {
        CFNotificationCenterAddObserver(
            CFNotificationCenterGetDarwinNotifyCenter(), observer, callback,
            name as CFString, nil, .deliverImmediately)
    }

    /// Stop observing (call from the observer's deinit). Leaving a dangling `passUnretained` pointer
    /// registered means a later post can call into freed memory and crash the next session.
    public static func removeObserver(_ observer: UnsafeRawPointer) {
        CFNotificationCenterRemoveEveryObserver(
            CFNotificationCenterGetDarwinNotifyCenter(), observer)
    }
}
