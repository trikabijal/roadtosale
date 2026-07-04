# Distributing JustTalk

JustTalk can't be sandboxed — it needs **Input Monitoring + Accessibility** for the global Fn
hotkey and paste. That rules out the App Store and TestFlight. The only path Gatekeeper trusts on
other people's Macs is a **Developer ID–signed, notarized DMG**. `./package.sh` does the whole thing.

## One-time setup (on the build machine)

1. **Apple Developer Program** membership ($99/yr) — required for a Developer ID certificate.
2. **Developer ID Application certificate**: Xcode ▸ Settings ▸ Accounts ▸ Manage Certificates ▸
   `+` ▸ *Developer ID Application*. Verify with `security find-identity -v -p codesigning`.
3. **Notarization credential** (stored once in the keychain):
   ```
   xcrun notarytool store-credentials notary \
     --apple-id you@example.com --team-id TEAMID --password <app-specific-password>
   ```
   App-specific password: appleid.apple.com ▸ Sign-In & Security ▸ App-Specific Passwords.

## Build the DMG

```
cd dictation
./package.sh                 # build → sign → notarize → staple → DMG  (output: dist-prod/JustTalk.dmg)
SKIP_NOTARIZE=1 ./package.sh # local smoke test only — signed, NOT distributable
```

Send `dist-prod/JustTalk.dmg`.

## Recipient requirements

- **Apple Silicon** Mac (M-series).
- **macOS 26** with **Apple Intelligence enabled** — the STT and cleanup use on-device models
  that need it. Without it, cleanup falls back to rule-based; STT needs the WhisperKit model.
- **Network on first launch** — the ~150 MB `small.en` model downloads on first run (cached after).
- On first launch, grant **Microphone**, **Input Monitoring**, and **Accessibility** when prompted.

## Notes

- Package from a stable commit (merge the beta branch to `main` first, or tag a release).
- The DMG is signed and notarized (and stapled), so it opens without the "unidentified developer"
  warning and validates offline.
- Version/build number live in `project.yml`; bump before each release.
