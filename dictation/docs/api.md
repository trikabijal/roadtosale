# DictationCore — API Reference

> **Module docs:** [architecture.md](architecture.md) · [api.md](api.md) (this file) · [flows.md](flows.md)
> **Mirrors:** [../../voice-engine/docs/model-contracts.md](../../voice-engine/docs/model-contracts.md) (the streaming sibling of these contracts)

Just Talk has **no backend** — it runs entirely on-device. The "API" is therefore the public
Swift surface of the **DictationCore** package
([`dictation/Shared/Sources/DictationCore/`](../Shared/Sources/DictationCore)), i.e. the facade
the macOS app (`JustTalk/AppState`) and the iOS keyboard (`DictationKeyboard/KeyboardViewModel`)
program against. Nothing behind these types is consumed directly by a target.

Everything below is `public`. Types are grouped by the file that defines them.

---

## 1. Speech-to-text — `SpeechTranscriber.swift`

The speech-to-text model, behind a provider-agnostic contract.

### Protocol

```swift
@MainActor
public protocol SpeechTranscriber: AnyObject {
    var isLoaded: Bool { get }
    func load(onProgress: (@MainActor (Double) -> Void)?) async throws   // download (first run) + load
    func transcribe(buffers: [AVAudioPCMBuffer], audioStartDate: Date) async throws -> TranscriptionResult
    func setVocabularyBias(_ terms: [String])   // optional; default no-op
    func reset()                                 // release the loaded model; default no-op
    func makeStreamingSession() -> (any StreamingTranscriber)?   // live pill; default nil
}
public extension SpeechTranscriber {
    func load() async throws                     // convenience: load(onProgress: nil)
}
```

- `makeStreamingSession()` vends a **streaming** session for the live pill (PRD 0008) that reuses
  this transcriber's already-loaded model — no second model, no second mic. Returns `nil` when the
  provider can't stream (the caller falls back to the per-segment preview). The accurate **pasted**
  text always comes from `transcribe(buffers:)`, never from the streaming session. See §8.

- `load(onProgress:)` reports download completion (0.0–1.0) on the main actor — the first run
  fetches a ~40 MB–1 GB model.
- `reset()` drops the loaded model so an orphaned/timed-out transcribe can't keep holding it;
  the next `transcribe` reloads.

### Result

```swift
public struct TranscriptionResult: Sendable {
    public let text: String
    public let confidence: Double      // 0.0–1.0
    public let audioDurationMs: Int
    public let latencyMs: Int
    public let provider: STTProvider
    public let model: String
}
```

### Provider, config, factory

```swift
public enum STTProvider: String, CaseIterable, Sendable {
    case whisperKit, appleSpeech, mock
    public var displayName: String { get }
    public var isAvailable: Bool { get }              // appleSpeech is contract-ready but false (not implemented)
    public static var selectable: [STTProvider] { get } // [.whisperKit, .appleSpeech]
}

public struct STTConfig: Sendable, Equatable {
    public var provider: STTProvider
    public var model: String                           // whisperKit: a ModelTier.rawValue
    public static let `default`                        // whisperKit + largeV3Turbo
    public var modelDisplayName: String { get }
}

@MainActor
public enum SpeechTranscriberFactory {
    public static func make(_ config: STTConfig) -> any SpeechTranscriber
}
```

### Errors + test doubles

```swift
public enum TranscriptionError: Error, LocalizedError {
    case modelNotLoaded, noAudioData, emptyResult, providerUnavailable(String)
}

@MainActor public final class MockTranscriber: SpeechTranscriber { ... }        // deterministic, no download
@MainActor public final class UnavailableTranscriber: SpeechTranscriber { ... } // throws providerUnavailable
```

---

## 2. WhisperKit implementation — `WhisperKitTranscriber.swift`

The live `whisperKit` provider.

