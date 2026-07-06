import XCTest
@testable import JustTalk

/// f8 / checklist C10 — the thesis of `feat/drop-input-monitoring`: the hotkey tap installs on
/// **Accessibility only**, and **ignores Input Monitoring**. Drives the pure `canInstallTap(axTrusted:
/// inputMonitoringGranted:)` seam so this is deterministic (the live `AXIsProcessTrusted()` can't be
/// faked in a test).
final class HotkeyPermissionTests: XCTestCase {

    func testInstallsWithAccessibilityAlone() {
        // Accessibility granted, Input Monitoring NOT → the active tap still installs.
        XCTAssertTrue(HotkeyManager.canInstallTap(axTrusted: true, inputMonitoringGranted: false))
    }

    func testDoesNotInstallWithoutAccessibility() {
        // Input Monitoring alone is NOT sufficient — it's the wrong permission for an active tap.
        XCTAssertFalse(HotkeyManager.canInstallTap(axTrusted: false, inputMonitoringGranted: true))
        XCTAssertFalse(HotkeyManager.canInstallTap(axTrusted: false, inputMonitoringGranted: false))
    }

    func testInputMonitoringIsIgnored() {
        // The gate is Accessibility-only: flipping Input Monitoring changes nothing.
        XCTAssertEqual(
            HotkeyManager.canInstallTap(axTrusted: true, inputMonitoringGranted: true),
            HotkeyManager.canInstallTap(axTrusted: true, inputMonitoringGranted: false))
        XCTAssertEqual(
            HotkeyManager.canInstallTap(axTrusted: false, inputMonitoringGranted: true),
            HotkeyManager.canInstallTap(axTrusted: false, inputMonitoringGranted: false))
    }
}
