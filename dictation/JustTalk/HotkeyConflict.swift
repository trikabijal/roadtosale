import AppKit

/// Best-effort detection of things that compete with our activation key.
///
/// Hard constraint: macOS exposes **no public API to enumerate other apps' event taps**,
/// so we can never positively name "app X owns Fn." We surface what we *can* know (the OS
/// Globe setting + known competitor apps running), and the onboarding "press your key to
/// test" step is the definitive check — it works regardless of what's intercepting.
enum HotkeyConflict {

    // MARK: - OS Globe-key setting (Fn only)

    /// `AppleFnUsageType` in `com.apple.HIToolbox`:
    /// 0 = Do Nothing · 1 = Change Input Source · 2 = Show Emoji & Symbols · 3 = Start Dictation.
    /// Returns nil when the key is ABSENT — which is the common case and does NOT mean "Do
    /// Nothing": on hardware with a Globe/Fn key the OS default is "Show Emoji & Symbols", so an
    /// unset value still pops the emoji picker. We read the raw object (not `.integer`, which
    /// can't tell "set to 0" from "absent") so the caller can treat absent as "reacts".
    static func appleFnUsageType() -> Int? {
        UserDefaults(suiteName: "com.apple.HIToolbox")?.object(forKey: "AppleFnUsageType") as? Int
    }

    /// Human label for the current OS Globe behavior.
    static func appleFnUsageLabel() -> String {
        switch appleFnUsageType() {
        case 0:   return "Do Nothing"
        case 1:   return "Change Input Source"
        case 2:   return "Show Emoji & Symbols"
        case 3:   return "Start Dictation"
        default:  return "Show Emoji & Symbols (system default)"   // nil / unknown → hardware default
        }
    }

    /// True when Fn is the chosen key AND the OS reacts to it (opens emoji / dictation / input
    /// switch). Absent counts as "reacts" — the Globe-key hardware default is Show Emoji, so an
    /// unset value is exactly the case that leaks to the emoji picker. Only an explicit 0 is safe.
    static func osClaimsFn(for config: HotkeyConfig) -> Bool {
        config.isFn && appleFnUsageType() != 0
    }

    // MARK: - macOS Dictation (the "second yellow microphone")

    /// macOS's own Dictation is enabled. Its default shortcut on Globe/Fn Macs is "Press 🌐", which
    /// fires alongside our Fn tap and pops the system's yellow microphone — the extra icon the user
    /// sees. This is a SEPARATE mapping from `AppleFnUsageType` (which governs emoji/input-source), so
    /// it collides even when Fn usage is "Show Emoji". We can't disable it programmatically; we warn.
    static func macOSDictationEnabled() -> Bool {
        if let d = UserDefaults(suiteName: "com.apple.assistant.support") {
            if let b = d.object(forKey: "Dictation Enabled") as? Bool { return b }
            if let n = d.object(forKey: "Dictation Enabled") as? Int { return n != 0 }
        }
        // Fallback: the Fn dictation auto-enable flag in HIToolbox.
        if let n = UserDefaults(suiteName: "com.apple.HIToolbox")?.object(forKey: "AppleDictationAutoEnable") as? Int {
            return n != 0
        }
        return false
    }

    /// True when Fn is our key AND macOS Dictation is on — they collide on the Globe key.
    static func macOSDictationClaimsFn(for config: HotkeyConfig) -> Bool {
        config.isFn && macOSDictationEnabled()
    }

    // MARK: - Known competitor apps

    /// Apps known to grab Fn / act as dictation drivers. Bundle ids drift across releases,
    /// so a name heuristic backs them up.
    static let knownGrabberBundleIDs: Set<String> = [
        "com.electron.wispr-flow",
        "computer.flow.app",
        "co.wispr.flow",
    ]

    static func runningCompetitors() -> [NSRunningApplication] {
        NSWorkspace.shared.runningApplications.filter { app in
            if let id = app.bundleIdentifier, knownGrabberBundleIDs.contains(id) { return true }
            if let name = app.localizedName?.lowercased(),
               name.contains("wispr") || name.contains("superwhisper") { return true }
            return false
        }
    }
}
