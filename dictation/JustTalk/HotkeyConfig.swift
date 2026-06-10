import Foundation
import CoreGraphics

/// The curated set of activation keys the user can choose from.
///
/// Every option is a single press (no chords): reliable to detect, works for both
/// hold-to-talk and tap-to-toggle, and unlikely to collide with normal typing. A
/// free-form shortcut recorder was deliberately not built — combos make hold-mode
/// semantics ambiguous and clash with app shortcuts.
enum HotkeyConfig: String, CaseIterable, Identifiable {
    case fn
    case rightCommand
    case rightOption
    case rightControl
    case f5
    case f6
    case f13

    var id: String { rawValue }

    /// Full label for pickers.
    var displayName: String {
        switch self {
        case .fn:           return "Fn / 🌐 Globe"
        case .rightCommand: return "Right ⌘ Command"
        case .rightOption:  return "Right ⌥ Option"
        case .rightControl: return "Right ⌃ Control"
        case .f5:           return "F5"
        case .f6:           return "F6"
        case .f13:          return "F13"
        }
    }

    /// Compact label for status strings ("hold Fn to talk").
    var shortName: String {
        switch self {
        case .fn:           return "Fn"
        case .rightCommand: return "Right ⌘"
        case .rightOption:  return "Right ⌥"
        case .rightControl: return "Right ⌃"
        case .f5:           return "F5"
        case .f6:           return "F6"
        case .f13:          return "F13"
        }
    }

    /// Only Fn can pop the OS emoji/Globe picker, so only Fn is affected by the
    /// `AppleFnUsageType` system setting (see HotkeyConflict).
    var isFn: Bool { self == .fn }

    /// Whether to swallow the key so it never reaches the foreground app. Fn must be
    /// suppressed (kills the Globe/emoji picker) and function keys too (avoid app/system
    /// reactions). Real modifiers (⌘/⌥/⌃) are **passed through** so they keep working as
    /// modifiers for normal shortcuts — we only observe their press/release.
    var suppresses: Bool {
        switch self {
        case .fn, .f5, .f6, .f13:                            return true
        case .rightCommand, .rightOption, .rightControl:     return false
        }
    }

    /// How `HotkeyManager` should match this key against `CGEvent`s.
    enum Match {
        /// Fn — detected via `CGEventFlags.maskSecondaryFn` on `.flagsChanged`.
        case fnModifier
        /// A right-hand modifier — `.flagsChanged`, disambiguated from its left twin
        /// by virtual keyCode; down/up read from `flag` presence.
        case modifier(keyCode: Int64, flag: CGEventFlags)
        /// A function key — `.keyDown` / `.keyUp` by virtual keyCode.
        case function(keyCode: Int64)
    }

    var match: Match {
        switch self {
        case .fn:           return .fnModifier
        case .rightCommand: return .modifier(keyCode: 54, flag: .maskCommand)
        case .rightOption:  return .modifier(keyCode: 61, flag: .maskAlternate)
        case .rightControl: return .modifier(keyCode: 62, flag: .maskControl)
        case .f5:           return .function(keyCode: 96)
        case .f6:           return .function(keyCode: 97)
        case .f13:          return .function(keyCode: 105)
        }
    }

    // MARK: - Persistence

    private static let defaultsKey = "hotkeyKey"

    static func load(from defaults: UserDefaults = .standard) -> HotkeyConfig {
        HotkeyConfig(rawValue: defaults.string(forKey: defaultsKey) ?? "") ?? .fn
    }

    func save(to defaults: UserDefaults = .standard) {
        defaults.set(rawValue, forKey: HotkeyConfig.defaultsKey)
    }
}
