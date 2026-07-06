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
        // Modern (Ventura+ / System Settings) privacy anchor. The legacy
        // `com.apple.preference.security?Privacy_Microphone` scheme lands on a random pane here.
        if let url = URL(string: "x-apple.systempreferences:com.apple.settings.PrivacySecurity.extension?Privacy_Microphone") {
            NSWorkspace.shared.open(url)
        }
    }

    // MARK: - Accessibility

    /// Non-prompting check — safe to poll.
    var accessibilityGranted: Bool { AXIsProcessTrusted() }

    /// Show the system Accessibility prompt and open the pane. Call only on a button tap.
    func promptAccessibility() {
        let options: NSDictionary = [kAXTrustedCheckOptionPrompt.takeUnretainedValue() as NSString: true]
        _ = AXIsProcessTrustedWithOptions(options)
        openAccessibilitySettings()
    }

    func openAccessibilitySettings() {
        // Modern (Ventura+ / System Settings) privacy anchor — see openMicSettings().
        if let url = URL(string: "x-apple.systempreferences:com.apple.settings.PrivacySecurity.extension?Privacy_Accessibility") {
            NSWorkspace.shared.open(url)
        }
    }

    /// Open Keyboard settings, where macOS Dictation's shortcut lives. macOS exposes no deep anchor to
    /// the Dictation sub-section, so we land on Keyboard settings and tell the user to scroll to it.
    func openKeyboardSettings() {
        if let url = URL(string: "x-apple.systempreferences:com.apple.Keyboard-Settings.extension") {
            NSWorkspace.shared.open(url)
        }
    }
}
