import SwiftUI

// MARK: - Design tokens (JustTalk redesign)

/// Single source of truth for the redesigned UI — palette, radii, spacing, materials, type.
/// Derived from the approved design prototype. Every view reads from here; no ad-hoc colors.
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

        // Brand accent (the design ships a pink default + 3 alternates).
        static let accent        = Color(hex: 0xFF2E7E)   // pink (default)
        static let accentPurple   = Color(hex: 0x7C5CFF)
        static let accentGold     = Color(hex: 0xFFB020)
        static let accentCyan     = Color(hex: 0x25C9E6)

        // Semantic state colors — kept distinct from the brand accent, always paired with an icon.
        static let recording     = Color(hex: 0xFF453A)   // red
        static let warning       = Color(hex: 0xFF9F0A)   // orange
        static let caution       = Color(hex: 0xFFD60A)   // yellow
        static let success       = Color(hex: 0x30D158)   // green

        // Window traffic-light dots.
        static let tlClose       = Color(hex: 0xFF5F57)
        static let tlMin         = Color(hex: 0xFEBC2E)
        static let tlMax         = Color(hex: 0x28C840)
    }

    // MARK: Corner radii

    enum Radius {
        static let window: CGFloat  = 16
        static let card: CGFloat    = 12
        static let control: CGFloat = 8
        static let chip: CGFloat    = 6
    }

    // MARK: Spacing scale

    enum Space {
        static let xs: CGFloat = 4
        static let sm: CGFloat = 8
        static let md: CGFloat = 12
        static let lg: CGFloat = 16
        static let xl: CGFloat = 20
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
