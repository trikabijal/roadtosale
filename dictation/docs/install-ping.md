# Install ping → Google Sheet (know who installed)

The onboarding wizard's final "stay in touch" step collects **name / email / phone** (all
optional, explicitly typed by the user) and POSTs them to a URL so you get an install signal. The
user's **voice/audio never goes here** — only the contact details they chose to enter.

- The app reads the endpoint from the Info.plist key **`JustTalkInstallPingURL`** (in `project.yml`).
- Empty (default) → the ping is a no-op. Fill it in to start collecting.

The cheapest zero-backend option is a **Google Apps Script web app that appends a row to a Sheet**.
Setup takes ~2 minutes.

## One-time setup

1. Create a new Google Sheet (e.g. "Just Talk installs"). Note the tab name (default `Sheet1`).
2. **Extensions → Apps Script**. Delete the sample, paste the script below, Save.
3. **Deploy → New deployment → Web app.**
   - *Execute as:* **Me**
   - *Who has access:* **Anyone**
   - Deploy, authorize, and copy the **Web app URL** (ends in `/exec`).
4. Put that URL in `project.yml` → `JustTalkInstallPingURL`, then rebuild + re-notarize
   (`./package.sh`). Done — each install adds a row.

## The script

```javascript
function doPost(e) {
  var lock = LockService.getScriptLock();
  lock.waitLock(20000);
  try {
    var sheet = SpreadsheetApp.getActiveSpreadsheet().getSheetByName('Sheet1');
    if (sheet.getLastRow() === 0) {
      sheet.appendRow(['Timestamp', 'Name', 'Email', 'Phone', 'Version', 'Platform']);
    }
    var d = {};
    try { d = JSON.parse(e.postData.contents || '{}'); } catch (err) { d = {}; }
    sheet.appendRow([
      new Date(),
      d.name || '', d.email || '', d.phone || '',
      d.version || '', d.platform || ''
    ]);
    return ContentService.createTextOutput(JSON.stringify({ ok: true }))
      .setMimeType(ContentService.MimeType.JSON);
  } finally {
    lock.releaseLock();
  }
}
```

## What the app sends

`POST` with `Content-Type: application/json` and body:

```json
{ "name": "...", "email": "...", "phone": "...", "version": "1.0.0", "platform": "mac" }
```

Fire-and-forget: a failed ping is logged to Console (subsystem `com.trika.dictation`) and never
blocks the user or setup.

## Alternatives

Any HTTPS endpoint that accepts that JSON works — Formspree, a Netlify function, or the future
Road-to-Sale backend. Swap the URL; no app change needed.