```swift
public enum ModelTier: String, CaseIterable, Sendable {
    case tinyEn  = "openai_whisper-tiny.en"               // ~40 MB — fits iOS keyboard memory budget
    case baseEn  = "openai_whisper-base.en"               // ~75 MB
    case smallEn = "openai_whisper-small.en"              // ~150 MB — fast English-only
    case small   = "openai_whisper-small"                 // multilingual small
    case largeV3Turbo = "openai_whisper-large-v3_turbo_954MB" // DEFAULT — multilingual, balanced
    case largeV3 = "openai_whisper-large-v3"              // best accuracy, ~3 GB
    public var displayName: String { get }
}

@MainActor
public final class WhisperKitTranscriber: SpeechTranscriber {
    public private(set) var isLoaded: Bool
    public let modelTier: ModelTier
    public init(modelTier: ModelTier = .largeV3Turbo)
    public func reset()
    public func setVocabularyBias(_ terms: [String])      // becomes a decoder conditioning prompt
    public static func modelDownloadBase() throws -> URL   // Application Support, not ~/Documents
    public func load(onProgress: (@MainActor (Double) -> Void)?) async throws
    public func transcribe(buffers:audioStartDate:) async throws -> TranscriptionResult
}
```

Built-in safeguards (not separate API, but contractually relevant): silence-floor rejection,
gain normalization of quiet audio, a known-junk-phrase + low-confidence hallucination filter,
and rejecting long clips that yield <3 words (so the audio is preserved for retry rather than
pasting garbage).

---

## 3. Cleanup — `TextCleanup.swift`

The cleanup model (the on-device LLM that polishes the text), behind a provider-agnostic
contract. **`clean` never throws** — cleanup must never block paste; implementations fall back
internally.

### Protocol

```swift
public protocol TextCleanup: Sendable {
    func clean(_ request: CleanupRequest) async -> CleanupResult
    func prewarm()   // warm the model ahead of clean (e.g. when recording starts); default no-op
    func reset()     // release any held model/session; default no-op
}
```

### Levels, provider, request/result, config

```swift
public enum CleanupLevel: String, CaseIterable, Sendable, Codable { case off, light, full }

public enum CleanupProvider: String, CaseIterable, Sendable {
    case foundationModels, ruleBased
    public var isAvailable: Bool { get }   // foundationModels requires macOS 26+/iOS 26+ build
}

public struct CleanupRequest: Sendable {
    public var rawText: String
    public var level: CleanupLevel
    public var vocab: [String: String]            // forced spellings, applied AFTER cleanup
    public var commandGrammar: [String: String]   // e.g. "new paragraph" → "\n\n"
    public var profile: String                    // "dictation" | "road-to-sale"
    public var priorContext: String               // LEGACY, unused — see note below
}

public struct CleanupResult: Sendable {
    public var cleanedText: String
    public var opsApplied: [String]
    public var usedFallback: Bool                 // true when it fell back to rule-based
    public var latencyMs: Int
    public var provider: CleanupProvider
}

public struct CleanupConfig: Sendable, Equatable {
    public var provider: CleanupProvider
    public var level: CleanupLevel
    public static let `default`                   // foundationModels + full
}
```

> **`priorContext` is legacy and unused.** It once threaded the previously-cleaned sentence into
> the `FoundationModelsCleanup` prompt for per-sentence cleanup; the small on-device model echoed
> it, snowballing into 3–4× repeated sentences (`qc/bugs/streaming/repeated-sentence.md`). Cleanup
> now runs **once over the whole transcript**, so the FM `taskPrompt` no longer takes or uses it.
> The field remains (defaulting to `""`) only for source compatibility — ignore it.

### Cleanup packs (knowledge as data)

```swift
public struct Lexicon: Codable, Sendable, Equatable {
    public var terms: [String]                    // canonical spellings to force (e.g. "F&I")
    public var expansions: [String: String]       // spoken → canonical (e.g. "f and i" → "F&I")
    public static let empty: Lexicon
    public var termMap: [String: String] { get }
}

public struct CleanupPack: Codable, Sendable {
    public var profile: String
    public var minWordsForCleanup: Int            // skip cleanup below this word count
    public var commandGrammar: [String: String]
    public var fillers: [String]
    public var junkPhrases: [String]
    public var prompts: [String: String]          // keyed by CleanupLevel.rawValue
    public var lexicon: Lexicon                    // populated for road-to-sale; empty for dictation
    public static let fallback: CleanupPack        // built-in safety net (kept in sync with the JSON)
    public func mergingLexiconTerms(_ extra: [String]) -> CleanupPack   // merge per-dealer catalog terms
}

public enum CleanupPackLoader {
    public static func load(profile: String = "dictation") -> CleanupPack   // also "road-to-sale"
}
```

