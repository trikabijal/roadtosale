# Building & Running (local)

Everything in this repo builds and runs **locally** — there is no CI server and no cloud deploy. Each sub-engine has its own scripts; two root scripts run them all.

## One-command, whole repo

```bash
./build-all.sh    # build every module (in dependency order)
./test-all.sh     # run every module's tests
```

Both print a per-module pass/fail summary and keep going if one module fails, so a missing toolchain (e.g. no Xcode on Linux) doesn't hide the rest.

## Per-module scripts

Every module follows the same contract. Run them from inside the module folder.

| Script | Does |
|--------|------|
| `./build.sh` | Check prerequisites, install dependencies, compile/build |
| `./test.sh` | Run the module's test suite |
| `./run.sh` | Run the module locally (dev) |
| `./deploy-local.sh` | Build + install/launch the artifact on this machine |

Each module's `docs/build.md` has the detail (prerequisites, where artifacts land, troubleshooting):

- **vehicle-feature-catalog** — Python + TS catalog package. [`vehicle-feature-catalog/docs/build.md`](../vehicle-feature-catalog/docs/build.md)
- **voice-engine** — Python lab + TS skeleton + native STT CLIs (native gated behind `./build.sh --with-native`, macOS). [`voice-engine/docs/build.md`](../voice-engine/docs/build.md)
- **road-to-sale-app** — Expo / React-Native app (SDK 56). [`road-to-sale-app/docs/build.md`](../road-to-sale-app/docs/build.md)
- **dictation** — macOS "Just Talk" app (Xcode + xcodegen); `deploy-local.sh` installs `Just Talk.app` to `/Applications`. [`dictation/docs/build.md`](../dictation/docs/build.md)

## Prerequisites at a glance

- **Always:** Python ≥ 3.11, Node ≥ 20 + npm, and the **full monorepo** checked out (voice-engine and road-to-sale-app read from `../vehicle-feature-catalog`).
- **voice-engine native CLIs (optional):** macOS + Swift/Xcode (Apple/WhisperKit) and JDK 17 (sherpa-onnx uses the bundled `./gradlew`).
- **road-to-sale-app native runs (optional):** iOS → macOS + Xcode + Simulator; Android → Android SDK + emulator.
- **dictation:** macOS 14+ (Apple Silicon recommended) + Xcode 16+ + `brew install xcodegen`.

Build and test of the JS/TS and Python parts need none of the native toolchains.

See [`architecture.md`](./architecture.md) for what each module is and how they fit together.
