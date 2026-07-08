# Mac build CI → website download button

Every merge into `development` that touches `dictation/**` rebuilds, signs, notarizes, and publishes
the macOS DMG to a **fixed GitHub Release** (`mac-latest`). The website download button links to that
release's stable asset URL — no manual upload, ever.

**Workflow:** `.github/workflows/release-mac.yml`
**Reuses:** `dictation/package.sh` (build → Developer-ID sign → notarize → staple → DMG), unchanged.

---

## The download URL (wire this to the website button)

```
https://github.com/trikabijal/roadtosale/releases/download/mac-latest/JustTalk.dmg
```

Stable forever: the workflow always republishes the `mac-latest` tag with the asset named
`JustTalk.dmg`. Point the `/just-talk` page's download button `href` at this URL.

---

## One-time setup: repo secrets

Add these under **GitHub ▸ repo Settings ▸ Secrets and variables ▸ Actions ▸ New repository secret**
(or via the `gh` commands below):

| Secret | Value |
|---|---|
| `MACOS_CERTIFICATE_P12` | base64 of the exported **Developer ID Application** `.p12` (see below) |
| `MACOS_CERTIFICATE_PWD` | the password you set when exporting the `.p12` |
| `MACOS_KEYCHAIN_PWD` | any string (temp keychain password on the runner) |
| `APPLE_ID` | `bijalsanghavi@hotmail.com` |
| `APPLE_TEAM_ID` | `BMRZ94XB47` |
| `APPLE_APP_PWD` | the app-specific password (same value as `dictation/.env` `APPLE_APP_PWD`) |

### Export the Developer ID certificate as base64

The signing identity is `Developer ID Application: Bijal Sanghavi (BMRZ94XB47)`. Export it **with its
private key**:

1. **Keychain Access** ▸ login keychain ▸ **My Certificates** ▸ right-click *Developer ID Application:
   Bijal Sanghavi (BMRZ94XB47)* ▸ **Export…** ▸ save `devid.p12`, set a password (→ `MACOS_CERTIFICATE_PWD`).
2. Base64 it and copy:
   ```bash
   base64 -i devid.p12 | pbcopy   # paste into the MACOS_CERTIFICATE_P12 secret
   ```

### Set the non-cert secrets from the terminal

```bash
cd dictation
gh secret set APPLE_ID        -b 'bijalsanghavi@hotmail.com'
gh secret set APPLE_TEAM_ID   -b 'BMRZ94XB47'
gh secret set MACOS_KEYCHAIN_PWD -b "$(openssl rand -hex 16)"
gh secret set APPLE_APP_PWD   -b "$(grep '^APPLE_APP_PWD=' .env | cut -d= -f2-)"
# cert secrets (after exporting devid.p12):
gh secret set MACOS_CERTIFICATE_PWD  -b '<the p12 password you chose>'
base64 -i devid.p12 | gh secret set MACOS_CERTIFICATE_P12
```

---

## Runner / SDK note

The app uses **macOS 26 APIs** (SpeechAnalyzer, Foundation Models) behind `@available` guards, so the
runner needs the **macOS 26 SDK (Xcode 26)**. The workflow runs on `macos-15` and selects the newest
installed Xcode. If the first run fails at compile with "no such module"/unavailable-symbol errors,
the runner image lacks Xcode 26 — switch `runs-on:` to `macos-26` in the workflow.

## Trigger / re-run

- **Automatic:** any push/merge to `development` touching `dictation/**`.
- **Manual:** Actions ▸ *Release Mac (Just Talk)* ▸ **Run workflow**.
