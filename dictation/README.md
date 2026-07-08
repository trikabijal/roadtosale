# Just Talk

**Just Talk** is an on-device macOS dictation app (a Wispr Flow replacement): press your activation key, speak, and your words appear in the frontmost app — cleaned into polished writing by an on-device LLM. Neither audio nor text ever leaves the machine. STT defaults to **Apple Speech** (fast, no download) with **WhisperKit** as the multilingual backup (Hinglish/Gujarati). An iOS custom keyboard (DictationKeyboard + DictationContainerApp) shares the same engine via a **container-app handoff** (a keyboard extension can't touch the mic, so the keyboard launches the host app to record — same trick Wispr uses); it's fully built but device-signing gated (custom keyboards can't run in the Simulator).

---

## Prerequisites

| Requirement | Why / how |
|-------------|-----------|
| **macOS 14+** (Apple Silicon recommended) | Build host and runtime for the Mac app. The scripts fail loudly on anything else. |
| **Xcode 16+** | Provides `xcodebuild` and `swift`. After installing from the App Store, point the toolchain at it: `sudo xcode-select -s /Applications/Xcode.app` |
| **Homebrew** | Used to install XcodeGen. See https://brew.sh |
| **XcodeGen** | `brew install xcodegen` — generates `JustTalk.xcodeproj` from `project.yml`. |
| **Network (first build only)** | SwiftPM resolves **GRDB** and **WhisperKit** from GitHub on the first build (defined in `Shared/Package.swift`). Subsequent builds are offline. |

There is no `package.json` / `requirements.txt` here — this is a Swift project. The dependency manifests are **`Shared/Package.swift`** (GRDB + WhisperKit) and **`project.yml`** (the XcodeGen project spec). All four scripts (`build.sh`, `test.sh`, `run.sh`, `deploy-local.sh`) check the required tools up front and exit with an `ERROR: … Install: …` hint if anything is missing — see `scripts/prereqs.sh`.

---

## Quickstart

```bash
cd dictation

./build.sh          # Release build → ./build, ends with: ** BUILD SUCCEEDED **
                    #   and prints the path to the built JustTalk.app
./test.sh           # Runs both suites: DictationCore 40 tests + JustTalkTests 9 tests pass
./run.sh            # Build + launch (lives in the menu bar — no Dock icon)
./deploy-local.sh   # Build + install "Just Talk.app" into /Applications as your daily driver
```

Expected results:

- **`./build.sh`** → `** BUILD SUCCEEDED **`, then `✓ Built: …/JustTalk.app`.
- **`./test.sh`** → DictationCore (40 tests) + JustTalkTests (9 tests) both pass, ending in `** TEST SUCCEEDED **`.
- **`./deploy-local.sh`** → installs into `/Applications`. Because a local build is **unsigned / development-signed** (not notarized), the first launch may trip Gatekeeper: **right-click the app → Open** once to bypass it. (Use `./deploy-local.sh --user` to install into `~/Applications` without admin rights.)

First launch opens a **setup wizard** — grant **Microphone** and **Accessibility**, pick your activation key, and press it once to confirm it reaches the app. On a capable Mac (Apple Silicon + macOS 26) the default provider is **Apple Speech** (no model download — just a Speech-Recognition grant); if you switch to **WhisperKit** (multilingual), the first dictation downloads its model (~40 MB–1 GB depending on tier), with progress in the menu bar item.

More detail: **[docs/build.md](docs/build.md)** (build/run/deploy, environments, troubleshooting) and **[docs/architecture.md](docs/architecture.md)** (components, contracts, isolation).

---

## Build / test scripts

```bash
./build.sh                 # Release build into ./build (default = macos)
./build.sh install         # build + copy to /Applications
./build.sh ios             # build the iOS container app + keyboard for the Simulator
./test.sh                  # all: DictationCore + JustTalkTests
./test.sh core             # DictationCore SwiftPM tests only (fast, no Xcode project)
./test.sh app              # JustTalkTests xcodebuild scheme only
./run.sh                   # build + launch
./deploy-local.sh          # install into /Applications
./deploy-local.sh --user   # install into ~/Applications (no admin rights)
```

Environment knobs for `build.sh`:

- `CONFIG=Debug|Release` — build configuration for the macOS app (default `Release`).
- `DEVELOPMENT_TEAM=XXXXXXXXXX` — sign with a stable team identity so macOS keeps Mic / Accessibility / Input-Monitoring grants across rebuilds. Set to `""` to force ad-hoc signing; unset uses the identity pinned in `project.yml`.

```bash
# Permissions that persist across rebuilds:
DEVELOPMENT_TEAM=XXXXXXXXXX ./deploy-local.sh
```

---

## Architecture (at a glance)

Two model layers, both swappable behind a contract (`{provider, model}`):

- **Speech-to-text** — `SpeechTranscriber`, chosen by dictation language. **Apple Speech is the
  default** (two impls behind one provider: `AppleAnalyzerTranscriber` on macOS 26 / iOS 26 — fast +
  native streaming, best for **English**; `AppleSpeechTranscriber` on older OSes — lightweight
  `SFSpeechRecognizer`). **WhisperKit** is the multilingual backup — the only one covering
  **Hinglish/Gujarati**. On macOS the first-launch default comes from `SystemCapabilities.recommendedProvider`.
  A `StreamingTranscriber` sibling drives the live per-word pill (PRD 0008).
- **Cleanup** — `TextCleanup` (Apple Foundation Models, with a deterministic rule-based fallback).

The **DictationCore** Swift package ships two products: **`DictationCoreBase`** (GRDB only, no
WhisperKit — linked by the memory-capped iOS keyboard + container app) and the full **`DictationCore`**
(base + WhisperKit + `SpeechTranscriberFactory` — linked by the macOS app). AI cleanup is wired into
both the macOS app and the iOS keyboard (same contract + data pack, so iOS inherits every cleanup fix
for free).

---

## macOS Usage (JustTalk)

1. The app lives in the menu bar (no Dock icon by design).
2. Hold your **activation key** (default **Fn / Globe**) to record; release to transcribe, clean, and paste. A floating HUD near the bottom of the screen shows a live mic level while you speak.
3. **Hotkey conflicts:** if another app (e.g. Wispr Flow) already owns Fn, quit it or choose a different key in the wizard — macOS can't share one key between two apps. (No need to touch the Fn/Globe system setting — Just Talk's tap swallows the key, so the emoji picker won't pop.)

Settings (menu bar → Settings):
- **Recording** — activation key (Fn, right ⌘/⌥/⌃, F5/F6/F13), activation mode (hold-to-talk or tap-to-toggle), auto-paste, start/stop sounds. "Re-run setup…" reopens the wizard.
- **Startup** — launch at login.
- **Speech-to-text** — provider (Apple SpeechTranscriber for English / WhisperKit for multilingual) + model/locale, and a "live pill grows word-by-word" toggle.
- **AI Cleanup** — level (Off / Light / **Full**, default) + engine (Foundation Models / rule-based).
- **Custom Vocabulary** — names/jargon that bias transcription and force spelling after cleanup.

Your clipboard is preserved: auto-paste restores whatever you had copied. Cleanup uses Apple Foundation Models (requires Apple Intelligence enabled); it falls back to deterministic rule-based cleanup if the model isn't ready.

---

## iOS Usage (DictationContainerApp + DictationKeyboard) — Flow Session handoff

An iOS keyboard extension **cannot access the microphone** (a hard sandbox limit — Wispr/Gboard/SwiftKey all hit it). So dictation is a **container-app handoff ("Flow Session")**: the keyboard opens `justtalk://record` to launch the host app, the app records + transcribes in the background (`UIBackgroundModes: audio`), writes the transcript to the App Group and Darwin-signals the keyboard, which inserts it via `textDocumentProxy`. All of this is built; full device validation is **deferred until a paid Apple Developer Program account is set up** (custom keyboards don't run in the Simulator, and `Allow Full Access` needs signing). Once available:

1. Connect your iOS 17+ device, select the **DictationContainerApp** scheme, set your Team for each target, and confirm the App Groups capability `group.com.trika.dictation` on **DictationKeyboard** and **DictationContainerApp** (shared store for the handoff + telemetry).
2. Build and install. On the device: **Settings → General → Keyboard → Keyboards → Add New Keyboard → Just Talk**, then enable **Allow Full Access**.
3. Switch to the Just Talk keyboard in any app (globe key), tap the mic (the host app flashes up to record), swipe back and keep talking, then tap the keyboard mic again — the text is inserted where your cursor is.

---

## Model Tiers (WhisperKit backup provider — macOS only)

WhisperKit is the multilingual backup on macOS; iOS uses Apple Speech (no download) exclusively.

| Tier | Size | Best for |
|------|------|---------|
| Tiny | ~40 MB | fastest, lowest accuracy |
| Base | ~75 MB | good balance of speed and accuracy |
| Small | ~150 MB | when latency matters |
| Large Turbo | ~954 MB | **default** — multilingual, best accuracy for daily use |

---

## Project Structure

```
dictation/
├── Shared/                     DictationCore Swift Package — two products (shared by all targets)
│   ├── Package.swift           Manifest: DictationCoreBase (GRDB) + DictationCore (full, +WhisperKit)
│   ├── Sources/DictationCore/           → product DictationCoreBase (GRDB only, no WhisperKit)
│   │   ├── RecordingEngine.swift          AVAudioEngine tap + format conversion + VAD + level
│   │   ├── SpeechTranscriber.swift        STT contract + provider/config + mock (no factory)
│   │   ├── ModelTier.swift                WhisperKit model-tier enum
│   │   ├── AppleAnalyzerTranscriber.swift Apple SpeechAnalyzer impl (macOS/iOS 26) — batch + streaming
│   │   ├── AppleSpeechTranscriber.swift   Apple SFSpeechRecognizer impl (older OS / keyboard budget)
│   │   ├── DictationHandoff.swift         iOS Flow-Session bridge (URL scheme + App Group + Darwin)
│   │   ├── SystemCapabilities.swift       Launch pre-flight + recommendedProvider default
│   │   ├── StreamingTranscriber.swift     Live-pill streaming contract + mock
│   │   ├── StreamingAgreement.swift       Pure LocalAgreement-2 confirmed/hypothesis logic (PRD 0008)
│   │   ├── TextCleanup.swift              Cleanup contract + config + pack loader + factory
│   │   ├── FoundationModelsCleanup.swift  On-device LLM cleanup (Apple Foundation Models)
│   │   ├── RuleBasedCleanup.swift         Deterministic fallback + shared text transforms
│   │   ├── TelemetryStore.swift           GRDB SQLite — TranscriptRecord, WeeklyStats
│   │   └── Resources/
│   │       └── dictation-cleanup-pack.json  Cleanup data pack (canonical: voice-engine/)
│   └── Sources/DictationCoreWhisper/    → product DictationCore (re-exports Base + WhisperKit)
│       ├── Reexport.swift                 @_exported import DictationCoreBase
│       ├── WhisperKitTranscriber.swift    WhisperKit impl + hallucination filter + streaming session
│       └── SpeechTranscriberFactory.swift STT factory (picks WhisperKit / Apple / mock)
├── JustTalk/                   macOS menu bar app
│   ├── JustTalkApp.swift, AppState.swift, MenuBarView.swift, SettingsView.swift
│   ├── HotkeyManager.swift     CGEventTap listener, matches the active HotkeyConfig
│   ├── HotkeyConfig.swift      Curated activation-key set + persistence
│   ├── HotkeyConflict.swift    OS Globe-setting + competitor-app detection
│   ├── PermissionsService.swift  Non-prompting mic/accessibility status + explicit requests
│   ├── OnboardingView.swift    Setup wizard (cards w/ live ticks) + window controller
│   ├── ClipboardPaster.swift
│   ├── RecordingHUD.swift      Floating live-level HUD
│   └── LoginItem.swift         Launch-at-login (SMAppService)
├── JustTalkTests/              macOS app unit tests (HotkeyConfig, HotkeyConflict)
├── DictationKeyboard/          iOS custom keyboard extension (Flow-Session handoff; DictationCoreBase)
├── DictationContainerApp/      iOS container app — records for the keyboard (RecordSession; DictationCoreBase)
├── docs/
│   ├── build.md                Build / run / deploy guide
│   ├── architecture.md
│   ├── api.md, flows.md, e2e-tests.md
│   └── whisperkit-failure-findings.md
├── scripts/
│   └── prereqs.sh              Shared prerequisite checks (sourced by all scripts)
├── build.sh / test.sh / run.sh / deploy-local.sh   One-command build / test / run / install
├── project.yml                 XcodeGen project spec (dependency manifest)
└── README.md
```

---

## Development Phases

| Phase | Status | Scope |
|-------|--------|-------|
| 1 — Shared foundation | Done | DictationCore package, XcodeGen spec, docs |
| 2 — macOS app | Done | Menu bar UI, Fn global hotkey, clipboard paste, GRDB telemetry |
| A — Correctness | Done | Clipboard preserve/restore, hotkey self-heal, hallucination filter, download progress |
| B0 — Pluggable contracts | Done | `SpeechTranscriber` + `TextCleanup` contracts, `{provider, model}` config |
| B — AI cleanup | Done | On-device Foundation Models cleanup (default Full) + rule-based fallback |
| C — Daily-driver ergonomics | Done | Launch at login, recording HUD, custom vocabulary, toggle mode, sounds |
| D — Permanent install | Done | `build.sh` / `run.sh` / `deploy-local.sh`, docs |
| 3 — iOS keyboard | Built (device-signing gated) — compiles for Simulator; device validation pending paid Apple Developer Program | Flow-Session container-app handoff (keyboard can't touch the mic): `justtalk://record` launch, background recording, `DictationHandoff` App-Group + Darwin bridge, Apple Speech STT, on-device AI cleanup, App Group SQLite telemetry |
| E1 — Live streaming pill + Apple provider | Done | Per-word roll-up pill (LocalAgreement-2), Apple SpeechAnalyzer provider (fast English), pill redesign (wave, gold border, frosted glass) — PRD 0008 |
| E — Optional | Future | Streaming as final output (post-stop speed win), per-app profiles, history search |
