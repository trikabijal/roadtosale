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
    /// Absent => 0 (treated as "no OS claim") so we don't nag on a default machine.
    static func appleFnUsageType() -> Int {
        UserDefaults(suiteName: "com.apple.HIToolbox")?.integer(forKey: "AppleFnUsageType") ?? 0
    }

    /// Human label for the current OS Globe behavior.
    static func appleFnUsageLabel() -> String {
        switch appleFnUsageType() {
        case 1:  return "Change Input Source"
        case 2:  return "Show Emoji & Symbols"
        case 3:  return "Start Dictation"
        default: return "Do Nothing"
        }
    }

    /// True only when Fn is the chosen key AND the OS is set to react to it. Our event tap
    /// usually suppresses Fn first anyway, so this is advisory, not a hard blocker.
    static func osClaimsFn(for config: HotkeyConfig) -> Bool {
        config.isFn && appleFnUsageType() != 0
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
