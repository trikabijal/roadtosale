# Just Talk — Build, Run, Test & Local Install

> **Module docs:** [architecture.md](architecture.md) · [api.md](api.md) · [flows.md](flows.md) · [e2e-tests.md](e2e-tests.md)

This guide assumes no prior knowledge of the project. Clone the repo on a Mac, install the
prerequisites, and the four scripts in `dictation/` build, test, run, and locally install the
app.

---

## What gets built

| Product line | Scheme(s) | Status | Bundle ID |
|--------------|-----------|--------|-----------|
| **macOS menu-bar app** (`JustTalk`) | `JustTalk`, `JustTalkTests` | **Shipping / daily driver** | `com.trika.justtalk.mac` |
| **iOS keyboard** (paused) | `DictationContainerApp`, `DictationKeyboard` | **PAUSED, secondary** | `com.trika.justtalk.ios` (+ `.keyboard`) |

All product logic lives in **DictationCore**, a local SwiftPM package (`Shared/Package.swift`)
that every target consumes. Its third-party deps — **GRDB** and **WhisperKit** — are resolved
transitively through that package the first time you build (needs network on first run).

The Xcode project (`JustTalk.xcodeproj`) is **generated** from `project.yml` by
[XcodeGen](https://github.com/yonaskolb/XcodeGen). Do not edit the `.xcodeproj` by hand —
edit `project.yml` and regenerate. Every script regenerates it before building.

---

## Prerequisites (fresh Mac)

1. **macOS** — Apple Silicon recommended; deployment target is macOS 14.0. (The on-device
   LLM cleanup path the product ships against assumes a recent macOS — see the
   architecture doc for runtime requirements.)
2. **Xcode 16+** — install from the App Store, then point the toolchain at it:
   ```sh
   sudo xcode-select -s /Applications/Xcode.app/Contents/Developer
   ```
   This provides `xcodebuild` and `swift`.
3. **Homebrew** — https://brew.sh
4. **XcodeGen**:
   ```sh
   brew install xcodegen
   ```

Each script checks for these and prints the exact fix (e.g. `brew install xcodegen`) if one
is missing.

---

## The four scripts (run from `dictation/`)

> Make them executable once after cloning: `chmod +x *.sh`

### `./build.sh` — build

| Command | Result |
|---------|--------|
| `./build.sh` (or `./build.sh macos`) | Build the macOS app (Release) into `./build` |
| `./build.sh install` | Build, then copy the app into `/Applications` |
| `./build.sh ios` | Build the **paused** iOS container app for the Simulator |

Steps: verifies macOS + Xcode + xcodegen → `xcodegen generate` → `xcodebuild build`.

Environment overrides:
- `CONFIG=Debug ./build.sh` — build the Debug configuration instead of Release.
- `DEVELOPMENT_TEAM=XXXXXXXXXX ./build.sh` — sign with your team for **stable** signing so
  macOS keeps Mic / Accessibility / Input-Monitoring grants across rebuilds. `DEVELOPMENT_TEAM=""`
  forces ad-hoc signing. If unset, the signing identity pinned in `project.yml` is used.

### `./run.sh` — build + launch

Builds the macOS app and `open`s the resulting `.app`. The app lives in the **menu bar**
(no Dock icon). Hold your activation key to talk.

### `./test.sh` — test

| Command | Runs |
|---------|------|
| `./test.sh` (or `all`) | DictationCore unit tests **and** JustTalkTests |
| `./test.sh core` | Only `swift test` in `Shared/` (fast; covers cleanup / pipeline / store / telemetry) |
| `./test.sh app` | Only `xcodebuild test` on the `JustTalkTests` scheme (hotkey config) |

Exits non-zero if any suite fails.

### `./deploy-local.sh` — local install (daily driver)

The literal "local deploy": build Release, then install `Just Talk.app` so you can use it
every day on this Mac.

| Command | Installs into |
|---------|---------------|
| `./deploy-local.sh` | `/Applications` (may prompt for your admin password) |
| `./deploy-local.sh --user` | `~/Applications` (no admin rights needed) |

---

## Where the `.app` lands

- macOS build product: `dictation/build/Build/Products/Release/Just Talk.app`
  (or `…/Debug/…` when `CONFIG=Debug`).
- iOS build product: `dictation/build-ios/…`.
- After `deploy-local.sh`: `/Applications/Just Talk.app` (or `~/Applications/…`).

`build/`, `build-ios/`, and `DerivedData/` are gitignored.

---

## macOS Gatekeeper / "unsigned app" caveat

The local build is signed with a **development** (or ad-hoc) identity, **not** a notarized
Developer ID. On the Mac where it was built it launches normally. If you copy the `.app` to
**another** Mac, Gatekeeper blocks it ("Just Talk can't be opened"). To bypass:

- Right-click the app → **Open** the first time, **or**
- Strip the quarantine flag:
  ```sh
  xattr -dr com.apple.quarantine "/Applications/Just Talk.app"
  ```

Distributing to other machines properly requires a **paid Apple Developer account**, a
**Developer ID** certificate, and **notarization** (a notarized signed DMG). See the
"Just Talk distribution" notes for the DMG path.

First launch on any Mac: grant **Microphone** + **Accessibility** in
**System Settings → Privacy & Security**. Accessibility is what lets the app paste
transcribed text into the frontmost app and suppresses the emoji picker on the Fn key.

---

## iOS line (paused — read before building `ios`)

The iOS keyboard is **paused and secondary**:

- Custom keyboards **cannot run in the Simulator** and need device provisioning, which needs
  a paid Apple Developer account.
- On the current branch, `DictationKeyboard/*.swift` are **diagnostic stubs** (the real
  implementation lives in git history, commit `1554186`), so the extension target may not
  build meaningfully. `./build.sh ios` builds the **container app** target and prints a
  warning. Treat the iOS path as optional.

---

## Troubleshooting

| Symptom | Fix |
|---------|-----|
| `xcodegen not found` | `brew install xcodegen` |
| `xcodebuild not found` | Install Xcode, then `sudo xcode-select -s /Applications/Xcode.app/Contents/Developer` |
| First build hangs on dependency resolution | First build downloads GRDB + WhisperKit — needs network; let it finish. |
| macOS re-prompts for Mic/Accessibility every launch | Ad-hoc signing changes identity each build. Build with a stable `DEVELOPMENT_TEAM` (see `build.sh`). |
| `Build product not found` | The `xcodebuild` step failed earlier — scroll up for the real compiler/signing error. |
| Code-signing errors with the pinned identity | The identity in `project.yml` is machine-specific. Pass your own `DEVELOPMENT_TEAM=…`, or `DEVELOPMENT_TEAM=""` for ad-hoc. |
| "Just Talk can't be opened" (copied to another Mac) | Gatekeeper — right-click → Open, or `xattr -dr com.apple.quarantine`. See the caveat above. |

---

## Note

> A full build requires **macOS + Xcode + XcodeGen**. The scripts are syntax-checked, but
> compiling/signing happens only on a real Mac with the toolchain installed.
