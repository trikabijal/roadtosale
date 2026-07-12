import Foundation

/// The Just Talk brand accent as raw RGB (0–1), platform-agnostic (no SwiftUI/AppKit here) so EVERY UI
/// target — the container app and the keyboard extension, which are separate processes — builds the
/// exact same gold. Prevents the accent from drifting between surfaces (it did: keyboard `0.86,0.62,0.20`
/// vs app `0.82,0.62,0.22`). Each UI wraps these in its own `Color`.
public enum BrandPalette {
    public static let goldRGB: (red: Double, green: Double, blue: Double) = (0.86, 0.62, 0.20)
    public static let goldDeepRGB: (red: Double, green: Double, blue: Double) = (0.70, 0.48, 0.12)
}
