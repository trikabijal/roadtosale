# f5 — Capture & persistence

**Facades:** `RecordingEngine` (+ `RecordingEngineDelegate`); `CapturedAudioStream`; `RecordingStore`
(`FileRecordingStore`); `AudioSampleBridge`. Ordered capture is the fix for the long-recording-scramble
regression (F12).

## E2E facade ledger

| Facade | Behavior | Expected | Tier | Status |
|---|---|---|---|---|
| `CapturedAudioStream.append` | order preserved under concurrency | temporal order; all land | 1 | ✅ (5, incl. concurrent) |
| `CapturedAudioStream.drain(after:)` | tail since cursor | new tail + count; edge cases | 1 | ✅ |
| `CapturedAudioStream.reset` | empties, still usable | | 1 | ✅ |
| `RecordingStore.save`+`loadSamples` | round-trip PCM + rate | identical samples | 1 | ✅ |
| `RecordingStore` | retention cap | keeps newest 5, prunes rest | 1 | ✅ |
| `RecordingStore.loadSamples` | missing id | throws `.notFound` | 1 | ✅ |
| `AudioSampleBridge` | `[Float]` ⇄ buffer | round-trips | 1 | ✅ |
| `RecordingEngine.start/stop` | ordered 16 kHz mono buffers, level/VAD | total ≈ input, temporal order | 3 | ⛔ needs audio harness |
| `RecordingEngine` | mid-session device change | recoverable `RecordingError`, no abort | 3 | ⛔ |

## Unit inventory: ✅ `CapturedAudioStreamTests` (5), `RecordingStoreTests` (4). ⛔ `RecordingEngine` (needs a capture seam/harness).

## Deferred (Tier 3): `RecordingEngine` live capture ordering + no-loss + low-gain flag — needs an AVAudioEngine harness / injected tap.

## Checklist grade
- **concurrency** ✅ — concurrent-appends-all-land test guards the audio-thread append (the F12 class).
