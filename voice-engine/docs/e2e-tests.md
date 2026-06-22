# Voice Engine — Test Plan (primary spec)

Last updated: `2026-06-22`

**Scope.** The `voice-engine` module — the repo's **contract / data / research core** for the
speech pipeline. It is *not* a linked library; production apps re-implement its contracts
natively. The testable surface is therefore the **runnable parts and the contracts**, not the
native engines themselves:

1. the Python lab facade `VoiceEngineLab` and its strategy registry, orchestrator, matcher,
   scoring, ingestion, and reporting (the live, working core),
2. the TypeScript reference facade `VoiceEngine` + the `CleanupStrategy` contract + the cue matcher,
3. the cross-language **drift/parity** between (1) and (2),
4. the canonical **data** (`cleanup-packs/*.json`) and the **derive_vocab** producer that writes into it.

See the module docs: [`architecture.md`](./architecture.md), [`api.md`](./api.md),
[`model-contracts.md`](./model-contracts.md), and the whole-system map at
[`../../docs/architecture.md`](../../docs/architecture.md).

> **This is the PLAN, not the test code.** It defines *what should be tested* based on the
> module's public facades and contracts, independent of which tests exist today. The
> coverage ledger (§7) and pending backlog (§8) compute the delta against the real test files.

---

## 1. Black-box discipline (non-negotiable)

Every scenario drives a **public facade or contract** and asserts an **observable** result
(a returned `TranscriptEvent` stream, a `CueDetection`, a `CleanupResult`, a written script
YAML, a derived vocab file). The rules that make this a research/contract module testable
*hermetically*:

- **Native binaries are stubbed.** Every non-`mock` STT strategy spawns a Swift/Kotlin/JVM
  subprocess. Tests replace that binary with a **tiny POSIX shell-script fake** that emits a
  known JSONL transcript on stdout (the pattern already used for Apple/WhisperKit). This keeps
  tests hermetic — no model download, no Speech-framework auth prompt, no swift/java toolchain,
  no microphone. The wrapper-level contract (argv built, JSONL parsed, errors surfaced) is what
  we assert; the engine's *accuracy* is not.
- **Network is stubbed.** YouTube ingestion injects a fake `youtube-transcript-api` /
  `transcript_fetcher`; no test touches the network.
- **No vendor SDK is imported** from Python or TS — they all live behind the process boundary.
- **Real-model accuracy is out of automated scope** — it is an inherently manual / benchmark
  tier (§6). Unit-automatable tests assert *contract conformance*, not WER.

---

## 2. Architecture as test surface

```
                    voice-engine (contract / data / research core)
  ┌─────────────────────────────── Python lab (runnable core) ───────────────────────────────┐
  │  VoiceEngineLab.load() ──► registry ──► 5 strategies (mock + 4 subprocess) + dormant argmax │
  │        │                                     │ JSONL on stdout (stubbed binary)            │
  │        ├── transcribe_file() ──► TranscriptEvent stream                                     │
  │        ├── match_cues() ──► CueMatcher / CombinedMatcher ──► CueDetection                   │
  │        └── Orchestrator (script × strategy) ──► scoring (classify, latency) ──► reporting   │
  │     ingestion: youtube fetch ──► script_builder ──► LabScript YAML                          │
  └────────────────────────────────────────────────────────────────────────────────────────────┘
  ┌─────────────────── TS reference contract (only `mock` runs) ───────────────────┐
  │  VoiceEngine.load() ──► registry (mock) ──► startSession() ──► TranscriptEvent  │
  │  CueMatcher (parity with Python)   CleanupStrategy ──► RuleBasedCleanupStrategy │
  └────────────────────────────────────────────────────────────────────────────────┘
  ┌──────────────────────── Data + producers ────────────────────────┐
  │  cleanup-packs/{dictation,road-to-sale}.json  (canonical data)    │
  │  vehicle-feature-catalog/scripts/derive_vocab.py ──► derived/*.json │
  └────────────────────────────────────────────────────────────────────┘
```

### Facade Coverage Ledger — every public boundary needs ≥1 Tier-1/Tier-2 test

