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
    private static var store: UserDefaults? { UserDefaults(suiteName: appGroup) }

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
}
