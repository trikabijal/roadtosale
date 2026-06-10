import Foundation

// MARK: - Contract (platform-neutral)

/// Metadata for one persisted recording. Platform-neutral by design — `id` is an opaque
/// handle, audio is addressed as PCM float samples elsewhere.
public struct StoredRecording: Sendable, Identifiable, Equatable {
    public let id: String          // opaque handle (the store's filename on Apple platforms)
    public let recordedAt: Date
    public let durationMs: Int

    public init(id: String, recordedAt: Date, durationMs: Int) {
        self.id = id
        self.recordedAt = recordedAt
        self.durationMs = durationMs
    }
}

/// Persists the last N dictation recordings so a failed/garbled transcription can be
/// re-run without the user re-speaking. **This is a cross-platform contract**: audio is
/// expressed as mono PCM float samples + sample rate (Kotlin `FloatArray` / `double`,
/// TS `Float32Array` / `number`), never a platform audio type. macOS + iOS share the Apple
/// `FileRecordingStore` below; Android/other platforms implement this same shape against
/// their own file + WAV I/O. See `docs/model-contracts.md`.
public protocol RecordingStore: Sendable {
    /// Maximum recordings retained; older ones are pruned on `save`.
    var maxRecordings: Int { get }

    /// Persist a recording (mono PCM float samples at `sampleRate`). Prunes to `maxRecordings`.
    @discardableResult
    func save(samples: [Float], sampleRate: Double, recordedAt: Date) throws -> StoredRecording

    /// Stored recordings, newest first, at most `maxRecordings`.
    func recent() -> [StoredRecording]

    /// Load the PCM samples for a stored recording (for re-transcription).
    func loadSamples(id: String) throws -> (samples: [Float], sampleRate: Double)
}

public enum RecordingStoreError: Error {
    case notFound
    case writeFailed
    case readFailed
}

#if canImport(AVFoundation)
import AVFoundation

// MARK: - Apple implementation (macOS + iOS)

/// File-backed `RecordingStore` for Apple platforms. Writes each recording as a Float32 mono
/// WAV under `directory`, encoding the timestamp + duration in the filename so `recent()`
/// needs no file open. Thread-safe to call off the main actor (holds only immutable config).
public final class FileRecordingStore: RecordingStore {
    public let maxRecordings: Int
    private let directory: URL

    public init(directory: URL, maxRecordings: Int = 5) throws {
        self.directory = directory
        self.maxRecordings = maxRecordings
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    }

    // Filename scheme: rec-<epochMillis>-<durationMs>.wav  → self-describing, sortable.
    private static let prefix = "rec-"
    private static let ext = "wav"

    @discardableResult
    public func save(samples: [Float], sampleRate: Double, recordedAt: Date) throws -> StoredRecording {
        let millis = Int(recordedAt.timeIntervalSince1970 * 1000)
        let durationMs = sampleRate > 0 ? Int(Double(samples.count) / sampleRate * 1000) : 0
        let name = "\(Self.prefix)\(millis)-\(durationMs).\(Self.ext)"
        let url = directory.appendingPathComponent(name)

        guard let format = AVAudioFormat(commonFormat: .pcmFormatFloat32,
                                         sampleRate: sampleRate, channels: 1, interleaved: false),
              let buffer = AVAudioPCMBuffer(pcmFormat: format,
                                            frameCapacity: AVAudioFrameCount(max(samples.count, 1)))
        else { throw RecordingStoreError.writeFailed }

        buffer.frameLength = AVAudioFrameCount(samples.count)
        if !samples.isEmpty, let dst = buffer.floatChannelData?[0] {
            samples.withUnsafeBufferPointer { dst.update(from: $0.baseAddress!, count: samples.count) }
        }

        do {
            let file = try AVAudioFile(forWriting: url, settings: format.settings,
                                       commonFormat: .pcmFormatFloat32, interleaved: false)
            try file.write(from: buffer)
        } catch {
            throw RecordingStoreError.writeFailed
        }

        prune()
        return StoredRecording(id: name, recordedAt: recordedAt, durationMs: durationMs)
    }

    public func recent() -> [StoredRecording] {
        listSorted().prefix(maxRecordings).map(\.record)
    }

