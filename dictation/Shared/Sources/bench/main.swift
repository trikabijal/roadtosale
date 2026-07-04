// Latency benchmark: batch vs chunked STT + cleanup across clip lengths.
// Dev-only harness (not shipped). Usage: swift run bench <clips-dir> [outfile.md]
//
// Each clip is `say`-generated numbered sentences ("Sentence number N ... codeword is X ...")
// so we can measure not just latency but CONTENT LOSS — which numbered sentences a pass drops.
// That doubles as the diagnosis for the "middle got skipped" bug on long clips.

import AVFoundation
import DictationCore
import Foundation
#if canImport(FoundationModels)
import FoundationModels
#endif

// MARK: - Audio helpers

func readSamples(_ url: URL) throws -> [Float] {
    let f = try AVAudioFile(forReading: url)
    let fmt = f.processingFormat
    guard let buf = AVAudioPCMBuffer(pcmFormat: fmt, frameCapacity: AVAudioFrameCount(f.length)) else { return [] }
    try f.read(into: buf)
    guard let ch = buf.floatChannelData?[0] else { return [] }
    return Array(UnsafeBufferPointer(start: ch, count: Int(buf.frameLength)))
}

func makeBuffer(_ samples: ArraySlice<Float>) -> AVAudioPCMBuffer {
    let fmt = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: 16000, channels: 1, interleaved: false)!
    let arr = Array(samples)
    let buf = AVAudioPCMBuffer(pcmFormat: fmt, frameCapacity: AVAudioFrameCount(arr.count))!
    buf.frameLength = AVAudioFrameCount(arr.count)
    arr.withUnsafeBufferPointer { buf.floatChannelData![0].update(from: $0.baseAddress!, count: arr.count) }
    return buf
}

func now() -> Date { Date() }
func msSince(_ d: Date) -> Int { Int(now().timeIntervalSince(d) * 1000) }

// MARK: - Content-loss detection

/// Sentence numbers present in a transcript ("sentence number 7" -> 7). Case-insensitive; also
/// tolerates STT writing digits as words is out of scope — `say` says "number seven" but Whisper
/// usually emits digits. We match both "number N" and bare codewords to be robust.
let codewords = ["alpha","bravo","charlie","delta","echo","foxtrot","golf","hotel","india","juliet",
                 "kilo","lima","mike","november","oscar","papa","quebec","romeo","sierra","tango"]

func codewordsPresent(_ text: String) -> Int {
    let lower = text.lowercased()
    return codewords.filter { lower.contains($0) }.count
}

/// Rough count of distinct sentence markers seen, via the numeric "number <n>" pattern.
func sentenceNumbers(_ text: String) -> Set<Int> {
    var out = Set<Int>()
    let lower = text.lowercased()
    let re = try! NSRegularExpression(pattern: #"number\s+(\d{1,3})"#)
    for m in re.matches(in: lower, range: NSRange(lower.startIndex..., in: lower)) {
        if let r = Range(m.range(at: 1), in: lower), let n = Int(lower[r]) { out.insert(n) }
    }
    return out
}

// MARK: - Cleanup timing (reuse the real engines)

let pack = CleanupPackLoader.load()
let ruleEngine = RuleBasedCleanup(pack: pack)

func makeCleanupEngine() -> any TextCleanup {
    #if canImport(FoundationModels)
    if #available(macOS 26.0, *) {
        return FoundationModelsCleanup(pack: pack, fallback: ruleEngine)
    }
    #endif
    return ruleEngine
}

func cleanupRequest(_ text: String) -> CleanupRequest {
    CleanupRequest(rawText: text, level: .full, vocab: [:], commandGrammar: [:], profile: "dictation")
}

@MainActor
func timeBatchCleanupFM(_ text: String) async -> (ms: Int, chars: Int, fallback: Bool)? {
    #if canImport(FoundationModels)
    if #available(macOS 26.0, *) {
        let fm = FoundationModelsCleanup(pack: pack, fallback: ruleEngine)
        let t = now()
        let r = await fm.clean(cleanupRequest(text))
        return (msSince(t), r.cleanedText.count, r.usedFallback)
    }
    #endif
    return nil
}

@MainActor
func timeBatchCleanupRule(_ text: String) async -> (ms: Int, chars: Int) {
    let t = now()
    let r = await ruleEngine.clean(cleanupRequest(text))
    return (msSince(t), r.cleanedText.count)
}

