// swift-tools-version:5.9
//
// AppleSTT — Swift CLI for the voice-engine lab.
//
// Wraps Apple's on-device speech APIs and emits JSONL transcript events
// on stdout. Two modes:
//   - speech_transcriber : SpeechAnalyzer + SpeechTranscriber (macOS 26+/iOS 26+).
//                          No custom vocabulary.
//   - sfspeech_recognizer: SFSpeechRecognizer with contextualStrings vocab
//                          biasing. Legacy API, available on older OS too,
//                          required for dealership-term boosting.
//
// Invoked by Python via subprocess. See ../../README.md for build and run
// instructions and ../../lab/src/voice_lab/strategies/apple_speech_transcriber.py
// for the Python wrapper.

import PackageDescription

let package = Package(
    name: "AppleSTT",
    platforms: [
        // macOS 26 (Tahoe) is required for SpeechTranscriber.
        // SFSpeechRecognizer mode also works on lower macOS versions if you
        // bump this back, but the default target is Tahoe to keep the
        // SpeechTranscriber API surface available unconditionally.
        .macOS("26.0")
    ],
    products: [
        .executable(name: "AppleSTT", targets: ["AppleSTT"])
    ],
    targets: [
        .executableTarget(
            name: "AppleSTT",
            path: "Sources/AppleSTT",
            linkerSettings: [
                // Embed Info.plist into the executable's __TEXT,__info_plist
                // Mach-O section. SwiftPM CLI binaries have no bundle, so
                // macOS's privacy subsystem cannot surface the speech-
                // recognition consent prompt without an embedded plist —
                // the auth request silently never returns. The plist path
                // is resolved relative to the package root (where
                // `swift build` is invoked), so this assumes the standard
                // `swift build -c release` invocation from the package
                // directory.
                .unsafeFlags([
                    "-Xlinker", "-sectcreate",
                    "-Xlinker", "__TEXT",
                    "-Xlinker", "__info_plist",
                    "-Xlinker", "Info.plist"
                ])
            ]
        )
    ]
)
