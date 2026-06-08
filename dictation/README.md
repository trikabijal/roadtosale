# Just Talk

A Wispr Flow replacement built on WhisperKit. Press your activation key, speak, and your words appear in the frontmost app — cleaned into polished writing by an on-device LLM. Runs entirely on-device: neither audio nor text ever leaves the machine.

Two model layers, both swappable behind a contract (`{provider, model}`):
- **Speech-to-text** — `SpeechTranscriber` (WhisperKit today; Apple SpeechTranscriber next).
- **Cleanup** — `TextCleanup` (Apple Foundation Models, with a deterministic rule-based fallback).

Two shipping targets: a macOS menu bar app (JustTalk) and an iOS custom keyboard extension (DictationKeyboard + DictationContainerApp — iOS rename deferred until the paid Apple Developer account is set up). All targets share the DictationCore Swift package for recording, transcription, cleanup, and telemetry. AI cleanup is now wired into **both** the macOS app and the iOS keyboard (same contract + data pack, so iOS inherits every cleanup fix for free). The iOS keyboard compiles for the Simulator but can only be fully validated on a physical device with a paid Apple Developer account (custom keyboards don't run in the Simulator).

---

## Prerequisites

- Xcode 16 or later
- macOS 14 Sonoma or later (required to build; also the runtime for the Mac app)
- iOS 17 device (required to run the keyboard extension; Simulator does not support custom keyboards)
- [XcodeGen](https://github.com/yonaskolb/XcodeGen): `brew install xcodegen`

---

## Setup

```bash
brew install xcodegen       # one-time
cd dictation
xcodegen generate           # creates JustTalk.xcodeproj
open JustTalk.xcodeproj
```

XcodeGen resolves `Shared/` as a local Swift Package. Xcode then fetches GRDB and WhisperKit from GitHub on first open — allow the package resolution to complete before building.

### Signing & Capabilities

In Xcode, for **each of the three targets**:

1. Signing & Capabilities → Team → select your Apple Developer team.
2. For **DictationKeyboard** and **DictationContainerApp** only: add the App Groups capability and enter `group.com.trika.dictation`. This is the shared SQLite container for telemetry.

---

## macOS Usage (JustTalk)

1. Select the **JustTalk** scheme, build, and run.
2. A **setup wizard** opens on first launch with live-ticking cards — grant **Microphone** and **Accessibility**, pick your activation key, and press it once to confirm it reaches the app. Permissions are only requested when you tap a button (nothing pops up out of the blue), and the wizard auto-advances as you grant each one. Reopen it anytime from the menu bar → **Setup**.
3. The app lives in the menu bar (no Dock icon by design).
4. Hold your **activation key** (default **Fn / Globe**) to record; release to transcribe, clean, and paste. A floating HUD near the bottom of the screen shows a live mic level while you speak.
5. **Hotkey conflicts:** if another app (e.g. Wispr Flow) already owns Fn, quit it or choose a different key in the wizard — macOS can't share one key between two apps. The wizard's "press your key to test" step confirms the key actually reaches Just Talk. For Fn, also set System Settings → Keyboard → "Press 🌐 key to" → **Do Nothing**.

Settings (menu bar → Settings):
- **Recording** — activation key (Fn, right ⌘/⌥/⌃, F5/F6/F13), activation mode (hold-to-talk or tap-to-toggle), auto-paste, start/stop sounds. "Re-run setup…" reopens the wizard.
- **Startup** — launch at login.
- **Speech-to-text** — provider + model.
- **AI Cleanup** — level (Off / Light / **Full**, default) + engine (Foundation Models / rule-based).
- **Custom Vocabulary** — names/jargon that bias transcription and force spelling after cleanup.

Your clipboard is preserved: auto-paste restores whatever you had copied. First launch downloads the selected WhisperKit model (~40 MB–1 GB depending on tier); progress is shown in the menu bar item. Cleanup uses Apple Foundation Models (requires Apple Intelligence enabled); it falls back to deterministic rule-based cleanup if the model isn't ready.

### Build & install (scripts)

```bash
cd dictation
./build.sh                 # Release build into ./build
./build.sh install         # build + copy to /Applications
./run.sh                   # build + launch
# Optional, for permissions that persist across rebuilds:
DEVELOPMENT_TEAM=XXXXXXXXXX ./build.sh install
```

---

## iOS Usage (DictationContainerApp + DictationKeyboard)

1. Connect your iOS 17 device and select the **DictationContainerApp** scheme.
2. Build and install (Xcode signs automatically if you set your team above).
3. On the device: **Settings → General → Keyboard → Keyboards → Add New Keyboard → Dictation**.
4. Tap **Dictation** in the keyboard list → enable **Allow Full Access** (required for microphone).
5. Switch to the Dictation keyboard in any app (globe key), tap the mic button, speak, tap again to transcribe and insert.

First launch: WhisperKit model downloads in the background. The keyboard shows a progress indicator until the model is ready.

---

## Model Tiers

| Tier | Size | Best for |
|------|------|---------|
| Tiny | ~40 MB | iOS keyboard extension (tight memory budget) |
| Base | ~75 MB | iOS app — good balance of speed and accuracy |
| Small | ~150 MB | iOS app or Mac when latency matters |
| Large Turbo | ~800 MB | macOS — best accuracy, recommended for daily use |

---

## Project Structure

```
dictation/
├── Shared/                     DictationCore Swift Package (shared by all targets)
│   └── Sources/DictationCore/
│       ├── RecordingEngine.swift          AVAudioEngine tap + format conversion + VAD + level
│       ├── SpeechTranscriber.swift        STT contract + provider/config/factory + mock
│       ├── WhisperKitTranscriber.swift    WhisperKit impl + ModelTier + hallucination filter
│       ├── TextCleanup.swift              Cleanup contract + config + pack loader + factory
│       ├── FoundationModelsCleanup.swift  On-device LLM cleanup (Apple Foundation Models)
│       ├── RuleBasedCleanup.swift         Deterministic fallback + shared text transforms
│       ├── TelemetryStore.swift           GRDB SQLite — TranscriptRecord, WeeklyStats
│       └── Resources/
│           └── dictation-cleanup-pack.json  Cleanup data pack (canonical: voice-engine/)
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
├── DictationKeyboard/          iOS custom keyboard extension (Phase 3)
├── DictationContainerApp/      iOS container app (Phase 3)
├── docs/
│   ├── architecture.md
│   └── whisperkit-failure-findings.md
├── build.sh / run.sh           One-command build / run
├── project.yml                 XcodeGen project spec
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
| D — Permanent install | Done | `build.sh` / `run.sh`, docs |
| 3 — iOS keyboard | Code-complete, compiles for Simulator; device validation pending paid Apple Developer Program | KeyboardViewController, in-keyboard mic UI, **on-device AI cleanup (Foundation Models + fallback)**, App Group SQLite telemetry |
| E — Optional | Future | Streaming partials, per-app profiles, history search |
