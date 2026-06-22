# Voice Engine — Build & Run (local)

How to build, test, run, and "deploy" the `voice-engine` module on a freshly
cloned machine. **Local only** — there is no CI or cloud in scope here.

`voice-engine` is three things in one module:

1. **Python comparison lab** (`lab/`) — the live, runnable core. Transcribes
   audio through STT strategies and compares cue-match results.
2. **TS reference skeleton** (`src/`, `tests/`) — the contract mirrored natively
   by the apps. Only the `mock` strategy runs in TS; it exists to pin the
   contract, not to ship.
3. **Native STT CLIs** (`native/apple/*`, `native/android/*`) — small command-line
   binaries (Apple Swift, WhisperKit Swift, sherpa-onnx JVM) that the Python lab
   spawns as subprocesses. These are heavy and platform-specific.

All four entry-point scripts live at the module root: `build.sh`, `test.sh`,
`run.sh`, `deploy-local.sh`.

---

## Prerequisites

| Tool | Needed for | Notes |
|------|-----------|-------|
| **Python ≥ 3.10** | Python lab (always) | `build.sh` hard-fails if missing or too old. |
| **Node.js ≥ 18 + npm** | TS skeleton (always) | npm ships with Node. |
| **Swift / Xcode** | Apple + WhisperKit native CLIs | macOS only. `--with-native` warns (not fatal) if absent. |
| **JDK (Java 17+)** | sherpa-onnx native CLI | Uses the bundled Gradle wrapper (`./gradlew`) — no system Gradle needed. |
| **curl** | sherpa-onnx native CLI | Downloads the sherpa-onnx native libs on first build. |

The lab also depends on the sibling **`vehicle-feature-catalog`** module (in the
repo root, not on PyPI). `build.sh` installs it editable automatically if present.

**Fresh non-macOS machine:** you can still build and test the Python lab + TS
skeleton and run the `mock` strategy. Native CLIs only build on macOS (Darwin);
`build.sh --with-native` warns and skips them elsewhere.

---

## Scripts

### `./build.sh` — build the module

```bash
./build.sh                 # python lab + TS skeleton  (default, fast)
./build.sh --with-native   # also build the native STT CLIs (alias: ./build.sh native)
./build.sh --help
```

What it does:

1. Checks core prerequisites (python3 ≥ 3.10, node, npm) — hard-fails if missing.
2. Creates/reuses `lab/.venv`, upgrades pip, installs `vehicle-feature-catalog`
   (editable) then `voice_lab` (editable, with `[dev]` extras).
3. `npm install`, typechecks, and compiles the TS skeleton to `dist/`.
4. **With `--with-native` only:** orchestrates the existing per-CLI native build
   scripts (it never re-implements them):
   - `native/apple/build.sh` — AppleSTT (`.app` bundle, needed for macOS TCC)
   - `native/apple/whisperkit_build.sh` — WhisperKitSTT
   - `native/android/sherpa_onnx_build.sh` — sherpa-onnx JVM CLI
   Missing native toolchains (swift / java) are warned, not fatal.

Idempotent and fresh-clone-safe.

### `./test.sh` — run all tests

```bash
./test.sh
```

Runs the Python lab pytest suite (~85 tests) **and** the TS vitest suite
(~20 tests). Exits non-zero if either suite fails. Requires `./build.sh` first.

### `./run.sh` — run the lab CLI

```bash
./run.sh                                # list available strategies + CLI help
./run.sh run --strategies mock          # comparison run, no native deps needed
./run.sh run --strategies whisperkit    # needs the native CLI (build --with-native)
./run.sh --help                         # full voice-lab CLI help
```

With no args it prints the available strategies and the `voice-lab` usage. Any
args are forwarded straight to the `voice-lab` CLI. Strategies: `mock`,
`apple_speech_transcriber`, `apple_sfspeechrecognizer_vocab`, `whisperkit`,
`sherpa_onnx`. Only `mock` runs without native binaries.

### `./deploy-local.sh` — full local setup

```bash
./deploy-local.sh
```

"Local deploy" here = get this machine to the point where the lab runs
end-to-end, **including** the native binaries it spawns. It runs
`build.sh --with-native`, smoke-verifies `from voice_lab import VoiceEngineLab`,
then reports which native STT binaries are present. **There is no server** — the
native binaries are the deployable artifacts the lab calls.

---

## Where artifacts land

| Artifact | Path | Gitignored |
|----------|------|------------|
| Python venv | `lab/.venv/` | yes |
| TS build output | `dist/` | yes |
| TS deps | `node_modules/` | yes |
| AppleSTT bundle | `native/apple/AppleSTT/AppleSTT.app/Contents/MacOS/AppleSTT` | (native) |
| WhisperKitSTT binary | `native/apple/WhisperKitSTT/.build/release/WhisperKitSTT` | (native) |
| sherpa-onnx CLI | `native/android/SherpaOnnxSTT/build/install/SherpaOnnxSTT/bin/SherpaOnnxSTT` | (native) |

The lab resolves these default paths automatically. Override any of them with the
env vars `APPLE_STT_BIN`, `WHISPERKIT_BIN`, `SHERPA_ONNX_BIN` (and model overrides
`WHISPERKIT_MODEL`, `SHERPA_ONNX_MODEL`).

---

## Troubleshooting

- **`python3 not found` / `requires >= 3.10`** — install/upgrade Python; ensure
  `python3` is on PATH.
- **`vehicle-feature-catalog not found`** — the sibling module must exist at
  `../vehicle-feature-catalog` relative to `voice-engine/`. Clone the full repo,
  not just this module.
- **`lab/.venv not found` from test.sh / run.sh** — run `./build.sh` first.
- **Native build skipped on non-mac** — expected. Swift/WhisperKit/sherpa CLIs
  only build on macOS. Use the `mock` strategy elsewhere.
- **`swift not on PATH`** — install Xcode (or the Swift toolchain) for the Apple
  CLIs. See `native/apple/SETUP.md`.
- **`java not on PATH`** — install a JDK (Java 17+) for the sherpa-onnx CLI. The
  Gradle wrapper (`./gradlew`) is bundled, so you do not need a system Gradle.
- **WhisperKit / sherpa first run is slow** — model weights download on first use;
  subsequent runs are cached.
- **Stale venv after a Python upgrade** — delete `lab/.venv` and re-run
  `./build.sh` to recreate it.
