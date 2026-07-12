import Foundation

/// The cross-platform design spec — the SINGLE source of truth for the values that must look the same on
/// macOS and iOS: the brand accent, semantic state colours, neutral ink, the metric scale (radius +
/// spacing), and the waveform spec. Kept as raw values (RGB tuples + `Double`) with NO SwiftUI/AppKit
/// import, so both UI targets wrap them in their own `Color`/`CGFloat` (same pattern as `BrandPalette`).
///
/// Platform-specific choices deliberately live OUTSIDE this file: macOS dark chrome grounds
/// (`Theme.Palette`), iOS light/system grounds, the macOS floating-pill glass + gold-shimmer border, and
/// each platform's native window/keyboard treatment. See docs/design-system.md.
public enum DesignTokens {

    // MARK: Colour (raw RGB 0–1)

    public enum Color {
        /// Brand accent — GOLD, one value everywhere (from `BrandPalette`).
        public static let accent = BrandPalette.goldRGB
        public static let accentDeep = BrandPalette.goldDeepRGB

        // Semantic state — one value each (previously 3 greens / 2 reds scattered across the code).
        public static let success = (red: 0.16, green: 0.62, blue: 0.36)   // used with a check/seal icon
        public static let warning = (red: 1.00, green: 0.62, blue: 0.04)   // orange
        public static let recording = (red: 0.90, green: 0.27, blue: 0.23) // the recording red / mic dot

        // Neutral ink (light surfaces) — the iOS onboarding ink/muted, shared so both agree.
        public static let ink = (red: 0.09, green: 0.09, blue: 0.11)
        public static let muted = (red: 0.42, green: 0.42, blue: 0.46)
    }

    // MARK: Metrics

    /// Corner radii. One card radius across both platforms (was macOS 12 vs iOS 16/22).
    public enum Radius {
        public static let window: Double = 16
        public static let card: Double = 16
        public static let control: Double = 10
        public static let chip: Double = 8
    }

    /// Spacing scale (was tokenised on macOS, raw literals on iOS).
    public enum Space {
        public static let xs: Double = 4
        public static let sm: Double = 8
        public static let md: Double = 12
        public static let lg: Double = 16
        public static let xl: Double = 20
        public static let xxl: Double = 24
    }

    // MARK: Waveform spec

    /// One spec for the living waveform so the macOS pill meter and the iOS keyboard bars share a look
    /// and the SAME level→amplitude response (they were full-scale at RMS 0.2 vs 0.167 vs 0.12). Frame
    /// size stays per-surface; these shape the motion + response.
    public enum Wave {
        /// Bars in the discrete (iOS-style) waveform.
        public static let barCount = 27
        /// Gap between bars (pt).
        public static let barSpacing: Double = 5
        /// Mic RMS that maps to full amplitude. Shared so a given loudness looks equally "loud" on both.
        public static let fullScaleRMS: Double = 0.18
        /// Minimum amplitude fraction (idle shimmer / floor) so the wave never fully flattens while live.
        public static let floor: Double = 0.12
    }
}
