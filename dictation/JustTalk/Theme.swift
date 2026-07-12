import SwiftUI
import DictationCore

// MARK: - Design tokens (JustTalk redesign)

/// macOS UI tokens. The CROSS-PLATFORM values (brand accent, semantic colours, radius/spacing) come from
/// the shared `DesignTokens` so macOS and iOS agree; only the macOS-specific dark chrome (grounds,
/// hairlines, text ramp, traffic-light dots, mono type) lives here. See docs/design-system.md.
private extension Color {
    /// Build a Color from a shared-token RGB tuple.
    init(_ rgb: (red: Double, green: Double, blue: Double)) {
        self.init(red: rgb.red, green: rgb.green, blue: rgb.blue)
    }
}

enum Theme {

    // MARK: Palette

    enum Palette {
        // Grounds & surfaces (dark-first).
        static let ground        = Color(hex: 0x14141A)   // desktop / deepest
        static let surface       = Color(hex: 0x1D1D1F)   // popover / window body
        static let surfaceRaised  = Color(hex: 0x26262C)  // rows / HUD fill / controls
        static let surfaceInset   = Color(hex: 0x0F0F13)  // search field / wells

        // Hairlines.
        static let stroke        = Color.white.opacity(0.08)
        static let strokeStrong   = Color.white.opacity(0.14)

        // Text.
        static let textPrimary   = Color(hex: 0xECECED)
        static let textSecondary  = Color(hex: 0x98989D)
        static let textTertiary   = Color(hex: 0x7C7C82)

        // Brand accent — the SHARED gold (was a macOS-only pink; the 3 unused alternates are removed).
        static let accent        = Color(DesignTokens.Color.accent)

        // Semantic state colors — from the shared spec, always paired with an icon.
        static let recording     = Color(DesignTokens.Color.recording)
        static let warning       = Color(DesignTokens.Color.warning)
        static let caution       = Color(hex: 0xFFD60A)   // yellow — macOS-only caution tint
        static let success       = Color(DesignTokens.Color.success)

        // Window traffic-light dots.
        static let tlClose       = Color(hex: 0xFF5F57)
        static let tlMin         = Color(hex: 0xFEBC2E)
        static let tlMax         = Color(hex: 0x28C840)
    }

    // MARK: Corner radii

    enum Radius {
        static let window  = CGFloat(DesignTokens.Radius.window)
        static let card    = CGFloat(DesignTokens.Radius.card)
        static let control = CGFloat(DesignTokens.Radius.control)
        static let chip    = CGFloat(DesignTokens.Radius.chip)
    }

    // MARK: Spacing scale

    enum Space {
        static let xs = CGFloat(DesignTokens.Space.xs)
        static let sm = CGFloat(DesignTokens.Space.sm)
        static let md = CGFloat(DesignTokens.Space.md)
        static let lg = CGFloat(DesignTokens.Space.lg)
        static let xl = CGFloat(DesignTokens.Space.xl)
    }

    // MARK: Type

    enum Font {
        static func ui(_ size: CGFloat, _ weight: SwiftUI.Font.Weight = .regular) -> SwiftUI.Font {
            .system(size: size, weight: weight)
        }
        /// Monospace for data/meta (timestamps, confidence, stats) — matches the design.
        static func mono(_ size: CGFloat, _ weight: SwiftUI.Font.Weight = .regular) -> SwiftUI.Font {
            .system(size: size, weight: weight, design: .monospaced)
        }
    }
}

// MARK: - Color(hex:) helper

extension Color {
    init(hex: UInt, alpha: Double = 1) {
        self.init(
            .sRGB,
            red:   Double((hex >> 16) & 0xFF) / 255,
            green: Double((hex >> 8) & 0xFF) / 255,
            blue:  Double(hex & 0xFF) / 255,
            opacity: alpha
        )
    }
}
