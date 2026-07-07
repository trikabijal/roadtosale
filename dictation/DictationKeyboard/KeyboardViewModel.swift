import AVFoundation
import DictationCoreBase
import os

private let log = Logger(subsystem: "com.trika.dictation", category: "KeyboardViewModel")

// MARK: - State

enum KeyboardDictationState: Equatable {
    case idle, recording, transcribing
}

// MARK: - Thread-safe audio accumulator (mirrors AppState.BufferAccumulator)

private final class AudioAccumulator: @unchecked Sendable {
    private let lock = NSLock()
    private var buffers: [AVAudioPCMBuffer] = []

    func append(_ buffer: AVAudioPCMBuffer) {
        lock.lock(); buffers.append(buffer); lock.unlock()
    }
    func snapshot() -> [AVAudioPCMBuffer] {
        lock.lock(); defer { lock.unlock() }; return buffers
    }
    func reset() {
        lock.lock(); buffers.removeAll(); lock.unlock()
    }
}

// MARK: - KeyboardViewModel

/// iOS equivalent of the macOS `AppState`: owns the idle→recording→transcribing state machine
/// for the keyboard extension. Text is inserted via `insertText` rather than `ClipboardPaster`.
@MainActor
final class KeyboardViewModel: ObservableObject {

    // MARK: Published

    @Published private(set) var state: KeyboardDictationState = .idle
    @Published private(set) var statusMessage: String = "Tap mic to dictate"
    @Published private(set) var micLevel: Float = 0
    @Published private(set) var showCorrectionPrompt = false
    @Published private(set) var lowInput = false

    /// Wired by `KeyboardViewController` — inserts the final text into the host text field.
    var insertText: ((String) -> Void)?
    /// Wired by `KeyboardViewController` — opens the container app (Flow Session) to record, since a
    /// keyboard extension can't access the microphone.
    var openApp: ((URL) -> Void)?

    // MARK: Private engines

    private let recordingEngine = RecordingEngine()
    private let transcriber: any SpeechTranscriber
    private let cleanupPack: CleanupPack
    private let cleanup: any TextCleanup
    private var telemetryStore: TelemetryStore?
    private let audio = AudioAccumulator()

    // Recording state
    private var recordingStartDate: Date?
    private var recordingPeakLevel: Float = 0
    private static let lowInputPeakThreshold: Float = 0.04
    // Hard safety stop — a missed tap can't leave the mic on indefinitely.
    private static let maxRecordingSeconds: Double = 300

    // Correction window
    private var correctionWindowTask: Task<Void, Never>?
    private var lastTranscriptId: String?

    // MARK: - Init

    init() {
        let pack = CleanupPackLoader.load()
        cleanupPack = pack
        // Rule-based cleanup: no LLM download, safe within the extension memory budget.
        // On iOS 26+ the factory will upgrade to Foundation Models automatically.
        cleanup = TextCleanupFactory.make(CleanupConfig(provider: .ruleBased, level: .light), pack: pack)
        // Apple Speech: no model download, ~10 MB — fits comfortably inside the keyboard extension.
        transcriber = AppleSpeechTranscriber()
        recordingEngine.delegate = self
        startListeningForDone()
        Task { await setup() }
    }

    private func setup() async {
        // Open the shared telemetry store (App Group). Non-fatal if the group is unavailable
        // in a simulator or an un-provisioned device — history simply won't persist.
        do {
            let url = try TelemetryStore.iOSDatabaseURL()
            telemetryStore = try TelemetryStore(databaseURL: url)
        } catch {
            log.error("Telemetry store unavailable: \(error.localizedDescription, privacy: .public)")
        }

        // Load the transcriber (no-op for Apple Speech, but follows the contract).
        do {
            try await transcriber.load()
        } catch {
            statusMessage = "Speech recognition unavailable"
            log.error("Transcriber load failed: \(error.localizedDescription, privacy: .public)")
        }
    }

    // MARK: - Public control

