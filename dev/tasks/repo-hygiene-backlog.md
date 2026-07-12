# Repo hygiene backlog (post Just-Talk split)

Housekeeping after Just Talk (native dictation) was split out to its own repo
`trikabijal/justtalk` on 2026-07-12. None of these are urgent; batch them into one cleanup PR.

## 1. Rename `just-talk-app/` — name collides with the split-out product

`just-talk-app/` is **not** the Just Talk dictation product (that's now `trikabijal/justtalk`). It's a
**React-Native (Expo) harness for RTS's `voice-engine`** — `JustTalkScreen.tsx` + a native
`native/VoiceModule/RtsVoiceModule.{h,m}` bridging `voice-engine`'s AppleSpeechTranscriberStrategy;
the on-device proof that the voice-engine contract works on a real iPhone.

- **Rename to** something unambiguous, e.g. `voice-engine-harness/` or `rts-voice-demo/`.
- Update: `package.json` `name`, any workspace refs, `build.sh`, docs mentioning it.
- Reason: three "talk" things (the product, this harness, `voice-engine`) → "which Just Talk?" confusion.

## 2. Remove the split-out `dictation/` from this repo

Just Talk now lives in `trikabijal/justtalk` (full history carried). `roadtosale/dictation/` is a stale
duplicate.

- Remove `dictation/` and the top-level `.github/workflows/release-mac.yml` (its CI moved to the new repo).
- Do it as a PR (deletion — hard to reverse); confirm the new repo is fully stood up first.

## 3. (Optional) Shared cleanup contract → real dependency

Just Talk and RTS currently **mirror** the `voice-engine` cleanup contract/packs in native code rather
than sharing a live dependency. If cross-product sharing becomes worth it, publish `voice-engine`'s
contract + `cleanup-packs/` as a versioned package both repos consume. Not needed today.