| Facade / boundary | Public surface under test | Plan IDs |
|---|---|---|
| Python `VoiceEngineLab` | `load`, `list_strategies`, `get_strategy`, `transcribe_file`, `match_cues` | P-FAC-1..6 |
| STT strategy contract — `mock` | replays JSONL → `TranscriptEvent`s | P-MOCK-1..2 |
| STT strategy contract — `apple_speech_transcriber` | argv, JSONL parse, partials, errors | P-APL-1..N |
| STT strategy contract — `apple_sfspeechrecognizer_vocab` | vocab argv, vocab-missing error, confidence | P-SFR-1..N |
| STT strategy contract — `whisperkit` | argv, model/env, vocab, JSONL parse, errors | P-WK-1..N |
| STT strategy contract — `sherpa_onnx` | argv, model/env, JSONL parse, errors (JVM CLI) | P-SHP-1..N |
| STT strategy contract — `argmax` (dormant) | WAV reader, Deepgram parse, no-API-key error | P-ARG-1..N |
| Subprocess boundary (shared) | binary-not-found hint, non-zero exit → stderr, malformed JSON, lazy yield | P-SUB-1..5 |
| Cue matcher (Python) | phrase, synonym, case-insensitive, multi-cue, punctuation, triggering event | P-MAT-1..N |
| Combined / semantic matcher | exact-then-semantic, `match_method` tagging | P-SEM-1..2 |
| Orchestrator | (script × strategy) run, classify, false-positive, latency stats | P-ORC-1..N |
| Scoring | classify pass/partial/fail/false-positive, latency percentiles | P-SCO-1..N |
| Script builder | cue index, step boundaries (monotonic), segment grouping, seed-shape parity | P-SCR-1..N |
| YouTube ingestion | fetch happy/disabled/blocked/empty, idempotent ingest, manifest validation | P-YT-1..N |
| Cross-language drift | `TranscriptEvent` field parity Python↔TS | P-DRF-1..2 |
| **Cleanup-pack data** | `dictation.json` / `road-to-sale.json` load + schema + RTS lexicon | P-PACK-1..N |
| **derive_vocab producer** | per-make terms, dedupe, scoping, output schema | P-VOC-1..N |
| Reporting (markdown / CSV) | run → markdown summary + CSV rows | P-REP-1..2 |
| TS `VoiceEngine` | `load`, `listStrategies`, `getStrategy`, `startSession`, register | T-FAC-1..N |
| TS `MockTranscriptionStrategy` | replays events, stop, unsubscribe | T-MOCK-1..3 |
| TS `CueMatcher` | parity set with Python matcher | T-MAT-1..N |
| TS `CleanupStrategy` / `RuleBasedCleanupStrategy` | levels, fillers, commands, vocab, repeats, registry | T-CLN-1..N |
| **TS↔Python matcher behaviour parity** | identical fixtures → identical detections | X-PAR-1 |

---

## 3. Test tiers

| Tier | What | When | Budget |
|---|---|---|---|
| **Tier 1 — critical path** | facade load + list; each strategy parses a happy-path JSONL → ordered `TranscriptEvent`s; matcher fires on phrase/synonym; rule-based cleanup core; orchestrator runs mock end-to-end | Every commit | < 30 s |
| **Tier 2 — integration** | strategy argv/env/vocab flags; all subprocess error paths; scoring classification + latency; script builder + youtube ingestion; cleanup-pack loading; derive_vocab; cross-language drift; reporting | Before every PR / release | 2–5 min |
| **Tier 3 — edge / stress / quality** | lazy-yield/backpressure on long streams; combined/semantic matcher; very large transcripts; **real-audio benchmark accuracy** (manual, §6); malformed/adversarial JSONL fuzz | Weekly / major release | 10–30 min |

Subprocess-backed and `node`-dependent tests **skip** (not fail) when the toolchain/binary is
absent, so CI stays green without swift / java / built binaries — see §5.

---

## 4. Primary scenarios

### 4.1 Python lab facade — `VoiceEngineLab` (Tier 1/2)
- **P-FAC-1 (T1):** `VoiceEngineLab.load()` returns a `VoiceEngineLab`.
- **P-FAC-2 (T1):** `list_strategies()` returns **exactly** the five registered names
  (`apple_sfspeechrecognizer_vocab`, `apple_speech_transcriber`, `mock`, `sherpa_onnx`,
  `whisperkit`) — sorted, `argmax` absent.
- **P-FAC-3 (T1):** `get_strategy("nope")` raises `UnknownStrategyError`.
- **P-FAC-4 (T1):** registered strategies are the **real** wrapper classes, not stubs
  (`isinstance` of the concrete strategy).
- **P-FAC-5 (T2):** `transcribe_file("mock", fixture)` replays a fixture JSONL to the right
  count of `TranscriptEvent`s with correct stability ordering (partial→final) and text.
- **P-FAC-6 (T2):** `match_cues(events, atoms)` returns `CueDetection`s for known cues (the
  exact path, `use_semantic=False`).

### 4.2 STT strategy contract — per-strategy, via stubbed binary (Tier 1/2)
Each non-`mock` strategy is a thin subprocess wrapper. The **identical** suite runs against
every one (mock binary fake) so a divergence is a strategy bug:
- **…-PARSE (T1):** happy-path JSONL (a `partial` then a `final`) → 2 ordered `TranscriptEvent`s
  with `text`, `timestamp_ms`, `latency_ms_from_audio_start`, `confidence`, `engine_metadata`
  faithfully mapped.
