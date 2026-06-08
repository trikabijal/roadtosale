import AppKit
import AVFoundation
import ApplicationServices

enum PermissionStatus {
    case granted, denied, notDetermined
}

/// Single source of truth for the two permissions Just Talk needs. Status reads are
/// **non-prompting** — the app can poll them freely without popping System Settings. The
/// system prompts fire only from the explicit `request*` methods, which are wired to
/// onboarding buttons. This is the fix for "Settings opens out of the blue": nothing here
/// is called automatically at launch.
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
        if let url = URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_Microphone") {
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
        if let url = URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_Accessibility") {
            NSWorkspace.shared.open(url)
        }
    }
}
