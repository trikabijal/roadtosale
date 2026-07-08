import AVFoundation
import DictationCoreBase
import SwiftUI
import os

private let log = Logger(subsystem: "com.trika.dictation", category: "RecordSession")

/// Thread-safe audio accumulator (mirrors the keyboard's).
private final class AudioBox: @unchecked Sendable {
    private let lock = NSLock()
    private var buffers: [AVAudioPCMBuffer] = []
    func append(_ b: AVAudioPCMBuffer) { lock.lock(); buffers.append(b); lock.unlock() }
    func snapshot() -> [AVAudioPCMBuffer] { lock.lock(); defer { lock.unlock() }; return buffers }
    func reset() { lock.lock(); buffers.removeAll(); lock.unlock() }
}

/// The "Flow Session": the container app records + transcribes (the keyboard can't touch the mic),
/// writes the text to the App Group, and the keyboard reads it back. Auto-starts on appear.
@MainActor
final class RecordSessionModel: ObservableObject {
    enum Phase: Equatable { case starting, recording, transcribing, done, failed(String) }
    @Published var phase: Phase = .starting
    @Published var level: Float = 0
    @Published var transcript: String = ""

    /// Hard safety stop: a missed/lost stop signal can't leave the mic on (draining the battery)
    /// forever. Mirrors the keyboard's cap.
    private static let maxRecordingSeconds: Double = 300

    private let engine = RecordingEngine()
    private let transcriber: any SpeechTranscriber
    private let cleanup: any TextCleanup
    private let cleanupPack: CleanupPack
    private let audio = AudioBox()
    private var startDate: Date?
    private var safetyStop: Task<Void, Never>?
    private var stopRequested = false   // stop signal that arrived before recording began

    init() {
        let pack = CleanupPackLoader.load()
        cleanupPack = pack
        cleanup = TextCleanupFactory.make(CleanupConfig(provider: .ruleBased, level: .light), pack: pack)
        // Apple Speech: SpeechAnalyzer on 26 (fast), SFSpeechRecognizer otherwise. No factory in the
        // base package, so pick directly.
        if #available(iOS 26.0, *) {
            transcriber = AppleAnalyzerTranscriber(localeIdentifier: "en-US")
        } else {
            transcriber = AppleSpeechTranscriber(language: "en-US")
        }
        engine.delegate = self
    }

    func begin() async {
        guard phase == .starting else { return }
        // Observe the stop signal BEFORE the awaits below: Darwin notifications aren't queued, so if
        // the user double-taps (start then stop) faster than startup completes, an observer registered
        // only after start() would miss the stop and record forever.
        registerStopObserver()
        do { try await transcriber.load() } catch {
            log.error("load: \(error.localizedDescription, privacy: .public)")
        }
        do {
            try await engine.requestPermission()
            audio.reset()
            startDate = Date()
            try engine.start()
        } catch {
            phase = .failed(error.localizedDescription)
            return
        }
        phase = .recording
        safetyStop = Task { [weak self] in
            try? await Task.sleep(for: .seconds(Self.maxRecordingSeconds))
            guard !Task.isCancelled else { return }
            self?.stop()
        }
        // A stop that landed during startup (before we were recording) is honored now.
        if stopRequested { stop() }
        // No auto-suspend: iOS drops a self-suspended app on the home screen, not the caller. Like
        // Wispr, the user swipes back to their app; recording keeps running in the background
        // (UIBackgroundModes: audio) until the keyboard signals stop.
    }

    /// Keyboard → app: stop signal (Darwin). Stops the background recording and transcribes.
    private func registerStopObserver() {
        DictationHandoff.observe(DictationHandoff.stopNotification,
                                 observer: Unmanaged.passUnretained(self).toOpaque()) { _, observer, _, _, _ in
            guard let observer else { return }
            let m = Unmanaged<RecordSessionModel>.fromOpaque(observer).takeUnretainedValue()
            Task { @MainActor in m.stop() }
        }
    }

    deinit {
        DictationHandoff.removeObserver(Unmanaged.passUnretained(self).toOpaque())
        safetyStop?.cancel()
    }

    func stop() {
        switch phase {
        case .starting:  stopRequested = true; return   // recording not started yet; honor once it is
        case .recording: break
        default:         return                          // already stopping/done/failed
        }
        safetyStop?.cancel()
        engine.stop()
        phase = .transcribing
        let buffers = audio.snapshot()
        let sd = startDate ?? Date()
        Task { await finish(buffers, sd) }
    }

    private func finish(_ buffers: [AVAudioPCMBuffer], _ sd: Date) async {
        do {
            let r = try await transcriber.transcribe(buffers: buffers, audioStartDate: sd)
            var text = r.text
            if !text.isEmpty,
               text.split(whereSeparator: \.isWhitespace).count >= cleanupPack.minWordsForCleanup {
                text = (await cleanup.clean(CleanupRequest(rawText: text, level: .light))).cleanedText
            }
            guard !text.isEmpty else { phase = .failed("Didn't catch that"); return }
            transcript = text
            DictationHandoff.write(text)                       // keyboard reads this
            DictationHandoff.post(DictationHandoff.doneNotification)   // ...on this signal
            phase = .done
        } catch {
            phase = .failed("Didn't catch that")
        }
    }
}

