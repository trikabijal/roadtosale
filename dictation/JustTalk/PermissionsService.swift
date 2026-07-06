import AppKit
import AVFoundation
import ApplicationServices
import CoreGraphics

enum PermissionStatus {
    case granted, denied, notDetermined
}

/// Single source of truth for the two permissions Just Talk needs — **Microphone** and
/// **Accessibility** (same footprint as Wispr Flow; no Input Monitoring — the hotkey uses an active
/// CGEventTap, which needs only Accessibility). Status reads are **non-prompting** — the app can
/// poll them freely without popping System Settings. The system prompts fire only from the explicit
/// `request*` methods, wired to onboarding buttons. This is the fix for "Settings opens out of the
/// blue": nothing here is called automatically at launch.
@MainActor
final class PermissionsService {

    // MARK: - Microphone

    var micStatus: PermissionStatus {
        switch AVCaptureDevice.authorizationStatus(for: .audio) {
        case .authorized:           return .granted
        case .denied, .restricted:  return .denied
        case .notDetermined:        return .notDetermined
        @unknown default:           return .notDetermined
        }
    }

    /// Trigger the system mic prompt (only meaningful when not yet determined). If the
    /// user previously denied, this no-ops — route them to System Settings instead.
    @discardableResult
    func requestMic() async -> PermissionStatus {
        if micStatus == .notDetermined {
            _ = await AVCaptureDevice.requestAccess(for: .audio)
        } else if micStatus == .denied {
            openMicSettings()
        }
        return micStatus
    }

    func openMicSettings() {
        openPrivacyPane(.microphone)
    }

    // MARK: - Accessibility

    /// Non-prompting check — safe to poll.
    var accessibilityGranted: Bool { AXIsProcessTrusted() }

    /// Ask for Accessibility. The **version-proof** route is `AXIsProcessTrustedWithOptions` with the
    /// prompt option: it shows Apple's own dialog whose "Open System Settings" button navigates to the
    /// exact Accessibility pane on every macOS — no fragile URL. (The dialog only appears the FIRST
    /// time; for repeats the wizard offers `openAccessibilitySettings()` as a manual fallback.)
    func promptAccessibility() {
        let options: NSDictionary = [kAXTrustedCheckOptionPrompt.takeUnretainedValue() as NSString: true]
        _ = AXIsProcessTrustedWithOptions(options)
    }

    func openAccessibilitySettings() {
        openPrivacyPane(.accessibility)
    }

    /// Open Keyboard settings, where macOS Dictation's shortcut lives. macOS exposes no deep anchor to
    /// the Dictation sub-section, so we land on Keyboard settings and tell the user to scroll to it.
    func openKeyboardSettings() {
        let url = osMajor >= 13
            ? "x-apple.systempreferences:com.apple.Keyboard-Settings.extension"
            : "x-apple.systempreferences:com.apple.preference.keyboard"
        if let u = URL(string: url) { NSWorkspace.shared.open(u) }
    }

    // MARK: - OS-version-based Privacy deep-links

    /// Privacy panes changed URL schemes across macOS: **≤12** used System Preferences
    /// (`com.apple.preference.security?Privacy_X`); **13+** uses the System Settings PrivacySecurity
    /// extension. On some releases (incl. macOS 26) the `?Privacy_X` fragment isn't honored and the
    /// link lands on the Privacy & Security root — still the right neighbourhood, and the wizard tells
    /// the user which row to click. So we pick the scheme by OS major version, best-effort on the pane.
    private enum PrivacyPane: String {
        case microphone = "Privacy_Microphone"
        case accessibility = "Privacy_Accessibility"
    }

    private var osMajor: Int { ProcessInfo.processInfo.operatingSystemVersion.majorVersion }

    private func openPrivacyPane(_ pane: PrivacyPane) {
        let anchor = osMajor >= 13
            ? "x-apple.systempreferences:com.apple.settings.PrivacySecurity.extension?\(pane.rawValue)"
            : "x-apple.systempreferences:com.apple.preference.security?\(pane.rawValue)"
        if let url = URL(string: anchor) { NSWorkspace.shared.open(url) }
    }
}
