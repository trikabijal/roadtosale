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

    private let engine = RecordingEngine()
    private let transcriber: any SpeechTranscriber
    private let cleanup: any TextCleanup
    private let cleanupPack: CleanupPack
    private let audio = AudioBox()
    private var startDate: Date?

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
        do { try await transcriber.load() } catch {
            log.error("load: \(error.localizedDescription, privacy: .public)")
        }
        do {
            try await engine.requestPermission()
            audio.reset()
            startDate = Date()
            try engine.start()
            phase = .recording
        } catch {
            phase = .failed(error.localizedDescription)
        }
    }

    func stop() {
        guard phase == .recording else { return }
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
            DictationHandoff.write(text)   // keyboard picks this up
            phase = .done
        } catch {
            phase = .failed("Didn't catch that")
        }
    }
}

extension RecordSessionModel: RecordingEngineDelegate {
    nonisolated func recordingEngine(_ e: RecordingEngine, didReceiveBuffer b: AVAudioPCMBuffer) {
        // captured on the audio thread
        Task { @MainActor in self.audio.append(b) }
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
                Text("Listening… tap to finish").font(.title3).foregroundStyle(.secondary)
                Button(action: model.stop) {
                    Image(systemName: "stop.circle.fill").font(.system(size: 64)).foregroundStyle(.red)
                }.buttonStyle(.plain)
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
                Task {
                    try? await Task.sleep(for: .milliseconds(500))
                    onClose()
                    try? await Task.sleep(for: .milliseconds(150))
                    // Private "suspend" — backgrounds the app to return to the previous one.
                    UIApplication.shared.perform(NSSelectorFromString("suspend"))
                }
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