    /// TEMP diagnostic: let the controller surface responder-chain findings on the keyboard.
    func setDiagnostic(_ s: String) { statusMessage = s }

    func toggleRecording() {
        // The keyboard is the toggle. A keyboard extension can't touch the mic, so the container app
        // records invisibly in the background (it flash-launches to start the mic, then suspends back).
        // tap-to-start → launch app; tap-to-stop → Darwin-signal the app, which transcribes and posts
        // `done`; we then read the App Group and insert.
        switch state {
        case .idle:
            state = .recording
            statusMessage = "Listening… tap to stop"
            openApp?(DictationHandoff.recordURL)
        case .recording:
            state = .transcribing
            statusMessage = "Transcribing…"
            DictationHandoff.post(DictationHandoff.stopNotification)
        case .transcribing:
            break
        }
    }

    /// Register for the app's `done` signal so we insert the moment the transcript is ready — the app
    /// finishes asynchronously in the background after the keyboard has already reappeared.
    func startListeningForDone() {
        DictationHandoff.observe(DictationHandoff.doneNotification,
                                 observer: Unmanaged.passUnretained(self).toOpaque()) { _, observer, _, _, _ in
            guard let observer else { return }
            let vm = Unmanaged<KeyboardViewModel>.fromOpaque(observer).takeUnretainedValue()
            Task { @MainActor in vm.checkForHandoff() }
        }
    }

    /// Insert any transcript the container app left in the App Group (called on the `done` signal and
    /// when the keyboard reappears).
    func checkForHandoff() {
        guard let text = DictationHandoff.consume() else { return }
        insertText?(text)
        state = .idle
        statusMessage = "Inserted ✓"
        correctionWindowTask?.cancel()
        correctionWindowTask = Task { [weak self] in
            do { try await Task.sleep(for: .seconds(3)) } catch { return }
            self?.statusMessage = "Tap mic to dictate"
        }
    }

    private func legacyToggleRecording_unused() {
        switch state {
        case .idle:         startRecording()
        case .recording:    stopRecordingAndTranscribe()
        case .transcribing: break
        }
    }

    func markLastTranscriptCorrected() {
        guard let id = lastTranscriptId else { return }
        showCorrectionPrompt = false
        correctionWindowTask?.cancel()
        statusMessage = "Tap mic to dictate"
        Task { try? await telemetryStore?.markCorrected(id: id, note: nil) }
    }

    // MARK: - Recording

    private func startRecording() {
        guard transcriber.isLoaded else { return }
        // A keyboard extension must REQUEST mic permission before starting the audio engine — on
        // first tap this shows the system prompt; without it start() just throws. Needs the keyboard's
        // "Allow Full Access" to be on.
        Task { @MainActor in
            do {
                try await recordingEngine.requestPermission()
            } catch {
                statusMessage = "Allow the mic — turn on Full Access for the Just Talk keyboard"
                log.error("mic permission: \(error.localizedDescription, privacy: .public)")
                return
            }
            audio.reset()
            recordingPeakLevel = 0
            recordingStartDate = Date()
            // Warm the cleanup model while the user speaks so the cleanup at stop is fast.
            cleanup.prewarm()
            do {
                try recordingEngine.start()
                state = .recording
                statusMessage = "Listening…"
                showCorrectionPrompt = false
            } catch {
                // TEMP: surface the real error on the keyboard to diagnose the device mic failure.
                statusMessage = "Mic: \(error.localizedDescription)"
                log.error("RecordingEngine start: \(error.localizedDescription, privacy: .public)")
            }
        }
    }

    private func stopRecordingAndTranscribe() {
        guard state == .recording else { return }
        recordingEngine.stop()
        state = .transcribing
        statusMessage = "Transcribing…"
        micLevel = 0
        lowInput = false
        let buffers = audio.snapshot()
        let startDate = recordingStartDate ?? Date()
        Task { await performTranscription(buffers: buffers, audioStartDate: startDate) }
    }

    // MARK: - Transcription

