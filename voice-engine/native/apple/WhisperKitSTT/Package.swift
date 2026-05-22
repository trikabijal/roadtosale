// swift-tools-version:5.9
//
// WhisperKitSTT — Swift CLI for the voice-engine lab.
//
// Wraps Argmax's open-source WhisperKit (MIT-licensed, on-device Whisper)
// and emits JSONL transcript events on stdout. Sibling to AppleSTT.
//
// Two stability streams:
//   - "partial" events come from the WhisperKit TranscriptionCallback
//     (TranscriptionProgress text mid-decode)
//   - "final"   events come from the segmentDiscoveryCallback
//     (each TranscriptionSegment as it is sealed during seeking)
//
// Invoked by Python via subprocess. See ../README.md for build and run
// instructions and the matching Python wrapper at
// ../../../lab/src/voice_lab/strategies/whisperkit.py.
//
// Version pinning notes:
//   The WhisperKit project was renamed to "Argmax Open-Source SDK"
//   (package name `argmax-oss-swift`) at v1.0.0 (released 2026-05-01).
//   That release removed deprecated APIs and changed the package name,
//   so we pin to .upToNextMajor(from: "0.13.0") which tracks the stable
//   pre-rename line (0.13.x through 0.18.x; package name "whisperkit",
//   library product "WhisperKit"). When we adopt v1.x we will need to
//   update the dependency URL to `argmax-oss-swift` and the package
//   reference here to match.

import PackageDescription

let package = Package(
    name: "WhisperKitSTT",
    platforms: [
        // WhisperKit ships macOS 13+. We bump to macOS 14 to match the
        // current WhisperKit README's stated minimum and keep the CoreML /
        // Speech / AVFoundation surface consistent with the rest of the
        // voice-engine native bundle.
        .macOS(.v14)
    ],
    products: [
        .executable(name: "WhisperKitSTT", targets: ["WhisperKitSTT"])
    ],
    dependencies: [
        // Argmax's open-source WhisperKit, MIT.
        // .upToNextMajor pulls in 0.13.x .. 0.x.x but excludes v1.0.0
        // (the argmax-oss-swift rename).
        .package(
            url: "https://github.com/argmaxinc/WhisperKit",
            .upToNextMajor(from: "0.13.0")
        )
    ],
    targets: [
        .executableTarget(
            name: "WhisperKitSTT",
            dependencies: [
                .product(name: "WhisperKit", package: "whisperkit")
            ],
            path: "Sources/WhisperKitSTT"
        )
    ]
)
