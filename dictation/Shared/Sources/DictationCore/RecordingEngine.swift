import AVFoundation

// MARK: - Delegate protocol

public protocol RecordingEngineDelegate: AnyObject {
    /// Called continuously as audio arrives (on an arbitrary audio queue).
    func recordingEngine(_ engine: RecordingEngine, didReceiveBuffer buffer: AVAudioPCMBuffer)
    /// Called when silence is detected for longer than silenceDurationMs after speech has been heard.
    func recordingEngineDidDetectSilence(_ engine: RecordingEngine)
    /// Per-buffer RMS level (0…~1), for UI meters. Optional.
    func recordingEngine(_ engine: RecordingEngine, didUpdateLevel level: Float)
}

public extension RecordingEngineDelegate {
    func recordingEngine(_ engine: RecordingEngine, didUpdateLevel level: Float) {}
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
    public var silenceThreshold: Float = 0.01
    /// How long (ms) continuous silence triggers the delegate callback.
    public var silenceDurationMs: Int = 800

    public weak var delegate: RecordingEngineDelegate?

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
        let session = AVAudioSession.sharedInstance()
        try session.setCategory(.record, mode: .measurement, options: .duckOthers)
        try session.setActive(true, options: .notifyOthersOnDeactivation)
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
                // Couldn't recover (e.g. no usable input device right now). Leave isRunning true:
                // a later config change (device back) can re-arm, and stop() still tears down cleanly.
                // The audio captured before the change is already safe in the caller's buffer.
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
        try? AVAudioSession.sharedInstance().setActive(false)
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