extension RecordSessionModel: RecordingEngineDelegate {
    nonisolated func recordingEngine(_ e: RecordingEngine, didReceiveBuffer b: AVAudioPCMBuffer) {
        // Append synchronously on the audio thread (AudioBox is lock-guarded). Hopping to MainActor
        // here would drop buffers still in flight when stop() snapshots — losing the tail of speech.
        audio.append(b)
    }
    nonisolated func recordingEngineDidDetectSilence(_ e: RecordingEngine) {}
    nonisolated func recordingEngine(_ e: RecordingEngine, didUpdateLevel l: Float) {
        Task { @MainActor in self.level = l }
    }
}

// MARK: - View

struct RecordSessionView: View {
    @StateObject private var model = RecordSessionModel()
    let onClose: () -> Void

    var body: some View {
        VStack(spacing: 28) {
            Spacer()
            switch model.phase {
            case .starting:
                ProgressView().controlSize(.large)
                Text("Getting ready…").foregroundStyle(.secondary)
            case .recording:
                MicPulse(level: model.level)
                Text("Recording…").font(.title).bold()
                Text("Swipe back to your app and keep talking.\nTap the keyboard mic to finish.")
                    .multilineTextAlignment(.center).foregroundStyle(.secondary).padding(.horizontal)
            case .transcribing:
                ProgressView().controlSize(.large)
                Text("Transcribing…").foregroundStyle(.secondary)
            case .done:
                Image(systemName: "checkmark.circle.fill").font(.system(size: 56)).foregroundStyle(.green)
                Text(model.transcript).font(.title3).multilineTextAlignment(.center).padding(.horizontal)
                Text("Go back to your app — it's ready to paste.").font(.callout).foregroundStyle(.secondary)
            case .failed(let msg):
                Image(systemName: "exclamationmark.triangle.fill").font(.system(size: 48)).foregroundStyle(.orange)
                Text(msg).multilineTextAlignment(.center).foregroundStyle(.secondary).padding(.horizontal)
            }
            Spacer()
            Button("Close", action: onClose).padding(.bottom)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .task { await model.begin() }
        .onChange(of: model.phase) { _, p in
            // Wispr-style "hops you back": once the transcript is in the App Group, background this
            // app so iOS returns to the app you were in; the keyboard reads + inserts on reappear.
            if p == .done {
                Task { try? await Task.sleep(for: .milliseconds(300)); onClose() }
            }
        }
    }
}

private struct MicPulse: View {
    let level: Float
    var body: some View {
        ZStack {
            Circle().fill(Color.red.opacity(0.15))
                .frame(width: 140 + CGFloat(min(1, level * 6)) * 60, height: 140 + CGFloat(min(1, level * 6)) * 60)
                .animation(.easeOut(duration: 0.1), value: level)
            Image(systemName: "mic.fill").font(.system(size: 44)).foregroundStyle(.red)
        }.frame(height: 220)
    }
}