`load(profile:)` reads `Resources/<profile>-cleanup-pack.json` from DictationCore's own
bundle, falling back to `CleanupPack.fallback` if missing/corrupt.

### Implementations + factory

```swift
public struct RuleBasedCleanup: TextCleanup {     // deterministic fallback + the ruleBased provider
    public init(pack: CleanupPack)
}

@available(macOS 26.0, iOS 26.0, *)
public final class FoundationModelsCleanup: TextCleanup, @unchecked Sendable {
    public init(pack: CleanupPack, fallback: RuleBasedCleanup)
    public func prewarm()                          // warm the on-device model during recording
    public func reset()
}

public enum TextCleanupFactory {
    public static func make(_ config: CleanupConfig, pack: CleanupPack) -> any TextCleanup
}
```

The factory returns `RuleBasedCleanup` for `ruleBased`, and `FoundationModelsCleanup` (with a
rule-based fallback) for `foundationModels` when the OS supports it — otherwise rule-based.

> `CleanupOutputSanitizer` (`CleanupOutputSanitizer.swift`) is `internal`, not part of the
> public facade — it's an implementation detail of `FoundationModelsCleanup`, exposed only for
> unit testing.

---

## 4. Recording engine — `RecordingEngine.swift`

```swift
public protocol RecordingEngineDelegate: AnyObject {
    func recordingEngine(_ engine: RecordingEngine, didReceiveBuffer buffer: AVAudioPCMBuffer)
    func recordingEngineDidDetectSilence(_ engine: RecordingEngine)
    func recordingEngine(_ engine: RecordingEngine, didUpdateLevel level: Float)  // optional (default no-op)
}

public final class RecordingEngine: NSObject {
    public static let targetSampleRate: Double   // 16_000
    public static let targetChannels: AVAudioChannelCount
    public var silenceThreshold: Float           // default 0.01
    public var silenceDurationMs: Int            // default 800
    public weak var delegate: RecordingEngineDelegate?

    public func requestPermission() async throws // platform-appropriate mic permission
    public func start() throws                   // installs the tap, converts to 16 kHz mono Float32
    public func stop()
}

public enum RecordingError: Error, LocalizedError {
    case microphonePermissionDenied, engineFailedToStart(Error), audioFormatUnavailable
}
```

Delegate callbacks arrive on an arbitrary audio thread — bridge to the main actor before
touching UI/state.

---

## 5. Recording store — `RecordingStore.swift`

A **cross-platform contract** (audio as neutral `[Float]` samples + sample rate).

```swift
public struct StoredRecording: Sendable, Identifiable, Equatable {
    public let id: String; public let recordedAt: Date; public let durationMs: Int
}

public protocol RecordingStore: Sendable {
    var maxRecordings: Int { get }
    @discardableResult
    func save(samples: [Float], sampleRate: Double, recordedAt: Date) throws -> StoredRecording
    func recent() -> [StoredRecording]
    func loadSamples(id: String) throws -> (samples: [Float], sampleRate: Double)
}

public enum RecordingStoreError: Error { case notFound, writeFailed, readFailed }

public final class FileRecordingStore: RecordingStore {     // Apple impl — Float32 mono WAV files
    public init(directory: URL, maxRecordings: Int = 5) throws
    public static func macOS(maxRecordings: Int = 5) throws -> FileRecordingStore
}

public enum AudioSampleBridge {   // bridge neutral [Float] ⇄ AVAudioPCMBuffer
    public static func flatten(_ buffers: [AVAudioPCMBuffer]) -> [Float]
    public static func makeBuffer(samples: [Float], sampleRate: Double) -> AVAudioPCMBuffer?
}
```

---

## 6. Telemetry store — `TelemetryStore.swift`

A Swift `actor` over GRDB SQLite.

