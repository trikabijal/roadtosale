// swift-tools-version:5.9
//
// WhisperKitLiveSTT — Swift CLI for live microphone transcription via WhisperKit.
//
// Uses WhisperKit's AudioStreamTranscriber (LocalAgreement-2 + energy VAD)
// to stream from the mic and emit the same JSONL event format as the
// file-based WhisperKitSTT sibling.
//
// Two stability streams:
//   - "partial" events come from AudioStreamTranscriber.State.unconfirmedSegments
//     (volatile, re-emitted on each callback)
//   - "final"   events come from newly confirmed segments in
//     AudioStreamTranscriber.State.confirmedSegments (sealed by LocalAgreement-2)
//
// Invoked by Python via subprocess. See ../README.md for build and run
// instructions and the matching Python wrapper at
// ../../../lab/src/voice_lab/strategies/whisperkit_live.py.
//
// Version pinning notes:
//   Matches the WhisperKitSTT sibling pin exactly. The WhisperKit project
//   was renamed to "argmax-oss-swift" at v1.0.0 (2026-05-01), which removed
//   deprecated APIs and changed the package name. We pin to
//   .upToNextMajor(from: "0.13.0") to track the stable 0.13.x–0.18.x line.
//   When adopting v1.x, update both packages together.

import PackageDescription

let package = Package(
    name: "WhisperKitLiveSTT",
    platforms: [
        // WhisperKit ships macOS 13+. We use macOS 14 to match the
        // current WhisperKit README's stated minimum and keep the CoreML /
        // Speech / AVFoundation surface consistent with the rest of the
        // voice-engine native bundle.
        .macOS(.v14)
    ],
    products: [
        .executable(name: "WhisperKitLiveSTT", targets: ["WhisperKitLiveSTT"])
    ],
    dependencies: [
        // Argmax's open-source WhisperKit, MIT.
        // .upToNextMajor pulls in 0.13.x .. 0.x.x but excludes v1.0.0
        // (the argmax-oss-swift rename). Must stay in sync with WhisperKitSTT.
        .package(
            url: "https://github.com/argmaxinc/WhisperKit",
            .upToNextMajor(from: "0.13.0")
        )
    ],
    targets: [
        .executableTarget(
            name: "WhisperKitLiveSTT",
            dependencies: [
                .product(name: "WhisperKit", package: "whisperkit")
            ],
            path: "Sources/WhisperKitLiveSTT"
        )
    ]
)