- **…-ARGV (T2):** the wrapper passes the expected argv (`--file`, `--mode`/`--model`,
  `--locale`, `--partials`, and `--vocab` only when given).
- **…-PARTIALS (T2):** `partials=False` serializes `--partials false`.
- **…-MODEL/ENV (T2):** default model used when env unset; env var (`WHISPERKIT_MODEL`,
  `SHERPA_ONNX_MODEL`) overrides; per-instance `model=` overrides env.
- **…-VOCAB (T2):** `--vocab` passed when a vocab file exists; missing vocab → `TranscriptionError`
  ("vocab file not found"). Applies to `apple_sfspeechrecognizer_vocab` and `whisperkit`.
- Strategies to cover identically: `apple_speech_transcriber`, `apple_sfspeechrecognizer_vocab`,
  `whisperkit`, **`sherpa_onnx`** (the JVM/Android-production lib), and the dormant `argmax`
  (WAV-frame + Deepgram-message variant).

### 4.3 Native-CLI subprocess boundary — shared (Tier 2/3)
The plumbing is shared (`whisperkit._event_from_json` / `_terminate`, reused by `sherpa_onnx`).
Assert once per strategy *and* as a contract:
- **P-SUB-1 (T2):** audio file missing → `TranscriptionError` ("audio file not found").
- **P-SUB-2 (T2):** binary missing/non-exec → `TranscriptionError` with a **build hint**
  (build script path) and respects the `*_BIN` env override resolution.
- **P-SUB-3 (T2):** non-zero exit → `TranscriptionError` that **surfaces stderr** + exit code.
- **P-SUB-4 (T2):** malformed JSON line → `TranscriptionError` ("failed to parse").
- **P-SUB-5 (T2):** missing required field → `TranscriptionError` ("malformed event").
- **P-SUB-6 (T3):** events **yield lazily** — the consumer reads the first event without the
  wrapper buffering full stdout (backpressure / streaming contract).
- **P-SUB-7 (T3, manual-gated):** the **real** built binary responds to `--help` with exit 0
  (skipped unless built).

### 4.4 Cue matcher — Python (Tier 1/2)
- **P-MAT-1 (T1):** exact phrase fires a detection with the right `cue_id` and `matched_phrase`.
- **P-MAT-2 (T1):** synonym fires.
- **P-MAT-3 (T2):** case-insensitive.
- **P-MAT-4 (T2):** no match → no detection (zero false positives).
- **P-MAT-5 (T2):** multiple cues in one event each fire **exactly once**.
- **P-MAT-6 (T2):** punctuation normalized.
- **P-MAT-7 (T2):** the triggering event + its timestamp are attached to the detection.

### 4.5 Combined / semantic matcher (Tier 3)
- **P-SEM-1 (T3):** exact on all events **plus** semantic on finals for exact-missed cues;
  detection tagged `match_method="semantic"` with a `similarity_score`. Gated on `fastembed`.
- **P-SEM-2 (T3):** an exact hit is tagged `match_method="exact"` and is preferred over semantic.

### 4.6 Orchestrator (Tier 1/2)
- **P-ORC-1 (T1):** `(script × strategy)` run over the mock strategy classifies expected cues
  as `pass` and returns per-strategy latency stats (`count ≥ N`).
- **P-ORC-2 (T2):** a negative cue that appears is flagged `false_positive`.
- **P-ORC-3 (T2, pending):** the **transcript cache** is honored — a second run with a cached
  `data/transcripts/{strategy}/{audio_id}.jsonl` skips STT (cache-hit path).

### 4.7 Scoring (Tier 1/2)
- **P-SCO-1 (T1):** detected + confident → `pass`.
- **P-SCO-2 (T2):** low confidence → `partial`.
- **P-SCO-3 (T2):** no detection → `fail`.
- **P-SCO-4 (T2):** v2 timestamp tolerance — within tolerance → `pass`, drift → `partial`
  (kept for the future timestamp mode).
- **P-SCO-5 (T2):** `classify_run` marks negative cues `false_positive`.
- **P-SCO-6 (T2):** latency percentiles p50/p95/p99 correct + ordered; empty input → zeroes.

### 4.8 Script builder (Tier 2)
- **P-SCR-1 (T2):** cue index loads workflow + feature cues with correct category mapping.
- **P-SCR-2 (T2):** segment cue matching fires each cue once.
- **P-SCR-3 (T2):** step boundaries advance through the NADA workflow and are **monotonic**
  (no regression to an earlier step).