```swift
public struct TranscriptRecord: Identifiable, Codable, FetchableRecord, PersistableRecord, Sendable {
    public var id: String
    public var platform: String            // "mac" | "ios"
    public var recordedAt: Date
    public var audioDurationMs: Int
    public var transcriptText: String      // final pasted text (post-cleanup)
    public var wordCount: Int
    public var whisperkitConfidence: Double
    public var latencyMs: Int
    public var modelTier: String           // "<sttProvider>/<model>"
    public var frontmostApp: String?       // macOS only
    public var wasCorrected: Bool
    public var correctionNote: String?
    public var rawText: String?            // pre-cleanup STT output
    public var cleanupLevel: String?       // "off" | "light" | "full"
    public var cleanupProvider: String?    // cleanup provider used, nil if skipped
    // init(...) with sensible defaults; wordCount derived from transcriptText if nil
}

public struct WeeklyStats: Sendable { /* totalCount, correctionRate, avg*, totalAudioMs */ public static let empty }
public struct UsageTotals: Sendable { /* totalCount, totalAudioMs; totalMinutes/totalHours */ public static let empty }

public actor TelemetryStore {
    public init(databaseURL: URL) throws
    public func save(_ record: TranscriptRecord) throws
    @discardableResult public func purge(olderThanDays days: Int) throws -> Int   // privacy retention
    public func markCorrected(id: String, note: String?) throws
    public func fetchRecent(limit: Int = 5) throws -> [TranscriptRecord]
    public func search(matching query: String, limit: Int = 100) throws -> [TranscriptRecord]
    public func fetchWeeklyStats() throws -> WeeklyStats
    public func fetchUsageTotals() throws -> UsageTotals
    public static func macOSDatabaseURL() throws -> URL
    public static func iOSDatabaseURL() throws -> URL    // App Group container
}

public enum TelemetryStoreError: Error { case appGroupUnavailable }
```

---

## 7. Timeout utility — `Timeout.swift`

```swift
public struct TimeoutError: Error { public init() }

public func withTimeout<T: Sendable>(
    seconds: Double,
    operation: @escaping @Sendable () async throws -> T
) async throws -> T
```

An unstructured race — throws `TimeoutError` at the deadline regardless of whether `operation`
honors cancellation, so a hung model call can't block fallback/paste. Used by `AppState` to cap
transcription and by `FoundationModelsCleanup` to cap the LLM call.

---

## 8. Streaming dictation & capture buffer

The macOS dictation flow runs a **single streaming path**: STT streams per VAD-delimited segment
during speech (for the live pill), then cleanup runs **once at stop** over the whole transcript.

### Captured audio stream — `CapturedAudioStream.swift`

```swift
public final class CapturedAudioStream: @unchecked Sendable {
    public init()
    public func append(_ buffer: AVAudioPCMBuffer)                    // O(1), no syscall — audio render thread
    public func snapshot() -> [AVAudioPCMBuffer]                      // all buffers, in order (COW retain)
    public func drain(after index: Int) -> (buffers: [AVAudioPCMBuffer], count: Int)  // new tail only
    public var count: Int { get }
    public func reset()
}
```

Order-preserving, thread-safe hold for captured buffers, backed by an `os_unfair_lock` with a
strictly O(1) critical section so the audio render thread is never meaningfully blocked. Replaces
the old `NSLock`-based `BufferAccumulator` (a syscall per append). Appending synchronously in
arrival order on the serial audio thread is what keeps long recordings in temporal order.

### Streaming session — `StreamingDictationSession.swift`

```swift
public struct StreamingResult: Sendable {
    public let cleanedText: String     // what gets pasted
    public let rawText: String         // concatenated raw STT (telemetry / learnings dataset)
}

@MainActor
public final class StreamingDictationSession {
    public init(transcriber: any SpeechTranscriber, cleanup: any TextCleanup, level: CleanupLevel,
                vocab: [String: String] = [:], commandGrammar: [String: String] = [:],
                profile: String = "dictation")
    public var confirmedText: String { get }   // raw transcript assembled so far (the live pill)
    public var partialText: String { get }     // always "" in the once-at-stop model (HUD API compat)
    public func prewarm()
    @discardableResult
    public func ingest(segment: [AVAudioPCMBuffer], audioStartDate: Date) async -> String
    public func finish() async -> StreamingResult
}
```

