import Foundation
import Speech
import AVFoundation

@objc(RtsVoiceModule)
class RtsVoiceModule: RCTEventEmitter {

  private var audioEngine = AVAudioEngine()
  private var recognitionRequest: SFSpeechAudioBufferRecognitionRequest?
  private var recognitionTask: SFSpeechRecognitionTask?
  private var speechRecognizer: SFSpeechRecognizer?
  private var isMuted = false
  private var sessionStartMs: Int64 = 0

  override static func requiresMainQueueSetup() -> Bool { return false }

  override func supportedEvents() -> [String]! {
    return ["onTranscriptEvent", "onVoiceStateChange", "onVoiceError"]
  }

  // MARK: - Permissions

  @objc func requestPermissions(_ resolve: @escaping RCTPromiseResolveBlock,
                                 rejecter reject: @escaping RCTPromiseRejectBlock) {
    SFSpeechRecognizer.requestAuthorization { authStatus in
      AVAudioSession.sharedInstance().requestRecordPermission { granted in
        DispatchQueue.main.async {
          switch authStatus {
          case .authorized where granted:
            resolve("granted")
          case .denied, .restricted:
            resolve("denied")
          default:
            resolve("undetermined")
          }
        }
      }
    }
  }

  // MARK: - Start/Stop

  @objc func startListening(_ language: String,
                              customVocabulary: [String],
                              resolver resolve: @escaping RCTPromiseResolveBlock,
                              rejecter reject: @escaping RCTPromiseRejectBlock) {
    let locale = Locale(identifier: language)
    speechRecognizer = SFSpeechRecognizer(locale: locale)
    guard let recognizer = speechRecognizer, recognizer.isAvailable else {
      reject("unavailable", "Speech recognizer not available for locale \(language)", nil)
      return
    }

    do {
      try startAudioSession()
      recognitionRequest = SFSpeechAudioBufferRecognitionRequest()
      guard let request = recognitionRequest else { return }

      request.shouldReportPartialResults = true
      request.requiresOnDeviceRecognition = true  // iOS 17+ on-device only

      // Custom vocabulary hint
      if !customVocabulary.isEmpty {
        request.contextualStrings = customVocabulary
      }

      sessionStartMs = Int64(Date().timeIntervalSince1970 * 1000)

      recognitionTask = recognizer.recognitionTask(with: request) { [weak self] result, error in
        guard let self = self else { return }

        if let error = error {
          self.sendEvent(withName: "onVoiceError", body: error.localizedDescription)
          return
        }

        guard let result = result else { return }

        let nowMs = Int64(Date().timeIntervalSince1970 * 1000)
        let latency = nowMs - self.sessionStartMs
        let stability: String = result.isFinal ? "final" : "partial"
        let text = result.bestTranscription.formattedString
        let confidence: Double? = result.isFinal ?
          Double(result.bestTranscription.segments.last?.confidence ?? 0) : nil

        let event: [String: Any] = [
          "text": text,
          "stability": stability,
          "timestamp_ms": nowMs,
          "latency_ms_from_audio_start": latency,
          "confidence": confidence as Any,
          "engine_metadata": ["engine": "apple_speech_transcriber", "is_final": result.isFinal]
        ]
        self.sendEvent(withName: "onTranscriptEvent", body: event)
      }

      let inputNode = audioEngine.inputNode
      let format = inputNode.outputFormat(forBus: 0)
      inputNode.installTap(onBus: 0, bufferSize: 1024, format: format) { [weak self] buffer, _ in
        guard let self = self, !self.isMuted else { return }
        self.recognitionRequest?.append(buffer)
      }

      audioEngine.prepare()
      try audioEngine.start()

      sendEvent(withName: "onVoiceStateChange", body: "listening")
      resolve(nil)
    } catch {
      reject("start_error", error.localizedDescription, error)
    }
  }

  @objc func stopListening(_ resolve: @escaping RCTPromiseResolveBlock,
                            rejecter reject: @escaping RCTPromiseRejectBlock) {
    audioEngine.stop()
    audioEngine.inputNode.removeTap(onBus: 0)
    recognitionRequest?.endAudio()
    recognitionTask?.cancel()
    recognitionRequest = nil
    recognitionTask = nil
    sendEvent(withName: "onVoiceStateChange", body: "stopped")
    resolve(nil)
  }

  @objc func mute() {
    isMuted = true
    sendEvent(withName: "onVoiceStateChange", body: "muted")
  }

  @objc func unmute() {
    isMuted = false
    sendEvent(withName: "onVoiceStateChange", body: "listening")
  }

  // MARK: - Private

  private func startAudioSession() throws {
    let session = AVAudioSession.sharedInstance()
    try session.setCategory(.playAndRecord,
                            mode: .measurement,
                            options: [.defaultToSpeaker, .allowBluetooth, .mixWithOthers])
    try session.setActive(true, options: .notifyOthersOnDeactivation)
  }
}