    private func performTranscription(buffers: [AVAudioPCMBuffer], audioStartDate: Date) async {
        let result: TranscriptionResult
        do {
            result = try await transcriber.transcribe(buffers: buffers, audioStartDate: audioStartDate)
        } catch TranscriptionError.emptyResult, TranscriptionError.noAudioData {
            finishIdle()
            return
        } catch {
            state = .idle
            micLevel = 0
            statusMessage = "Didn't catch that — try again"
            log.error("Transcription error: \(error.localizedDescription, privacy: .public)")
            return
        }

        let rawText = result.text
        guard !rawText.isEmpty else { finishIdle(); return }

        let wordCount = rawText.split(whereSeparator: \.isWhitespace).count
        let shouldClean = wordCount >= cleanupPack.minWordsForCleanup
        let cleanupResult: CleanupResult?
        if shouldClean {
            statusMessage = "Cleaning…"
            cleanupResult = await cleanup.clean(CleanupRequest(rawText: rawText, level: .light))
        } else {
            cleanupResult = nil
        }
        let finalText = cleanupResult?.cleanedText ?? rawText
        guard !finalText.isEmpty else { finishIdle(); return }

        // Insert into whichever text field the user had focused.
        insertText?(finalText)

        // Persist for History / telemetry (shared SQLite via App Group).
        let record = TranscriptRecord(
            platform: "ios-keyboard",
            audioDurationMs: result.audioDurationMs,
            transcriptText: finalText,
            whisperkitConfidence: result.confidence,
            latencyMs: result.latencyMs,
            modelTier: "\(result.provider.rawValue)/\(result.model)",
            rawText: rawText,
            cleanupLevel: shouldClean ? CleanupLevel.light.rawValue : CleanupLevel.off.rawValue,
            cleanupProvider: cleanupResult.map {
                $0.usedFallback ? "\($0.provider.rawValue)+fallback" : $0.provider.rawValue
            }
        )
        lastTranscriptId = record.id
        try? await telemetryStore?.save(record)

        state = .idle
        statusMessage = "Inserted ✓"
        showCorrectionPrompt = true

        // 5-second correction window, then the prompt auto-dismisses.
        correctionWindowTask?.cancel()
        correctionWindowTask = Task { [weak self] in
            do { try await Task.sleep(for: .seconds(5)) } catch { return }
            self?.showCorrectionPrompt = false
            self?.statusMessage = "Tap mic to dictate"
        }
    }

    private func finishIdle() {
        state = .idle
        statusMessage = "Tap mic to dictate"
        micLevel = 0
        lowInput = false
    }
}

// MARK: - RecordingEngineDelegate

extension KeyboardViewModel: RecordingEngineDelegate {
    /// Called on the audio thread — append without hopping to the main actor (ordering matters).
    nonisolated func recordingEngine(_ engine: RecordingEngine, didReceiveBuffer buffer: AVAudioPCMBuffer) {
        audio.append(buffer)
    }

    /// VAD silence is not used for auto-stop — the user taps the button to finish.
    nonisolated func recordingEngineDidDetectSilence(_ engine: RecordingEngine) {}

    nonisolated func recordingEngine(_ engine: RecordingEngine, didUpdateLevel level: Float) {
        Task { @MainActor [weak self] in
            guard let self, state == .recording else { return }
            micLevel = level
            evaluateInputLevel(level)
        }
    }

    private func evaluateInputLevel(_ level: Float) {
        guard state == .recording, let start = recordingStartDate else { return }
        // Hard safety stop so a forgotten recording can't drain the battery.
        if Date().timeIntervalSince(start) > Self.maxRecordingSeconds {
            stopRecordingAndTranscribe()
            return
        }
        recordingPeakLevel = max(recordingPeakLevel, level)
        // Give ~2s before judging loudness so brief pauses don't trip the warning.
        guard Date().timeIntervalSince(start) > 2 else { return }
        lowInput = recordingPeakLevel < Self.lowInputPeakThreshold
    }
}