/// Incremental cleanup: split into sentences, clean each on ONE warm session with the previous
/// cleaned sentence as read-only context. Returns per-sentence ms so we can report the
/// "post-stop" cost = only the LAST sentence (everything else cleaned during speech).
@MainActor
func timeChunkedCleanupFM(_ text: String) async -> (perSentenceMs: [Int], lastMs: Int, totalMs: Int)? {
    #if canImport(FoundationModels)
    if #available(macOS 26.0, *) {
        guard SystemLanguageModel.default.isAvailable else { return nil }
        let sentences = text
            .split(whereSeparator: { ".!?".contains($0) })
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty }
        guard !sentences.isEmpty else { return nil }
        let instr = pack.prompts[CleanupLevel.full.rawValue] ?? "Fix punctuation, capitalization, and filler words only. Do not rephrase or summarize."
        let session = LanguageModelSession(instructions: instr)
        session.prewarm()
        var perMs: [Int] = []
        var prevClean = ""
        for s in sentences {
            let prompt = """
            Clean ONLY this one sentence (punctuation, capitalization, fillers). Do not rephrase or drop content. Return only the cleaned sentence.
            Previous sentence for context (do not repeat it): \(prevClean)
            Sentence: \(s)
            """
            let t = now()
            let out = (try? await session.respond(to: prompt).content) ?? s
            perMs.append(msSince(t))
            prevClean = out
        }
        return (perMs, perMs.last ?? 0, perMs.reduce(0, +))
    }
    #endif
    return nil
}

// MARK: - Main

