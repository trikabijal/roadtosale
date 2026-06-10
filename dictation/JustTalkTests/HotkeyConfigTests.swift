import XCTest
@testable import JustTalk

final class HotkeyConfigTests: XCTestCase {

    func testPersistenceRoundTrip() {
        let d = UserDefaults(suiteName: "JustTalkTests-\(UUID().uuidString)")!
        HotkeyConfig.rightOption.save(to: d)
        XCTAssertEqual(HotkeyConfig.load(from: d), .rightOption)
    }

    func testDefaultsToFnWhenUnset() {
        let d = UserDefaults(suiteName: "JustTalkTests-empty-\(UUID().uuidString)")!
        XCTAssertEqual(HotkeyConfig.load(from: d), .fn)
    }

    func testIsFnOnlyForFn() {
        XCTAssertTrue(HotkeyConfig.fn.isFn)
        for key in HotkeyConfig.allCases where key != .fn {
            XCTAssertFalse(key.isFn, "\(key) should not report isFn")
        }
    }

    func testFnMatchesViaFnModifier() {
        guard case .fnModifier = HotkeyConfig.fn.match else {
            return XCTFail("fn should match via .fnModifier")
        }
    }

    func testRightModifierKeycodesAreDistinct() {
        func keyCode(_ c: HotkeyConfig) -> Int64? {
            if case let .modifier(keyCode, _) = c.match { return keyCode }
            return nil
        }
        // Right-hand virtual keycodes — distinct from their left twins so we don't fire on left.
        XCTAssertEqual(keyCode(.rightCommand), 54)
        XCTAssertEqual(keyCode(.rightOption), 61)
        XCTAssertEqual(keyCode(.rightControl), 62)
    }

    func testFunctionKeycodes() {
        func keyCode(_ c: HotkeyConfig) -> Int64? {
            if case let .function(keyCode) = c.match { return keyCode }
            return nil
        }
        XCTAssertEqual(keyCode(.f5), 96)
        XCTAssertEqual(keyCode(.f6), 97)
        XCTAssertEqual(keyCode(.f13), 105)
    }

    func testDisplayNamesAreUnique() {
        let names = Set(HotkeyConfig.allCases.map(\.displayName))
        XCTAssertEqual(names.count, HotkeyConfig.allCases.count)
    }
}

final class HotkeyConflictTests: XCTestCase {

    func testAppleFnUsageLabelCoversAllValues() {
        // The label switch must never return empty for the current system value.
        XCTAssertFalse(HotkeyConflict.appleFnUsageLabel().isEmpty)
    }

    func testOsClaimsFnIsFalseForNonFnKeys() {
        // For a non-Fn key, the OS Globe setting is irrelevant — never a conflict.
        XCTAssertFalse(HotkeyConflict.osClaimsFn(for: .rightCommand))
        XCTAssertFalse(HotkeyConflict.osClaimsFn(for: .f5))
        XCTAssertFalse(HotkeyConflict.osClaimsFn(for: .f13))
    }
}
