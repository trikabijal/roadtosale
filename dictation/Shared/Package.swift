// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "DictationCore",
    platforms: [
        .macOS(.v14),
        .iOS(.v17),
    ],
    products: [
        // Lightweight base: GRDB + all types except WhisperKit.
        // Link this in the keyboard extension to stay under the ~70 MB OS limit.
        .library(name: "DictationCoreBase", targets: ["DictationCoreBase"]),
        // Full build: re-exports DictationCoreBase + adds WhisperKit + SpeechTranscriberFactory.
        // Link this in the macOS app and the iOS container app.
        .library(name: "DictationCore", targets: ["DictationCore"]),
    ],
    dependencies: [
        .package(url: "https://github.com/groue/GRDB.swift", from: "6.0.0"),
        .package(url: "https://github.com/argmaxinc/WhisperKit", from: "0.9.0"),
    ],
    targets: [
        // Base target: no WhisperKit. Used by the keyboard extension.
        .target(
            name: "DictationCoreBase",
            dependencies: [
                .product(name: "GRDB", package: "GRDB.swift"),
            ],
            path: "Sources/DictationCore",
            // WhisperKitTranscriber.swift lives here but belongs to the full target.
            exclude: ["WhisperKitTranscriber.swift"],
            resources: [.process("Resources")]
        ),
        // Full target: DictationCoreBase + WhisperKit. Used by macOS app.
        .target(
            name: "DictationCore",
            dependencies: [
                "DictationCoreBase",
                .product(name: "WhisperKit", package: "WhisperKit"),
            ],
            path: "Sources/DictationCoreWhisper"
        ),
        .testTarget(
            name: "DictationCoreTests",
            dependencies: ["DictationCore"]
        ),
        // Dev-only latency benchmark harness (not shipped, not a product). Measures batch vs
        // chunked STT + cleanup across clip lengths. Run: `swift run bench <clips-dir>`.
        .executableTarget(
            name: "bench",
            dependencies: ["DictationCore"]
        ),
    ]
)
