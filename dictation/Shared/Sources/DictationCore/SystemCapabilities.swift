import Foundation

/// A pre-flight check of whether THIS Mac can run Just Talk, and which STT provider to default to.
/// Run at launch so a machine that can't run the app is told clearly ("update macOS", "needs Apple
/// Silicon", "free up disk") up front — instead of failing silently mid-onboarding or on the first
/// model download. Goal: never lose a user to an onboarding that just breaks.
public struct SystemCapabilities: Sendable, Equatable {
    /// A hard reason the app can't run on this machine. Empty = good to go. These match what the
    /// engine vendors PUBLISH: WhisperKit (Argmax) is "On-device Speech AI for Apple Silicon, macOS
    /// 14+" (Intel not supported); Apple Intelligence / on-device models require "M1 or later, 8 GB
    /// RAM". So the floor is Apple Silicon + 8 GB + macOS 14 — sourced, not guessed.
    public enum Blocker: Sendable, Equatable {
        case notAppleSilicon
        case osBelow(minMajor: Int, current: String)
        case lowDisk(neededGB: Double, freeGB: Double)
        case lowRAM(neededGB: Double, actualGB: Double)
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
    /// Minimum installed RAM. WhisperKit / Whisper large-v3-turbo needs ~1.5–2.5 GB working memory
    /// for CoreML inference plus audio buffers + OS/app overhead; a 4 GB Mac can't run it. 8 GB is
    /// the practical floor — and the floor of every Apple Silicon Mac and every macOS-14 Intel Mac,
    /// so this only ever catches an unusually old/small machine. This is the "powerful enough" gate.
    public static let minRAMGB: Double = 8.0

    public static func check() -> SystemCapabilities {
        var blockers: [SystemCapabilities.Blocker] = []

        // Apple Silicon is a HARD requirement per both vendors' published specs (WhisperKit is "for
        // Apple Silicon" / no Intel support; Apple Intelligence needs M1+). Intel Macs — even the few
        // that run macOS 26 — get neither a supported STT engine nor on-device cleanup, so we block
        // them with a clear message rather than a degraded experience.
        if !isAppleSilicon() { blockers.append(.notAppleSilicon) }

        let os = ProcessInfo.processInfo.operatingSystemVersion
        let osString = "\(os.majorVersion).\(os.minorVersion)"
        if os.majorVersion < minOSMajor {
            blockers.append(.osBelow(minMajor: minOSMajor, current: osString))
        }

        let freeGB = freeDiskGB()
        if freeGB < minDiskGB {
            blockers.append(.lowDisk(neededGB: minDiskGB, freeGB: freeGB))
        }

        // "Powerful enough" gate = RAM (a reliable, checkable proxy; CPU generation isn't). Use a
        // small margin below 8 GB so a true 8 GB Mac (reports exactly 8.0 GiB) passes and only 4/6 GB
        // machines are caught.
        let ramGB = physicalRAMGB()
        if ramGB < minRAMGB - 0.5 {
            blockers.append(.lowRAM(neededGB: minRAMGB, actualGB: ramGB))
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
    /// Installed physical RAM in GiB, from `hw.memsize`.
    public static func physicalRAMGB() -> Double {
        var bytes: UInt64 = 0
        var size = MemoryLayout<UInt64>.size
        let result = sysctlbyname("hw.memsize", &bytes, &size, nil, 0)
        guard result == 0, bytes > 0 else { return .greatestFiniteMagnitude }
        return Double(bytes) / (1024 * 1024 * 1024)
    }

    public static func freeDiskGB() -> Double {
        let url = URL(fileURLWithPath: NSHomeDirectory())
        if let vals = try? url.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey]),
           let bytes = vals.volumeAvailableCapacityForImportantUsage {
            return Double(bytes) / 1_000_000_000
        }
        return .greatestFiniteMagnitude
    }
}
