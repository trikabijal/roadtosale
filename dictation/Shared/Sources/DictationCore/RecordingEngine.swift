import AVFoundation

// MARK: - Audio level thresholds (single source of truth)

/// The one place the app decides "is the user actually audible?" — a peak RMS level (0…1). Used by
/// BOTH the wizard mic test (pass when the peak reaches this) and the recording low-input warning
/// (warn when even the peak stays below this), so the two can never disagree. Sits above room noise
/// and below normal speech.
/// Cross-platform dictation limits — one source so macOS and iOS agree.
public enum DictationLimits {
    /// Hard cap on a SINGLE dictation take (both platforms) so a forgotten mic can't grow unbounded.
    public static let maxSingleTakeSeconds: Double = 600
}

/// Default speech locale — one source for the ~handful of "en-US" hardcodes across both platforms.
public enum SpeechDefaults {
    public static let locale = "en-US"
}

public enum AudioLevels {
    /// "Is the user audible?" — wizard mic-test pass + recording low-input warning use this peak RMS.
    public static let audibleThreshold: Float = 0.035
    /// Recording engine: continuous peak below this counts as a silence segment boundary (≈ -40 dBFS).
    public static let silenceThreshold: Float = 0.01
    /// WhisperKit hallucination filter: clip peak below this is treated as silence (≈ -34 dBFS).
    /// Conservative so quiet speech survives. Co-located here so the three "audible" knobs tune together.
    public static let silenceFloor: Float = 0.02
}

// MARK: - Delegate protocol

public protocol RecordingEngineDelegate: AnyObject {
    /// Called continuously as audio arrives (on an arbitrary audio queue).
    func recordingEngine(_ engine: RecordingEngine, didReceiveBuffer buffer: AVAudioPCMBuffer)
    /// Called when silence is detected for longer than silenceDurationMs after speech has been heard.
    func recordingEngineDidDetectSilence(_ engine: RecordingEngine)
    /// Per-buffer RMS level (0…~1), for UI meters. Optional.
    func recordingEngine(_ engine: RecordingEngine, didUpdateLevel level: Float)
    /// An audio-config change stopped the engine and it could NOT re-arm (e.g. no usable input device).
    /// Capture is effectively dead until stop() — the delegate should end the recording + tell the user,
    /// not keep showing a live-looking meter. Optional.
    func recordingEngineDidFailToRecover(_ engine: RecordingEngine)
}

public extension RecordingEngineDelegate {
    func recordingEngine(_ engine: RecordingEngine, didUpdateLevel level: Float) {}
    func recordingEngineDidFailToRecover(_ engine: RecordingEngine) {}
}

// MARK: - Errors

public enum RecordingError: Error, LocalizedError {
    case microphonePermissionDenied
    case engineFailedToStart(Error)
    case audioFormatUnavailable

    public var errorDescription: String? {
        switch self {
        case .microphonePermissionDenied: return "Microphone access was denied. Enable it in System Settings."
        case .engineFailedToStart(let e): return "Audio engine failed to start: \(e.localizedDescription)"
        case .audioFormatUnavailable: return "Could not configure audio input format."
        }
    }
}

// MARK: - RecordingEngine

public final class RecordingEngine: NSObject {

    // WhisperKit requires 16kHz mono Float32
    public static let targetSampleRate: Double = 16_000
    public static let targetChannels: AVAudioChannelCount = 1

    private let audioEngine = AVAudioEngine()
    private var converter: AVAudioConverter?
    private let targetFormat = AVAudioFormat(
        commonFormat: .pcmFormatFloat32,
        sampleRate: RecordingEngine.targetSampleRate,
        channels: RecordingEngine.targetChannels,
        interleaved: false
    )!

    /// RMS below this threshold counts as silence. Default 0.01 (-40 dBFS approx).
    public var silenceThreshold: Float = AudioLevels.silenceThreshold
    /// How long (ms) continuous silence triggers the delegate callback.
    public var silenceDurationMs: Int = 800

    public weak var delegate: RecordingEngineDelegate?

    /// When true (default) the engine configures + activates the iOS `AVAudioSession` on start and
    /// deactivates it on stop. Set FALSE when an owner (the Flow Session) manages the session itself —
    /// e.g. it keeps a silent keep-alive playing between dictations, and this engine must NOT
    /// deactivate the shared session out from under it.
    public var managesAudioSession = true

