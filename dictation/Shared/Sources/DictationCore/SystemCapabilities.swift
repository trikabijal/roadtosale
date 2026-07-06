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
        // Read the real machine, then hand the raw facts to the PURE `decide` — which is unit-tested
        // with synthetic inputs (the env reads themselves can't be faked in a test).
        let os = ProcessInfo.processInfo.operatingSystemVersion
        return decide(
            isAppleSilicon: isAppleSilicon(),
            osMajor: os.majorVersion,
            osVersion: "\(os.majorVersion).\(os.minorVersion)",
            freeDiskGB: freeDiskGB(),
            ramGB: physicalRAMGB(),
            appleAvailable: STTProvider.appleSpeech.isAvailable,
            cleanupIsFoundationModels: CleanupProvider.foundationModels.isAvailable
        )
    }

    /// Pure requirements decision — no environment reads, so it is deterministically unit-testable.
    /// Requirements are vendor-sourced: Apple Silicon (WhisperKit "for Apple Silicon" / Apple
    /// Intelligence M1+), 8 GB RAM (Apple's published on-device-AI minimum), macOS 14 (WhisperKit /
    /// deployment target), ~2 GB disk (model). Apple SpeechAnalyzer is recommended only on the safe
    /// known-good config (Apple Silicon + macOS 26); everywhere else WhisperKit Large Turbo.
    static func decide(
        isAppleSilicon: Bool,
        osMajor: Int,
        osVersion: String,
        freeDiskGB: Double,
        ramGB: Double,
        appleAvailable: Bool,
        cleanupIsFoundationModels: Bool
    ) -> SystemCapabilities {
        var blockers: [SystemCapabilities.Blocker] = []
        if !isAppleSilicon { blockers.append(.notAppleSilicon) }
        if osMajor < minOSMajor { blockers.append(.osBelow(minMajor: minOSMajor, current: osVersion)) }
        if freeDiskGB < minDiskGB { blockers.append(.lowDisk(neededGB: minDiskGB, freeGB: freeDiskGB)) }
        // Small margin below 8 GB so a true 8 GB Mac (reports exactly 8.0 GiB) passes; only 4/6 GB caught.
        if ramGB < minRAMGB - 0.5 { blockers.append(.lowRAM(neededGB: minRAMGB, actualGB: ramGB)) }

        let useApple = isAppleSilicon && appleAvailable
        return SystemCapabilities(
            blockers: blockers,
            recommendedProvider: useApple ? .appleSpeech : .whisperKit,
            cleanupIsFoundationModels: cleanupIsFoundationModels,
            freeDiskGB: freeDiskGB,
            osVersion: osVersion
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