    public func loadSamples(id: String) throws -> (samples: [Float], sampleRate: Double) {
        let url = directory.appendingPathComponent(id)
        guard FileManager.default.fileExists(atPath: url.path) else {
            throw RecordingStoreError.notFound
        }
        do {
            let file = try AVAudioFile(forReading: url)
            let format = file.processingFormat
            let frames = AVAudioFrameCount(file.length)
            guard frames > 0,
                  let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: frames) else {
                throw RecordingStoreError.readFailed
            }
            try file.read(into: buffer)
            guard let ch = buffer.floatChannelData?[0] else { throw RecordingStoreError.readFailed }
            let samples = Array(UnsafeBufferPointer(start: ch, count: Int(buffer.frameLength)))
            return (samples, format.sampleRate)
        } catch {
            throw RecordingStoreError.readFailed
        }
    }

    // MARK: - Internals

    /// All stored recordings newest-first, paired with their URL for pruning.
    private func listSorted() -> [(record: StoredRecording, url: URL)] {
        let files = (try? FileManager.default.contentsOfDirectory(
            at: directory, includingPropertiesForKeys: nil)) ?? []
        return files
            .filter { $0.lastPathComponent.hasPrefix(Self.prefix) && $0.pathExtension == Self.ext }
            .compactMap { url -> (StoredRecording, URL)? in
                guard let rec = Self.parse(url.lastPathComponent) else { return nil }
                return (rec, url)
            }
            .sorted { $0.0.recordedAt > $1.0.recordedAt }
    }

    /// Delete everything beyond the newest `maxRecordings`.
    private func prune() {
        for stale in listSorted().dropFirst(maxRecordings) {
            try? FileManager.default.removeItem(at: stale.url)
        }
    }

    /// Parse "rec-<millis>-<durationMs>.wav" → StoredRecording.
    private static func parse(_ filename: String) -> StoredRecording? {
        let stem = filename.replacingOccurrences(of: ".\(ext)", with: "")
            .replacingOccurrences(of: prefix, with: "")
        let parts = stem.split(separator: "-")
        guard parts.count == 2, let millis = Int(parts[0]), let dur = Int(parts[1]) else { return nil }
        return StoredRecording(id: filename,
                               recordedAt: Date(timeIntervalSince1970: Double(millis) / 1000),
                               durationMs: dur)
    }

    // MARK: - Default locations (mirror TelemetryStore's convention)

    public static func macOS(maxRecordings: Int = 5) throws -> FileRecordingStore {
        let base = try FileManager.default.url(for: .applicationSupportDirectory,
                                               in: .userDomainMask, appropriateFor: nil, create: true)
        let dir = base.appendingPathComponent("com.trika.dictation/recordings", isDirectory: true)
        return try FileRecordingStore(directory: dir, maxRecordings: maxRecordings)
    }
}

// MARK: - Sample/buffer bridging (Apple-only; the contract above stays neutral)

/// Converts between the contract's neutral `[Float]` samples and Apple's `AVAudioPCMBuffer`,
/// so the same `RecordingStore` audio can flow into the `SpeechTranscriber` (which speaks
/// `AVAudioPCMBuffer`) for re-transcription.
public enum AudioSampleBridge {

    /// Flatten recorded buffers into mono PCM float samples.
    public static func flatten(_ buffers: [AVAudioPCMBuffer]) -> [Float] {
        buffers.flatMap { buffer -> [Float] in
            guard let ch = buffer.floatChannelData?[0] else { return [] }
            return Array(UnsafeBufferPointer(start: ch, count: Int(buffer.frameLength)))
        }
    }

    /// Wrap samples back into a single mono `AVAudioPCMBuffer` at `sampleRate`.
    public static func makeBuffer(samples: [Float], sampleRate: Double) -> AVAudioPCMBuffer? {
        guard let format = AVAudioFormat(commonFormat: .pcmFormatFloat32,
                                         sampleRate: sampleRate, channels: 1, interleaved: false),
              let buffer = AVAudioPCMBuffer(pcmFormat: format,
                                            frameCapacity: AVAudioFrameCount(max(samples.count, 1)))
        else { return nil }
        buffer.frameLength = AVAudioFrameCount(samples.count)
        if !samples.isEmpty, let dst = buffer.floatChannelData?[0] {
            samples.withUnsafeBufferPointer { dst.update(from: $0.baseAddress!, count: samples.count) }
        }
        return buffer
    }
}
#endif