@main
struct Bench {
    @MainActor
    static func main() async {
        let args = CommandLine.arguments
        guard args.count >= 2 else { print("usage: bench <clips-dir> [outfile.md]"); return }
        let dir = URL(fileURLWithPath: args[1])
        let outfile = args.count >= 3 ? args[2] : nil

        // clip name -> (file, expected sentence count)
        let clips: [(String, String, Int)] = [
            ("10s",  "clip_10s.wav",  2),
            ("20s",  "clip_20s.wav",  4),
            ("30s",  "clip_30s.wav",  6),
            ("60s",  "clip_60s.wav",  12),
            ("180s", "clip_180s.wav", 36),
        ]

        var report = "# Dictation latency + content-loss benchmark\n\n"
        report += "small.en STT · Apple Foundation Models cleanup · on-device\n\n"

        let stt = WhisperKitTranscriber(modelTier: .smallEn)
        do {
            print("loading small.en …")
            try await stt.load()
            print("model loaded.\n")
        } catch {
            print("MODEL LOAD FAILED: \(error)"); return
        }

        // chunk size for streaming-STT simulation (WhisperKit uses ~15s blocks).
        let chunkSeconds = 15.0
        let sr = 16000.0

        report += "## Per-clip results\n\n"
        report += "| clip | audio s | BATCH STT ms | BATCH cov | CHUNK STT total ms | CHUNK last-seg ms | CHUNK cov | FM clean ms | FM last-sent ms | rule clean ms |\n"
        report += "|---|---|---|---|---|---|---|---|---|---|\n"

        for (name, file, expSent) in clips {
            let url = dir.appendingPathComponent(file)
            guard let samples = try? readSamples(url), !samples.isEmpty else {
                print("skip \(name): can't read \(file)"); continue
            }
            let audioS = Double(samples.count) / sr
            print("=== \(name)  (\(String(format: "%.1f", audioS))s, expect \(expSent) sentences) ===")

            // BATCH STT
            let bt = now()
            let batch = try? await stt.transcribe(buffers: [makeBuffer(samples[...])], audioStartDate: now())
            let batchMs = msSince(bt)
            let batchText = batch?.text ?? ""
            let batchCovNums = sentenceNumbers(batchText)
            let batchCov = codewordsPresent(batchText)
            print("  BATCH  stt=\(batchMs)ms  words=\(batchText.split(separator: " ").count)  codewords=\(batchCov)  sentNums=\(batchCovNums.sorted())")

            // CHUNKED STT (fixed 15s windows — streaming-STT proxy). Report total + last-segment.
            let chunkLen = Int(chunkSeconds * sr)
            var segMs: [Int] = []
            var chunkTextParts: [String] = []
            var i = 0
            while i < samples.count {
                let end = min(i + chunkLen, samples.count)
                let ct = now()
                let seg = try? await stt.transcribe(buffers: [makeBuffer(samples[i..<end])], audioStartDate: now())
                segMs.append(msSince(ct))
                chunkTextParts.append(seg?.text ?? "")
                i = end
            }
            let chunkText = chunkTextParts.joined(separator: " ")
            // Dump full texts as hard evidence of any middle-drop.
            if let outfile {
                let base = (outfile as NSString).deletingLastPathComponent
                try? batchText.write(toFile: "\(base)/text_\(name)_batch.txt", atomically: true, encoding: .utf8)
                try? chunkText.write(toFile: "\(base)/text_\(name)_chunk.txt", atomically: true, encoding: .utf8)
            }
            let chunkCov = codewordsPresent(chunkText)
            let chunkCovNums = sentenceNumbers(chunkText)
            let chunkTotal = segMs.reduce(0, +)
            let chunkLast = segMs.last ?? 0
            print("  CHUNK  segs=\(segMs.count) total=\(chunkTotal)ms lastSeg=\(chunkLast)ms  codewords=\(chunkCov) sentNums=\(chunkCovNums.sorted())")

            // Cleanup on the batch text (the real-world input to cleanup).
            let fm = await timeBatchCleanupFM(batchText)
            let rule = await timeBatchCleanupRule(batchText)
            let chunkedClean = await timeChunkedCleanupFM(batchText)

            // REAL committed pipeline: drive StreamingDictationSession end-to-end (real WhisperKit
            // per-segment + ONE real FM cleanup pass at stop, no rolling context). Feed all-but-last
            // segment as "during speech" (untimed), then measure the last segment + finish() as the
            // post-stop wait, and check the assembled text is complete.
            let cleanupEngine: any TextCleanup = makeCleanupEngine()
            let session = StreamingDictationSession(transcriber: stt, cleanup: cleanupEngine, level: .full)
            session.prewarm()
            let segLen = Int(8.0 * sr)   // ~8s pseudo-VAD segments
            var segStarts: [Int] = []
            var p = 0; while p < samples.count { segStarts.append(p); p += segLen }
            for s in segStarts.dropLast() {
                let e = min(s + segLen, samples.count)
                _ = await session.ingest(segment: [makeBuffer(samples[s..<e])], audioStartDate: now())
            }
            let psStart = now()
            if let lastStart = segStarts.last {
                let e = min(lastStart + segLen, samples.count)
                _ = await session.ingest(segment: [makeBuffer(samples[lastStart..<e])], audioStartDate: now())
            }
            let streamFinal = await session.finish()
            let streamPostStopMs = msSince(psStart)
            let streamCov = codewordsPresent(streamFinal.cleanedText)
            let streamWords = streamFinal.cleanedText.split(separator: " ").count
            if let outfile {
                let base = (outfile as NSString).deletingLastPathComponent
                try? streamFinal.cleanedText.write(toFile: "\(base)/text_\(name)_stream.txt", atomically: true, encoding: .utf8)
            }
            print("  STREAM realSession postStop=\(streamPostStopMs)ms words=\(streamWords) codewords=\(streamCov)")

            let fmMs = fm?.ms ?? -1
            let fmLast = chunkedClean?.lastMs ?? -1
            let ruleMs = rule.ms
            print("  CLEAN  fmBatch=\(fmMs)ms  fmChunkedLastSent=\(fmLast)ms  rule=\(ruleMs)ms  fmFallback=\(fm?.fallback ?? false)")
            if let cc = chunkedClean { print("         fm per-sentence ms=\(cc.perSentenceMs)") }
            print("")

            report += "| \(name) | \(String(format: "%.1f", audioS)) | \(batchMs) | \(batchCov)/\(expSent==2 ? 2 : min(expSent,20)) | \(chunkTotal) | \(chunkLast) | \(chunkCov)/\(min(expSent,20)) | \(fmMs) | \(fmLast) | \(ruleMs) |\n"
        }

        report += "\nNotes:\n"
        report += "- **cov** = distinct codewords found (max 20, since codewords cycle). Lower than expected = dropped content.\n"
        report += "- **CHUNK STT total** = sum of all 15s segments (work done DURING speech). **last-seg** = the only wait left post-stop with streaming.\n"
        report += "- **FM last-sent ms** = incremental cleanup's post-stop cost (only the final sentence; the rest cleaned during speech).\n"

        if let outfile {
            try? report.write(toFile: outfile, atomically: true, encoding: .utf8)
            print("wrote report -> \(outfile)")
        }
        print("\nDONE")
    }
}
