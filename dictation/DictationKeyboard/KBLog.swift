import Foundation
import os

/// Diagnostic file logger for the keyboard extension. Writes to the shared App Group container so the
/// Mac can pull it off the device with `devicectl device copy`. Also mirrors to os_log.
enum KBLog {
    private static let oslog = Logger(subsystem: "com.trika.dictation", category: "KBLog")
    private static let appGroup = "group.com.trika.dictation"

    static var fileURL: URL? {
        FileManager.default
            .containerURL(forSecurityApplicationGroupIdentifier: appGroup)?
            .appendingPathComponent("keyboard-diag.log")
    }

    static func log(_ message: String) {
        oslog.log("\(message, privacy: .public)")
        guard let url = fileURL else { return }
        let line = "\(Date().timeIntervalSince1970): \(message)\n"
        if let handle = try? FileHandle(forWritingTo: url) {
            defer { try? handle.close() }
            _ = try? handle.seekToEnd()
            try? handle.write(contentsOf: Data(line.utf8))
        } else {
            try? Data(line.utf8).write(to: url)
        }
    }

    /// App-Group-container availability probe — written once so we know the group is actually wired.
    static func probe() {
        if let url = fileURL {
            log("APP GROUP OK: \(url.path)")
        } else {
            oslog.error("APP GROUP UNAVAILABLE — containerURL is nil")
        }
    }
}