    // VAD state
    private var hasSpeechStarted = false
    private var silenceStartDate: Date?

    private var isRunning = false

    // Observer for AVAudioEngineConfigurationChange. macOS posts this — and STOPS the engine,
    // killing the tap — whenever the audio I/O config changes: another app grabs/changes the
    // default device, headphones plug in, sample rate shifts, etc. ("other software loaded").
    // Without handling it, capture silently freezes mid-recording and only the audio BEFORE the
    // change is kept → the classic "only the first part transcribed" bug. We re-arm the tap and
    // restart the engine in place; the accumulated buffers live in the caller, so nothing already
    // captured is lost. Serialized on the main queue with a guard so a burst can't re-enter.
    private var configChangeObserver: NSObjectProtocol?
    private var isReconfiguring = false

    // MARK: - Public API

    /// Call this before start(). Async so it can request permission.
    public func requestPermission() async throws {
        #if os(macOS)
        let status = AVCaptureDevice.authorizationStatus(for: .audio)
        if status == .notDetermined {
            let granted = await AVCaptureDevice.requestAccess(for: .audio)
            if !granted { throw RecordingError.microphonePermissionDenied }
        } else if status == .denied || status == .restricted {
            throw RecordingError.microphonePermissionDenied
        }
        #else
        // iOS: AVAudioSession
        if #available(iOS 17.0, *) {
            let granted = await AVAudioApplication.requestRecordPermission()
            if !granted { throw RecordingError.microphonePermissionDenied }
        } else {
            let granted = await withCheckedContinuation { continuation in
                AVAudioSession.sharedInstance().requestRecordPermission { ok in
                    continuation.resume(returning: ok)
                }
            }
            if !granted { throw RecordingError.microphonePermissionDenied }
        }
        #endif
    }

    public func start() throws {
        guard !isRunning else { return }

        #if os(iOS)
        if managesAudioSession {
            let session = AVAudioSession.sharedInstance()
            // `.playAndRecord` + `.mixWithOthers` lets us record alongside a foreground app without
            // `.measurement`/`.duckOthers` fighting it (which fails AVAudioEngine.start with 'what').
            try session.setCategory(.playAndRecord, mode: .default,
                                    options: [.mixWithOthers, .allowBluetooth, .defaultToSpeaker])
            try session.setActive(true, options: .notifyOthersOnDeactivation)
        }
        #endif

        try armTapAndStart()

        // Re-arm automatically if the audio I/O config changes mid-recording (default device
        // change, sample-rate shift, headphones, another app seizing the device). macOS stops the
        // engine on this notification, which used to silently truncate the recording.
        configChangeObserver = NotificationCenter.default.addObserver(
            forName: .AVAudioEngineConfigurationChange, object: audioEngine, queue: nil
        ) { [weak self] _ in
            self?.handleConfigurationChange()
        }

        isRunning = true
        hasSpeechStarted = false
        silenceStartDate = nil
    }

    /// Install the tap at the current hardware format and start the engine. Shared by `start()`
    /// and the configuration-change recovery so both arm identically against the LIVE input
    /// format (which may have changed). Does NOT touch `isRunning`/VAD state — the callers own that.
    private func armTapAndStart() throws {
        let inputNode = audioEngine.inputNode
        // Defensive: clear any tap left over from a previous (possibly aborted) session.
        // Installing a second tap on the same bus raises an UNCATCHABLE ObjC exception that
        // aborts the whole process — so we must never let it happen.
        inputNode.removeTap(onBus: 0)

        let inputFormat = inputNode.outputFormat(forBus: 0)
        // Guard an invalid hardware format (no/unready input device, or a mid-session device
        // change — e.g. headphones plugged in). `installTap` aborts the process on a
        // 0-rate / 0-channel format, so turn that into a recoverable error instead of a crash.
        guard inputFormat.sampleRate > 0, inputFormat.channelCount > 0 else {
            audioEngine.reset()  // drop the bad state so the next attempt can re-read a good format
            throw RecordingError.audioFormatUnavailable
        }

        guard let conv = AVAudioConverter(from: inputFormat, to: targetFormat) else {
            throw RecordingError.audioFormatUnavailable
        }
        self.converter = conv
        let target = targetFormat

        // Tap at the native hardware format, convert on the fly to 16kHz. Clamp the buffer
        // size so a momentarily-zero sample rate can't produce an invalid (0) buffer size.
        let hardwareBufferSize = max(AVAudioFrameCount(inputFormat.sampleRate * 0.1), 1024)
        inputNode.installTap(onBus: 0, bufferSize: hardwareBufferSize, format: inputFormat) { [weak self] buffer, _ in
            self?.handleBuffer(buffer, converter: conv, targetFormat: target)
        }

        audioEngine.prepare()
        do {
            try audioEngine.start()
        } catch {
            inputNode.removeTap(onBus: 0)   // don't leave a tap behind on a failed start
            throw RecordingError.engineFailedToStart(error)
        }
    }

    /// The audio I/O config changed and macOS stopped the engine. Re-arm the tap against the new
    /// hardware format and restart so capture continues into the SAME buffer the caller is
    /// accumulating — the user keeps talking, we keep recording. Posted on an arbitrary thread,
    /// so we serialize onto main and guard against a re-entrant burst of notifications.
    private func handleConfigurationChange() {
        DispatchQueue.main.async { [weak self] in
            guard let self, self.isRunning, !self.isReconfiguring else { return }
            self.isReconfiguring = true
            defer { self.isReconfiguring = false }
            do {
                try self.armTapAndStart()
            } catch {
                // Couldn't recover (e.g. no usable input device right now). Tell the delegate so it can
                // end the recording and surface it, instead of leaving a live-looking-but-dead HUD with a
                // flatlined meter while the user keeps talking into nothing. Audio captured before the
                // change is already safe in the caller's buffer; stop() still tears down cleanly.
                self.delegate?.recordingEngineDidFailToRecover(self)
            }
        }
    }

    public func stop() {
        guard isRunning else { return }
        if let observer = configChangeObserver {
            NotificationCenter.default.removeObserver(observer)
            configChangeObserver = nil
        }
        audioEngine.inputNode.removeTap(onBus: 0)
        audioEngine.stop()
        isRunning = false

        #if os(iOS)
        if managesAudioSession {
            try? AVAudioSession.sharedInstance().setActive(false)
        }
        #endif
    }

    // MARK: - Private

    private func handleBuffer(_ inputBuffer: AVAudioPCMBuffer, converter: AVAudioConverter, targetFormat: AVAudioFormat) {
        // Convert to 16kHz
        let outputFrameCapacity = AVAudioFrameCount(
            Double(inputBuffer.frameLength) * Self.targetSampleRate / inputBuffer.format.sampleRate + 1
        )
        guard let convertedBuffer = AVAudioPCMBuffer(pcmFormat: targetFormat, frameCapacity: outputFrameCapacity) else { return }

        var error: NSError?
        var inputConsumed = false
        converter.convert(to: convertedBuffer, error: &error) { _, outStatus in
            if inputConsumed {
                outStatus.pointee = .noDataNow
                return nil
            }
            inputConsumed = true
            outStatus.pointee = .haveData
            return inputBuffer
        }

        guard error == nil, convertedBuffer.frameLength > 0 else { return }

        // VAD: compute RMS
        let rms = computeRMS(buffer: convertedBuffer)
        delegate?.recordingEngine(self, didUpdateLevel: rms)
        let now = Date()

        if rms >= silenceThreshold {
            hasSpeechStarted = true
            silenceStartDate = nil
        } else if hasSpeechStarted {
            // Below threshold after speech has started
            if let silenceStart = silenceStartDate {
                let elapsed = now.timeIntervalSince(silenceStart) * 1000 // ms
                if elapsed >= Double(silenceDurationMs) {
                    hasSpeechStarted = false
                    silenceStartDate = nil
                    delegate?.recordingEngineDidDetectSilence(self)
                }
            } else {
                silenceStartDate = now
            }
        }

        delegate?.recordingEngine(self, didReceiveBuffer: convertedBuffer)
    }

    private func computeRMS(buffer: AVAudioPCMBuffer) -> Float {
        guard let channelData = buffer.floatChannelData?[0] else { return 0 }
        let frameCount = Int(buffer.frameLength)
        guard frameCount > 0 else { return 0 }
        var sum: Float = 0
        for i in 0..<frameCount {
            let sample = channelData[i]
            sum += sample * sample
        }
        return sqrt(sum / Float(frameCount))
    }
}
