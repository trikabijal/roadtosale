// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "DictationCore",
    platforms: [
        .macOS(.v14),
        .iOS(.v17),
    ],
    products: [
        .library(name: "DictationCore", targets: ["DictationCore"]),
    ],
    dependencies: [
        .package(url: "https://github.com/groue/GRDB.swift", from: "6.0.0"),
        .package(url: "https://github.com/argmaxinc/WhisperKit", from: "0.9.0"),
    ],
    targets: [
        .target(
            name: "DictationCore",
            dependencies: [
                .product(name: "GRDB", package: "GRDB.swift"),
                .product(name: "WhisperKit", package: "WhisperKit"),
            ],
            resources: [
                // Canonical copy lives at voice-engine/cleanup-packs/dictation.json
                .process("Resources"),
            ]
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
