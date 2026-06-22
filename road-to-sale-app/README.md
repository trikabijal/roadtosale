# Road to Sale App

An Expo / React Native (SDK 56, TypeScript) dealership sales app — NADA-aligned
sales coaching with on-device voice capture and a vehicle-feature catalog. This
module is part of the `roadtosale` monorepo and reads the vehicle catalog from
the sibling `vehicle-feature-catalog/` module.

## Prerequisites

| Requirement | Why | Install |
| --- | --- | --- |
| Node.js **>= 20** + npm | Build, typecheck, and test the JS/TS app | https://nodejs.org (npm ships with Node) |
| Full monorepo checkout | The build compiles the vehicle catalog from the sibling `../vehicle-feature-catalog/data/` — cloning only this folder will not work | `git clone` the whole repo, not just `road-to-sale-app/` |

**Optional — only for running on a device or simulator:**

| Platform | Requirements |
| --- | --- |
| iOS | macOS + Xcode (full app) + an iOS Simulator + CocoaPods (`sudo gem install cocoapods`) |
| Android | Android SDK (`ANDROID_HOME`/`ANDROID_SDK_ROOT` set) + a JDK (17) + an emulator (AVD) or a connected device |

The scripts check every prerequisite up front and fail loudly with an install
hint if one is missing — they never deep-fail halfway through.

## Quickstart

After cloning the **full monorepo**, from `road-to-sale-app/`:

```bash
./build.sh        # install deps, typecheck, compile the cue pack + vehicle catalog
./test.sh         # run the Jest suite — expect: 106 tests pass, 7 suites
```

Then, to develop or run on a device/simulator:

```bash
./run.sh [ios|android|web]        # start the Expo dev server (default: ios)
./deploy-local.sh [ios|android]   # compile + install + launch a native dev build
```

`build.sh` accepts an env arg (`./build.sh [local|dev|prod]`, default `local`).
It does JS/TS prep only — it does **not** run `expo prebuild`. The native build
happens in `deploy-local.sh`.

### SDK 56 caveat — use a dev build, not Expo Go

This app ships a **custom native voice module** (`plugins/withVoiceModule.ts`).
**Expo Go cannot load custom native modules**, so the voice/audio features will
not work under Expo Go. Build a native **dev build** once with
`./deploy-local.sh [ios|android]`, then iterate on JS/TS changes with `./run.sh`.

## Documentation

- [docs/build.md](docs/build.md) — build pipeline and artifacts
- [docs/architecture.md](docs/architecture.md) — architecture overview
