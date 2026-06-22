# Road to Sale App — Build, Test & Run (Local)

Everything you need to go from a fresh clone to a running app on your machine.
This is **local-only** — no CI, no cloud, no app-store/TestFlight steps.

The app is an **Expo / React Native (TypeScript)** project on **Expo SDK 56**
(RN 0.85, React 19) with a **custom native voice module** wired via an Expo
config plugin (`plugins/withVoiceModule.ts`).

> Expo SDK 56 caveat: Expo CLI commands change between major versions. This doc
> uses the SDK 56 commands (`npx expo start`, `npx expo prebuild`,
> `npx expo run:ios`, `npx expo run:android`). Always confirm against the
> versioned docs at <https://docs.expo.dev/versions/v56.0.0/> (and the CLI
> reference at <https://docs.expo.dev/more/expo-cli/>) before changing the
> scripts. Do **not** assume older global `expo-cli` conventions.

---

## 1. Prerequisites

### Always required

| Tool | Version | Notes |
|------|---------|-------|
| Node.js | **>= 20** | `build.sh` enforces this. Tested on Node 25. |
| npm | bundled with Node | Used for `npm ci`. |
| The full monorepo | — | The catalog build reads YAML from the sibling `../vehicle-feature-catalog/data/`. Clone the whole repo, not just this folder. |

### Required only for native device/simulator runs (`deploy-local.sh`)

| Platform | Needs |
|----------|-------|
| **iOS** | macOS + **Xcode** (full app, not just Command Line Tools) + an iOS Simulator. CocoaPods/Ruby for pod install (Expo runs it automatically). A provisioned iPhone if you run on a physical `--device`. |
| **Android** | **Android SDK** (Android Studio) with `ANDROID_HOME` / `ANDROID_SDK_ROOT` exported, a JDK, and a running emulator (AVD) or a USB-debugging device. |

You do **not** need Xcode or the Android SDK just to build the JS/TS bundle or
run the tests.

---

## 2. The four scripts

All scripts live in `road-to-sale-app/`, use `set -euo pipefail`, can be run
from anywhere (they `cd` to their own dir), and print a usage header.

### `./build.sh [local|dev|prod]` — JS/TS bundle prep

Default build. **Does not** generate native projects (no prebuild) — the
generated `ios/`/`android/` trees are gitignored, so the default build keeps
them out of your working tree. Steps:

1. Check prerequisites (Node >= 20, npm; warns if the sibling catalog data is missing).
2. Install dependencies — `npm ci` (falls back to `npm install` if there is no lockfile).
3. Typecheck — `npx tsc --noEmit`.
4. Compile the cue pack — `src/cue-packs/road-to-sale-v1.yaml` → `…/road-to-sale-v1.json`.
5. Compile the vehicle catalog — `../vehicle-feature-catalog/data/*.yaml` → `src/catalog/bundle.json`.

```bash
./build.sh          # local (default)
./build.sh prod     # env label is informational; build steps are identical
```

### `./test.sh [jest args…]` — Jest suite

Runs the full suite via the `jest-expo` preset (configured in `package.json`).
~106 tests across 7 files (catalog, CRM, voice, DB, API, session engines).
Exits non-zero on any failure.

```bash
./test.sh                 # full suite once
./test.sh --watch         # re-run on save
./test.sh --coverage      # coverage report
./test.sh src/api         # only tests under src/api/
```

### `./run.sh [ios|android|web]` — Expo dev server (default `ios`)

Starts Metro / the Expo dev server and opens the app on a simulator/emulator.
This serves the JS bundle to an **already-installed** dev build — it does not
compile native code. Use it for day-to-day JS/TS iteration.

```bash
./run.sh ios
./run.sh android
./run.sh web
```

> **Expo Go will not work** for the voice features. This app has a custom native
> module, and Expo Go cannot load custom native modules. Install a **dev build**
> first with `./deploy-local.sh`, then iterate with `./run.sh`.

### `./deploy-local.sh [ios|android] [extra expo run:* args…]` — build + install + run natively

"Local deploy" = compile the native app and install + launch it on a device or
simulator on **this** machine. Not an app-store deploy. Steps:

1. Check prerequisites (Node, node_modules, and the platform toolchain).
2. Run `npx expo prebuild --platform <ios|android>` **only if** the native
   project is missing. The voice config plugin wires the native module during
   prebuild.
3. Build + install + launch via `npx expo run:ios` / `npx expo run:android`.

Extra args pass straight through:

```bash
./deploy-local.sh ios                          # default simulator
./deploy-local.sh ios --device                 # pick a physical iPhone
./deploy-local.sh ios --configuration Release
./deploy-local.sh android                      # default emulator/device
./deploy-local.sh android --variant release
```

After the first native install, iterate with `./run.sh <platform>` (faster — no
native recompile).

---

## 3. Typical workflows

**First clone → tests green:**

```bash
cd road-to-sale-app
./build.sh        # installs deps, typechecks, compiles cue pack + catalog
./test.sh         # 106 tests should pass
```

**First clone → running on a simulator (iOS):**

```bash
./build.sh
./deploy-local.sh ios     # prebuild + compile + install + launch
# later, JS-only iteration:
./run.sh ios
```

---

## 4. Where artifacts land

| Artifact | Path | Tracked? |
|----------|------|----------|
| Compiled cue pack | `src/cue-packs/road-to-sale-v1.json` | yes |
| Vehicle catalog bundle | `src/catalog/bundle.json` | build artifact (gitignored) |
| Generated iOS project | `ios/` | gitignored (except hand-authored `ios/VoiceModule/`) |
| Generated Android project | `android/` | gitignored (except `android/.../voice/`) |
| Coverage report | `coverage/` (with `--coverage`) | gitignored |

The hand-authored native module sources (`ios/VoiceModule/`,
`android/app/src/main/kotlin/com/trika/roadtosale/voice/`) are **kept** in git;
`expo prebuild` fills in the rest of the native tree around them.

---

## 5. Troubleshooting

- **`build.sh` fails at step 5 (catalog)** — the sibling `../vehicle-feature-catalog/data/`
  is missing. Clone the full monorepo; this app reads catalog YAML from there.
- **`MODULE_TYPELESS_PACKAGE_JSON` warning during the cue-pack step** — harmless.
  `build-cue-pack.ts` is run with `module: esnext` (it references `import.meta`
  behind a runtime guard), so Node logs a one-line reparse warning. The JSON is
  still written.
- **Cue-pack step errors with TS1343 (`import.meta` not allowed)** — the script
  must be invoked with an esnext module setting (build.sh already does this). Do
  not point it at `src/catalog/tsconfig.build.json` (that config is CommonJS and
  scoped to the catalog).
- **Voice/audio doesn't work in the running app** — you're probably on Expo Go.
  Build a dev build with `./deploy-local.sh` instead.
- **`expo run:ios` can't find a simulator / `xcodebuild` missing** — install the
  full Xcode app and run `sudo xcode-select -s /Applications/Xcode.app/Contents/Developer`.
- **`expo run:android` can't find the SDK** — export `ANDROID_HOME` (or
  `ANDROID_SDK_ROOT`) and make sure an emulator is running or a device is
  connected (`adb devices`).
- **A command behaves unexpectedly** — Expo CLI commands are version-specific.
  Re-check the SDK 56 docs (<https://docs.expo.dev/versions/v56.0.0/>) before
  editing the scripts.
