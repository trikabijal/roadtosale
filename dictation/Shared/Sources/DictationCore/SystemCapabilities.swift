import Foundation

/// A pre-flight check of whether THIS Mac can run Just Talk, and which STT provider to default to.
/// Run at launch so a machine that can't run the app is told clearly ("update macOS", "needs Apple
/// Silicon", "free up disk") up front — instead of failing silently mid-onboarding or on the first
/// model download. Goal: never lose a user to an onboarding that just breaks.
public struct SystemCapabilities: Sendable, Equatable {
    /// A hard reason the app can't run on this machine. Empty = good to go.
    /// NOTE: Apple Silicon is deliberately NOT a hard requirement — WhisperKit's own package targets
    /// macOS 13/14 with no arch gate and runs on Intel (CPU fallback, slower), so we don't block Intel
    /// Macs. Apple Silicon only affects which provider we *recommend*, not whether the app can run.
    public enum Blocker: Sendable, Equatable {
        case osBelow(minMajor: Int, current: String)
        case lowDisk(neededGB: Double, freeGB: Double)
    }

    public let blockers: [Blocker]
    /// The STT provider to use when nothing is saved yet — Apple (fast, English) on a machine that
    /// supports it, else WhisperKit (multilingual) on 14+ Apple Silicon.
    public let recommendedProvider: STTProvider
    /// Whether Apple's on-device LLM cleanup (Foundation Models) is available (else rule-based).
    public let cleanupIsFoundationModels: Bool
    public let freeDiskGB: Double
    public let osVersion: String

    /// True when the machine meets the hard requirements and onboarding can proceed.
    public var canRun: Bool { blockers.isEmpty }
}

public enum SystemPreflight {
    /// Minimum free disk for the on-device model (WhisperKit Large Turbo ≈ 1 GB) + working space.
    public static let minDiskGB: Double = 2.0
    /// Minimum macOS major (the deployment target — below this the app won't even launch, but we
    /// still surface a clean message rather than relying on Gatekeeper's generic one).
    public static let minOSMajor = 14

    public static func check() -> SystemCapabilities {
        var blockers: [SystemCapabilities.Blocker] = []

        // The only hard gates: the OS floor (WhisperKit / our deployment target = macOS 14) and disk
        // for the on-device model. Architecture is NOT a gate — Intel Macs run WhisperKit (slower).
        let os = ProcessInfo.processInfo.operatingSystemVersion
        let osString = "\(os.majorVersion).\(os.minorVersion)"
        if os.majorVersion < minOSMajor {
            blockers.append(.osBelow(minMajor: minOSMajor, current: osString))
        }

        let freeGB = freeDiskGB()
        if freeGB < minDiskGB {
            blockers.append(.lowDisk(neededGB: minDiskGB, freeGB: freeGB))
        }

        // Recommend Apple SpeechAnalyzer (fast, English) only where it's the safe known-good bet:
        // Apple Silicon + macOS 26. Everywhere else — Intel, or macOS 14–25 — default to WhisperKit
        // Large Turbo, which runs on any supported Mac. (Apple stays user-switchable; if it can't
        // run on an odd Intel-macOS-26 config, its load fails and we fall back to WhisperKit.)
        let appleAvailable = isAppleSilicon() && STTProvider.appleSpeech.isAvailable
        let provider: STTProvider = appleAvailable ? .appleSpeech : .whisperKit

        return SystemCapabilities(
            blockers: blockers,
            recommendedProvider: provider,
            cleanupIsFoundationModels: CleanupProvider.foundationModels.isAvailable,
            freeDiskGB: freeGB,
            osVersion: osString
        )
    }

    /// True on Apple Silicon HARDWARE, even if the process is running the x86_64 slice under Rosetta
    /// (`hw.optional.arm64` reflects the chip, not the running slice — more robust than `#if arch`).
    public static func isAppleSilicon() -> Bool {
        var value: Int32 = 0
        var size = MemoryLayout<Int32>.size
        let result = sysctlbyname("hw.optional.arm64", &value, &size, nil, 0)
        return result == 0 && value == 1
    }

    /// Free space (GB) on the home volume, using the "important usage" figure macOS reports to apps
    /// (accounts for purgeable space). Returns a large value if it can't be read, so disk never
    /// blocks on an unknown.
    public static func freeDiskGB() -> Double {
        let url = URL(fileURLWithPath: NSHomeDirectory())
        if let vals = try? url.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey]),
           let bytes = vals.volumeAvailableCapacityForImportantUsage {
            return Double(bytes) / 1_000_000_000
        }
        return .greatestFiniteMagnitude
    }
}