`ingest` transcribes one VAD-delimited segment with the **single** main transcriber and appends its
raw text (errors are swallowed so a bad segment can't abort the dictation). `finish` assembles the
full raw transcript and runs **one** `cleanup.clean(...)` pass — no `priorContext`, no per-sentence
session. There is no second (preview) model.

### Streaming transcriber — `StreamingTranscriber.swift` (PRD 0008, live pill)

The streaming sibling of `SpeechTranscriber` — feeds a growing audio buffer and emits a
LocalAgreement-2 confirmed/hypothesis split for the **per-word roll-up pill**. It drives the live
HUD only; the pasted text is still the batch `transcribe(buffers:)` pass.

```swift
public struct StreamingTranscript: Sendable, Equatable {
    public let confirmed: String     // stable prefix — render solid, won't change
    public let hypothesis: String    // tentative tail — render dimmed, may still change
    public let confidence: Double
    public let latencyMs: Int
    public var display: String { get }   // confirmed + " " + hypothesis
    public static let empty: StreamingTranscript
}

@MainActor
public protocol StreamingTranscriber: AnyObject {
    func step(samples: [Float]) async -> StreamingTranscript      // one incremental pass over audio-so-far
    func finish(samples: [Float]?) async -> StreamingTranscript   // promote hypothesis → confirmed
    func reset()
}

@MainActor public final class MockStreamingTranscriber: StreamingTranscriber { ... }   // deterministic, no model
```

- **`StreamingAgreement`** (`StreamingAgreement.swift`) — the pure, model-free LocalAgreement-2 logic
  (confirmed/unconfirmed split + `lastConfirmedEnd` windowing), lifted from WhisperKit's
  `AudioStreamTranscriber` so it can run over our own fed buffers. Unit-tested with synthetic
  segments (`StreamingAgreementTests`).
- **`WhisperKitStreamingSession`** (in `WhisperKitTranscriber.swift`) — vended by
  `WhisperKitTranscriber.makeStreamingSession()`; reuses the loaded `WhisperKit` instance, decodes
  with `clipTimestamps=[lastConfirmedEnd]` + the vocab-bias prompt, and stops running new passes past
  a `maxStreamSeconds` guard (caps the re-encode cost on long dictations — the pill freezes, the
  batch output is unaffected).

## 9. Semantic dictation state — `DictationState.swift`

The semantic state surface. `AppState` exposes computed `phase`/`availability` derived from its
existing published fields; the **menu bar reads them** (status glyph + warming spinner) instead of
poking `engineLoaded`/`dictationState`. Those underlying fields remain the source until the
`DictationEngine` facade emits these directly — at which point the `.inserted`/`.failed` terminals
(which the derivation can't yet produce) light up too.

```swift
public enum DictationPhase: Equatable {
    case idle, capturing, finishing, inserted
    case failed(FailReason)
    public enum FailReason: Equatable { case transcription, cleanup, noSpeech, timeout }
}

public enum EngineAvailability: Equatable {
    case warmingUp, ready
    case blocked(BlockReason)
    public enum BlockReason: Equatable { case microphoneDenied, accessibilityDenied, modelUnavailable }
}
```

`DictationPhase` answers "what is THIS dictation doing right now" (the semantic successor to
`dictationState`, with success `inserted` and `failed(reason)` terminals made explicit);
`EngineAvailability` answers "can the system work at all right now" (the successor to
`engineLoaded: Bool` + load-related `statusMessage`, composing mic-permission, Accessibility, and
model-warmth — something a single `Bool` can't express).

---

## How the app targets use this facade

- **macOS** (`JustTalk/AppState.swift`): builds a transcriber via `SpeechTranscriberFactory`
  and cleanup via `TextCleanupFactory`, loads a `CleanupPack` via `CleanupPackLoader`, drives
  `RecordingEngine`, and writes through `FileRecordingStore` + `TelemetryStore`.
- **iOS** (`DictationKeyboard/KeyboardViewModel.swift`, in git history): the same pattern with
  the `tinyEn` tier and the App Group `TelemetryStore`, inserting via `textDocumentProxy`.

See [flows.md](flows.md) for the end-to-end traces.
