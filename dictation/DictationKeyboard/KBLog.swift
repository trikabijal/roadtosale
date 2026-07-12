import os
import DictationCoreBase

/// Minimal os_log wrapper for the keyboard extension. View logs with:
/// `log stream --predicate 'subsystem == "com.trika.dictation"'` (or Console.app).
enum KBLog {
    private static let oslog = Logger(subsystem: DictationHandoff.logSubsystem, category: "Keyboard")

    static func log(_ message: String)   { oslog.log("\(message, privacy: .public)") }
    static func error(_ message: String) { oslog.error("\(message, privacy: .public)") }
}