- **P-SCR-4 (T2):** consecutive same-step segments are grouped; cue timestamps are relative to
  the segment start.
- **P-SCR-5 (T2):** the built YAML shape matches the seed scripts (`script-001`); empty
  transcript → empty segments.

### 4.9 YouTube ingestion (Tier 2)
- **P-YT-1 (T2):** happy-path fetch → segments sorted by start, blank text dropped (fake API).
- **P-YT-2 (T2):** transcripts-disabled / age-restricted → `TranscriptUnavailableError`;
  request-blocked / random error → `TranscriptFetchError` (transient, not the unavailable
  subclass); empty video id → `TranscriptFetchError`.
- **P-YT-3 (T2):** `ingest_one()` writes a valid script YAML carrying required `source.*` fields.
- **P-YT-4 (T2):** `ingest_one()` is idempotent (skip on re-run, `--force` overrides);
  unavailable → `no_transcript` (no file), fetch error → `fetch_error` (no file).
- **P-YT-5 (T2):** `run_ingest_youtube()` processes a full manifest; manifest missing required
  fields → `ValueError`.

### 4.10 Cross-language drift / parity (Tier 2)
- **P-DRF-1 (T2):** a Python `TranscriptEvent` round-trips through a `node` loader with every
  field surviving (skips if `node` absent).
- **P-DRF-2 (T2):** the TS types file declares the same field names as the Python dataclasses
  (`TranscriptEvent`, `CueAtom`, `CueDetection`).
- **X-PAR-1 (T2, NEW):** **matcher behaviour parity** — the *same cue atoms + same transcript
  fixtures* fed to the Python `CueMatcher` and the TS `CueMatcher` produce the **same set of
  detections** (cue ids + matched phrases). Field-name drift (P-DRF-2) does not prove the two
  algorithms agree; this does.

### 4.11 Cleanup contract — TS `RuleBasedCleanupStrategy` (Tier 1/2)
- **T-CLN-1 (T2):** registers under `bootstrapDefaultCleanupStrategies()` as `rule-based`.
- **T-CLN-2 (T1):** `level:'off'` returns raw text untouched, `ops_applied == []`.
- **T-CLN-3 (T1):** `full` removes fillers, capitalizes, normalizes "i"→"I"; `ops` includes
  `fillers`.
- **T-CLN-4 (T2):** command grammar applied (`new paragraph` → `\n\n`), `ops` includes `commands`.
- **T-CLN-5 (T2):** forced vocab applied **after** cleanup, `ops` includes `vocab`.
- **T-CLN-6 (T2):** repeated words collapse at `full`.
- **T-CLN-7 (T2, pending):** `level:'light'` preserves exact wording (no repeat-collapse) but
  still strips fillers — the level *boundary* is asserted, not just `off`/`full`.
- **T-CLN-8 (T3, pending):** `CleanupResult` carries `used_fallback`, `latency_ms`,
  `engine_metadata` — the full result contract, not only `cleaned_text`.

### 4.12 Cleanup-pack **data** loading (Tier 2) — currently unguarded
- **P-PACK-1 (T2, NEW):** `cleanup-packs/dictation.json` parses and carries the required keys
  (`profile`, `min_words_for_cleanup`, `command_grammar`, `fillers`, `junk_phrases`,
  `prompts.{light,full}`).
- **P-PACK-2 (T2, NEW):** `cleanup-packs/road-to-sale.json` carries everything in `dictation`
  **plus** `lexicon.terms` (dealership glossary) and `lexicon.expansions`
  (`"f and i" → "F&I"`, `"trade in" → "trade-in"`).
- **P-PACK-3 (T2, NEW):** feeding the pack's `command_grammar` + `lexicon.expansions` (as
  `VocabMap`) through `RuleBasedCleanupStrategy` produces the documented transforms
  (the pack data actually drives cleanup, end-to-end through the contract).
- **P-PACK-4 (T3, NEW):** the RTS pack `lexicon.expansions` round-trips: each spoken form maps
  to its written form via the vocab pass (e.g. "trade in" → "trade-in").

### 4.13 derive_vocab **producer** (Tier 2) — currently unvalidated
- **P-VOC-1 (T2, NEW):** `derive_make(make_id)` over a tiny fixture catalog returns the
  documented schema (`make_id`, `make_name`, `source`, `counts`, `models`, `trims`, `features`,
  `terms`).
- **P-VOC-2 (T2, NEW):** terms are **deduped** case-insensitively and ordered
  models+trims first, then features.
- **P-VOC-3 (T2, NEW):** only short (≤3-word) feature synonyms are included; sentence-like
  cue phrases are excluded (vocabulary, not cues).
