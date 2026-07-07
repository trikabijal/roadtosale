import XCTest
@testable import DictationCore

/// f7 — the launch requirements gate. Drives the PURE `SystemPreflight.decide(...)` with synthetic
/// machine facts (the real env reads can't be faked). Requirements are vendor-sourced: Apple Silicon
/// + 8 GB RAM + macOS 14; Apple recommended on Apple Silicon + macOS 26, else WhisperKit.
final class SystemCapabilitiesTests: XCTestCase {

    /// A capable machine (Apple Silicon, macOS 26, 16 GB, 200 GB free, Apple available). Override one
    /// field per test to isolate a single blocker.
    private func decide(
        appleSilicon: Bool = true,
        osMajor: Int = 26,
        freeDiskGB: Double = 200,
        ramGB: Double = 16,
        appleAvailable: Bool = true
    ) -> SystemCapabilities {
        SystemPreflight.decide(
            isAppleSilicon: appleSilicon, osMajor: osMajor, osVersion: "\(osMajor).0",
            freeDiskGB: freeDiskGB, ramGB: ramGB, appleAvailable: appleAvailable,
            cleanupIsFoundationModels: true)
    }

    func testCapableAppleSiliconMacOS26RecommendsApple() {
        // Apple Speech is the default on a capable Mac (fast, and the same provider the iOS keyboard
        // uses → same shape). WhisperKit is the multilingual backup.
        let c = decide()
        XCTAssertTrue(c.canRun)
        XCTAssertEqual(c.recommendedProvider, .appleSpeech)
    }

    func testAppleSiliconOlderOSRecommendsWhisperKit() {
        let c = decide(osMajor: 14, appleAvailable: false)   // Apple SpeechAnalyzer needs macOS 26
        XCTAssertTrue(c.canRun)
        XCTAssertEqual(c.recommendedProvider, .whisperKit)
    }

    func testIntelIsBlocked() {
        let c = decide(appleSilicon: false)
        XCTAssertFalse(c.canRun)
        XCTAssertTrue(c.blockers.contains(.notAppleSilicon))
        XCTAssertEqual(c.recommendedProvider, .whisperKit)
    }

    func testOldMacOSIsBlocked() {
        let c = decide(osMajor: 13)
        XCTAssertTrue(c.blockers.contains(.osBelow(minMajor: 14, current: "13.0")))
    }

    func testLowRAMIsBlocked() {
        let c = decide(ramGB: 4)
        XCTAssertTrue(c.blockers.contains(.lowRAM(neededGB: 8, actualGB: 4)))
    }

    /// The cheapest current Mac (MacBook Neo, 8 GB fixed) must pass — the margin lets exactly-8 through.
    func testEightGBPasses() {
        XCTAssertFalse(decide(ramGB: 8).blockers.contains { if case .lowRAM = $0 { return true }; return false })
    }

    func testSevenGBBlocked() {
        XCTAssertTrue(decide(ramGB: 7).blockers.contains { if case .lowRAM = $0 { return true }; return false })
    }

    func testLowDiskIsBlocked() {
        let c = decide(freeDiskGB: 1)
        XCTAssertTrue(c.blockers.contains(.lowDisk(neededGB: 2, freeGB: 1)))
    }

    func testTwoGBDiskPasses() {
        XCTAssertFalse(decide(freeDiskGB: 2).blockers.contains { if case .lowDisk = $0 { return true }; return false })
    }

    /// Multiple failures surface as multiple blockers (no masking onto one).
    func testIntelAndLowRAMListBoth() {
        let c = decide(appleSilicon: false, ramGB: 4)
        XCTAssertTrue(c.blockers.contains(.notAppleSilicon))
        XCTAssertTrue(c.blockers.contains(.lowRAM(neededGB: 8, actualGB: 4)))
    }

    /// Apple "available" but on an Intel Mac → still recommend WhisperKit (Apple needs Apple Silicon).
    func testAppleAvailableButIntelRecommendsWhisperKit() {
        XCTAssertEqual(decide(appleSilicon: false, appleAvailable: true).recommendedProvider, .whisperKit)
    }
}