- **P-VOC-4 (T2, NEW):** `main()` writes `derived/<make>.vocab.json` and is **scoped to one
  make** (Honda terms don't leak Toyota words) — the WhisperKit-bias-window guarantee.

### 4.14 Reporting (Tier 2) — currently unguarded
- **P-REP-1 (T2, NEW):** a finished `RunResult` renders a markdown summary with per-strategy
  pass/partial/fail counts.
- **P-REP-2 (T2, NEW):** the CSV writer emits one row per (script × strategy × cue) with the
  documented columns.

### 4.15 TS facade + mock (Tier 1)
- **T-FAC-1 (T1):** `VoiceEngine.load()` returns a facade; `listStrategies()` includes `mock`
  after bootstrap; `getStrategy('nope')` throws `UnknownStrategyError`; `startSession('mock', …)`
  returns a `Session` with `onEvent`/`stop`.
- **T-MOCK-1..3 (T1):** mock replays events to subscribers; `stop()` halts emission;
  `unsubscribe()` stops delivery.

---

## 5. Conventions & gating
- **Subprocess + node tests `skip`** (never fail) when swift/java/`node`/the built binary are
  absent (`pytest.mark.skipif` on binary existence; `node`-on-PATH guard). CI without the
  toolchain stays green; the real-binary lane runs them.
- Heavy/real-model tests are excluded from the Tier-1 fast suite.
- **No real user audio/text in the repo** — fixtures are synthetic JSONL or public-domain.
- Stub binaries are written to `tmp_path` per test (hermetic, parallel-safe).

---

## 6. Inherently manual / benchmark tier (NOT unit-automatable)

These need **real models + real audio** and a host with the vendor stack. They are the lab's
*reason to exist* but cannot be asserted in CI; document them as a manual/benchmark lane:

- **M-1 — Apple SpeechTranscriber accuracy** (macOS 26+, Speech-framework auth): WER + cue
  recall on real fixtures.
- **M-2 — Apple SFSpeechRecognizer + dealership vocab**: does `contextualStrings` biasing
  measurably improve proper-noun spelling vs. no bias.
- **M-3 — WhisperKit accuracy** (one-time HuggingFace model download): WER per model size;
  long-audio (>30 s window) completeness.
- **M-4 — sherpa-onnx accuracy** (ONNX models, JVM): the Android-production lib's WER, and
  whether its simulated partials are acceptable.
- **M-5 — Argmax Local Server** (paid SDK, `ARGMAX_API_KEY`, running server): end-to-end
  WebSocket streaming accuracy + latency.
- **M-6 — Semantic matcher quality** (`fastembed` models): recall lift vs. exact-only on real
  transcripts, false-positive rate.
- **M-7 — Full benchmark run**: the `(script × strategy)` matrix over the real fixture corpus
  → markdown/CSV report; this is the evidence behind "which engine do we ship?". Run via the
  `voice-lab run` CLI on a real machine, not in CI.

Automated tests assert **contract conformance** (parsing, argv, errors, classification);
the manual tier asserts **accuracy**. The two must not be conflated.

---

## 7. Coverage ledger (plan vs. implementation)

Computed against the **real** test files:
Python (85 tests) under `voice-engine/lab/tests/` —
`test_facade.py` (6), `test_apple_strategy.py` (14), `test_whisperkit_strategy.py` (15),
`test_argmax_strategy.py` (11), `test_matcher.py` (7), `test_orchestrator.py` (2),
`test_scoring.py` (8), `test_script_builder.py` (8), `test_youtube_ingestion.py` (12),
`test_drift.py` (2). TS (20 tests) under `voice-engine/tests/` —
`facade.test.ts` (4), `cleanup.test.ts` (6), `matcher.test.ts` (7), `mock-strategy.test.ts` (3).

**Status key:** ✅ COVERED (a real test asserts it) · ⚠️ PARTIAL · ⛔ PENDING (no test).

| Plan item | Tier | Status | Notes (real test) |
|---|---|---|---|
| P-FAC-1 load returns facade | 1 | ✅ COVERED | `test_facade.py::test_load_returns_facade` |
| P-FAC-2 exact five strategies, argmax absent | 1 | ⚠️ PARTIAL | only membership asserted (`test_list_strategies_includes_mock`, per-strategy `test_registry_registers_*`). No test pins the **exact sorted set** nor that `argmax` is absent. |
| P-FAC-3 unknown → error | 1 | ✅ COVERED | `test_facade.py::test_get_strategy_unknown_raises` |
| P-FAC-4 real impls not stubs | 1 | ✅ COVERED | `test_*_strategy.py::test_registry_returns_real_*` |
| P-FAC-5 transcribe_file replays mock | 2 | ✅ COVERED | `test_facade.py::test_transcribe_file_with_mock_replays_jsonl` |
| P-FAC-6 match_cues via facade | 2 | ⛔ PENDING | matcher tested directly (`test_matcher.py`) and via orchestrator, but **not through `VoiceEngineLab.match_cues()`**. |
| P-MOCK-1..2 mock replays JSONL | 1 | ✅ COVERED | `test_facade.py::test_transcribe_file_with_mock_replays_jsonl` |
| apple_speech_transcriber PARSE/ARGV/PARTIALS | 1/2 | ✅ COVERED | `test_apple_strategy.py` (`parses_jsonl`, `passes_expected_argv`, `partials_false_serializes_to_false`) |
| apple_sfspeechrecognizer_vocab VOCAB | 2 | ✅ COVERED | `test_apple_strategy.py` (`sfspeechrecognizer_passes_vocab_flag`, `missing_vocab_raises`) + registry tests |
| whisperkit PARSE/ARGV/MODEL/ENV/VOCAB | 1/2 | ✅ COVERED | `test_whisperkit_strategy.py` (8 wrapper tests incl. `default_model`, `env_var_overrides`, `passes_vocab_flag`, `missing_vocab_raises`) |
| **sherpa_onnx PARSE/ARGV/MODEL/ENV/errors** | 1/2 | ⛔ **PENDING** | **No `test_sherpa_onnx_strategy.py` exists.** Only indirectly referenced by `registry.py`. The Android-production lib has **zero** wrapper coverage despite being structurally identical to whisperkit. |
| argmax WAV/parse/no-key | 2 | ✅ COVERED | `test_argmax_strategy.py` (read_wav variants, parse partial/final/empty/binary/malformed, no-API-key) |
| P-SUB-1 audio missing | 2 | ✅ COVERED | apple + whisperkit `missing_audio_file_raises_clear_error` (⚠️ not sherpa_onnx) |
| P-SUB-2 binary missing + build hint + env | 2 | ⚠️ PARTIAL | apple + whisperkit `missing_binary_raises_with_build_hint`; **sherpa_onnx untested**; env-override resolution not directly asserted |
| P-SUB-3 non-zero exit → stderr | 2 | ⚠️ PARTIAL | apple + whisperkit `nonzero_exit_surfaces_stderr`; **sherpa_onnx untested** |
| P-SUB-4 malformed JSON | 2 | ⚠️ PARTIAL | apple + whisperkit `malformed_json_raises`; **sherpa_onnx untested** |
| P-SUB-5 missing field | 2 | ⚠️ PARTIAL | apple + whisperkit `missing_required_field_raises`; **sherpa_onnx untested** |
| P-SUB-6 lazy yield / backpressure | 3 | ⚠️ PARTIAL | `test_apple_strategy.py::test_events_yield_lazily` only; not whisperkit/sherpa_onnx |
| P-SUB-7 real binary --help | 3 | ✅ COVERED (skip-gated) | apple + whisperkit `test_real_binary_responds_to_help` (no sherpa equivalent) |
| P-MAT-1..7 cue matcher (Python) | 1/2 | ✅ COVERED | `test_matcher.py` (7 tests: phrase, synonym, case, no-match, multi-cue, punctuation, triggering event) |
| P-SEM-1..2 combined/semantic matcher | 3 | ⛔ PENDING | `combined_matcher.py` / `semantic_matcher.py` have **no tests** |
| P-ORC-1 run + classify pass + latency | 1 | ✅ COVERED | `test_orchestrator.py::test_orchestrator_runs_mock_and_classifies_v1_style` |
| P-ORC-2 false positive flagged | 2 | ✅ COVERED | `test_orchestrator.py::test_orchestrator_flags_false_positive` |
| P-ORC-3 transcript cache-hit skips STT | 2 | ⛔ PENDING | cache path in `orchestrator.py` not exercised by a test |
| P-SCO-1..6 scoring | 1/2 | ✅ COVERED | `test_scoring.py` (8 tests: pass/partial/fail, timestamp tolerance, classify_run FP, latency percentiles + empty) |
| P-SCR-1..5 script builder | 2 | ✅ COVERED | `test_script_builder.py` (8 tests: cue index, segment cues, monotonic boundaries, grouping, relative ts, seed-shape, empty) |
| P-YT-1..5 youtube ingestion | 2 | ✅ COVERED | `test_youtube_ingestion.py` (12 tests: fetch happy/disabled/age/blocked/random/empty-id, ingest_one valid/idempotent/no_transcript/fetch_error, manifest, bad-manifest) |
| P-DRF-1 event round-trips through node | 2 | ✅ COVERED (skip-gated) | `test_drift.py::test_transcript_event_roundtrips_through_ts` |
| P-DRF-2 TS field names match Python | 2 | ✅ COVERED | `test_drift.py::test_cue_atom_field_names_match_ts` |
| **X-PAR-1 matcher behaviour parity TS↔Python** | 2 | ⛔ **PENDING** | drift test checks **field names only**. No test feeds identical fixtures to both `CueMatcher`s and asserts identical detections. The two suites (`test_matcher.py` / `matcher.test.ts`) use the same fixtures but are never cross-checked. |
| T-CLN-1 registers rule-based | 2 | ✅ COVERED | `cleanup.test.ts` "registers as a default cleanup strategy" |
| T-CLN-2 off untouched | 1 | ✅ COVERED | `cleanup.test.ts` "level off returns raw text" |
| T-CLN-3 full fillers+caps | 1 | ✅ COVERED | `cleanup.test.ts` "removes fillers and capitalizes" |
| T-CLN-4 command grammar | 2 | ✅ COVERED | `cleanup.test.ts` "obeys command grammar" |
| T-CLN-5 vocab after cleanup | 2 | ✅ COVERED | `cleanup.test.ts` "applies the vocab map" |
| T-CLN-6 collapse repeats at full | 2 | ✅ COVERED | `cleanup.test.ts` "collapses repeated words" |
| T-CLN-7 `light` level boundary | 2 | ⛔ PENDING | no test exercises `level:'light'` — only `off` and `full` |
| T-CLN-8 full CleanupResult contract | 3 | ⛔ PENDING | `used_fallback` / `latency_ms` / `engine_metadata` never asserted |
| **P-PACK-1 dictation.json loads + schema** | 2 | ⛔ **PENDING** | **No test loads `cleanup-packs/dictation.json`** anywhere in this module. (A *Swift* test in `dictation/` checks its own copy; nothing here.) |
| **P-PACK-2 road-to-sale.json + lexicon** | 2 | ⛔ **PENDING** | **No test loads `road-to-sale.json`** or validates `lexicon.terms` / `lexicon.expansions`. |
| P-PACK-3 pack data drives cleanup end-to-end | 2 | ⛔ PENDING | cleanup tested only with inline grammar/vocab, never pack-sourced |
| P-PACK-4 RTS expansions round-trip | 3 | ⛔ PENDING | "f and i"→"F&I" / "trade in"→"trade-in" never asserted |
| **P-VOC-1..4 derive_vocab output** | 2 | ⛔ **PENDING** | **`vehicle-feature-catalog/scripts/derive_vocab.py` has no test** — schema, dedupe, ≤3-word synonym filter, per-make scoping all unvalidated. |
| P-REP-1..2 reporting markdown/CSV | 2 | ⛔ PENDING | `reporting/markdown.py` + `reporting/csv_writer.py` have no tests |
| T-FAC-1 TS facade | 1 | ✅ COVERED | `facade.test.ts` (load, list, unknown throws, startSession) |
| T-MOCK-1..3 TS mock strategy | 1 | ✅ COVERED | `mock-strategy.test.ts` (replays, stop, unsubscribe) |
| Synthesis (ElevenLabs/noise) | 3 | ⛔ PENDING | `synthesis/` has no tests (largely manual/TTS — acceptable to leave manual) |
| Audio ingestion (`ingestion/audio.py`) | 2 | ⛔ PENDING | no test for the audio normalization path |

### Headline gaps
1. **`sherpa_onnx` strategy is entirely untested.** Apple and WhisperKit each have a full
   fake-binary suite (parse, argv, model/env, vocab, all five error paths, lazy yield, real
   `--help`). `sherpa_onnx` — structurally identical and the **same library Android ships** —
   has **zero** wrapper tests. Highest-priority gap.
2. **TS↔Python cue-matcher parity is unverified.** `test_drift.py` proves the *types* share
   field names but never that the two `CueMatcher` *implementations* agree on the same input.
3. **Cleanup-pack JSON loading is unguarded in this module.** Neither `dictation.json` nor
   `road-to-sale.json` is loaded/validated by any voice-engine test, and the RTS `lexicon`
   (`terms` + `expansions`) is never asserted.
4. **`derive_vocab.py` output is never validated** — no test covers schema, dedupe, the
   ≤3-word synonym filter, or the per-make scoping guarantee.
5. **`apple_sfspeechrecognizer_vocab`** is covered (vocab flag + missing-vocab + registry) — *not*
   a gap, contrary to a quick read; its tests live inside `test_apple_strategy.py`.
6. Smaller gaps: combined/semantic matcher, orchestrator cache-hit path, reporting writers,
   `light` cleanup level + full `CleanupResult` contract, audio ingestion.

---

## 8. Pending test backlog

Concrete tests to write, grouped by tier. **No test code was created or modified by this
plan — this is the backlog only.**

### Tier 1 (critical path)
1. `test_facade.py` — pin the **exact sorted strategy list** and assert `argmax` is **not**
   registered (P-FAC-2).

### Tier 2 (integration) — the bulk of the gap
2. **`test_sherpa_onnx_strategy.py` (NEW FILE)** — mirror `test_whisperkit_strategy.py` against
   a fake JVM binary: `parses_jsonl`, `passes_expected_argv`, `default_model_used`,
   `env_var_overrides_default_model` (`SHERPA_ONNX_MODEL`), `partials_false_serializes`,
   `missing_audio_file_raises`, `missing_binary_raises_with_build_hint`,
   `nonzero_exit_surfaces_stderr`, `malformed_json_raises`, `missing_required_field_raises`,
   plus the skip-gated real `--help`. (≈10–11 tests — closes gap #1.)
3. **`test_matcher_parity` (NEW, in `test_drift.py` or a new file)** — feed the shared cue
   atoms + transcript fixtures to the Python `CueMatcher` and (via `node`) the TS `CueMatcher`;
   assert identical `{cue_id, matched_phrase}` detection sets (X-PAR-1; closes gap #2).
4. **`test_cleanup_packs.py` (NEW FILE)** — load `cleanup-packs/dictation.json` and
   `road-to-sale.json`; assert required keys + `prompts.{light,full}`; assert RTS
   `lexicon.terms` non-empty and `lexicon.expansions` contains the documented mappings
   (P-PACK-1/2). Optionally drive the pack's grammar+expansions through a JS harness of
   `RuleBasedCleanupStrategy` (P-PACK-3/4).
5. **`test_derive_vocab.py` (NEW FILE)** — point `derive_make()` at a tiny fixture catalog;
   assert output schema, case-insensitive dedupe + ordering, the ≤3-word synonym filter, and
   per-make scoping (P-VOC-1..4; closes gap #4).
6. `test_facade.py` — `match_cues()` through the **facade** (not just `CueMatcher` directly)
   returns expected detections (P-FAC-6).
7. `test_orchestrator.py` — second run with a pre-seeded transcript cache **skips STT**
   (cache-hit path, P-ORC-3).
8. `test_reporting.py` (NEW FILE) — markdown summary counts + CSV row schema (P-REP-1/2).
9. `cleanup.test.ts` — add `level:'light'` boundary (strips fillers, preserves wording, no
   repeat-collapse) (T-CLN-7).

### Tier 3 (edge / stress / quality)
10. `test_combined_matcher.py` (NEW FILE) — exact-then-semantic, `match_method` tagging,
    `similarity_score`; gated on `fastembed` (P-SEM-1/2).
11. whisperkit + sherpa_onnx **lazy-yield/backpressure** tests to match the apple one (P-SUB-6).
12. RTS `lexicon.expansions` round-trip through the cleanup vocab pass (P-PACK-4).
13. `cleanup.test.ts` — assert the full `CleanupResult` contract (`used_fallback`,
    `latency_ms`, `engine_metadata`) (T-CLN-8).

### Manual / benchmark lane (NOT automated — see §6)
14. M-1..M-7: real-model accuracy + the full `(script × strategy)` benchmark run. Tracked as a
    device/release activity, not CI.

### Count summary
- **Plan scenarios (numbered IDs across §4):** ~70.
- **✅ COVERED:** ~46 (the five-strategy core minus sherpa_onnx, matcher, scoring, script
  builder, youtube ingestion, drift, TS facade/mock/cleanup core).
- **⚠️ PARTIAL:** ~7 (strategy list pinning; the shared subprocess error paths that exist for
  apple/whisperkit but not sherpa_onnx; lazy-yield).
- **⛔ PENDING:** ~17 plan items → **13 concrete automated tests-to-write** (backlog 1–13)
  plus the manual benchmark lane (14).
- **Headline pending count by gap:** sherpa_onnx (~10 tests), cleanup-pack loading (~3),
  derive_vocab (~4), matcher parity (1), semantic matcher (~2), reporting (~2), facade
  list/match_cues (2), orchestrator cache (1), cleanup level/result (2).

**No production or test code was changed. This document is the deliverable.**

---

## See also
- Module architecture: [`architecture.md`](./architecture.md)
- Public API (Python + TS): [`api.md`](./api.md)
- Model contracts (STT + cleanup): [`model-contracts.md`](./model-contracts.md)
- Whole-repo map: [`../../docs/architecture.md`](../../docs/architecture.md)
- Lab internals: [`../lab/docs/`](../lab/docs/)
