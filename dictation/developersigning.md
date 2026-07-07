Shipping the DMG — full walkthrough

You do Steps 1–3 (they need your Apple ID / 2FA). Then I run the build. Do them in order.

Step 1 — Create the Developer ID certificate (in Xcode)

This cert is what makes the app open on other people's Macs. You only do this once.

1. Open Xcode.
2. Menu bar: Xcode → Settings… (or press ⌘ ,).
3. Click the Accounts tab (top of the window).
4. Look at the left list. Is bijalsanghavi@hotmail.com there?
  - No → click the + at the bottom-left → Apple ID → Continue → sign in (email, password, 2FA code).
5. Click your Apple ID in the left list. On the right you'll see your Team (something like "Bijal Sanghavi (Personal Team)" or a company name). Click it to select.
6. Click the Manage Certificates… button (bottom-right of the panel).
7. A sheet opens listing your certificates. Click the + at the bottom-left of that sheet.
8. Choose Developer ID Application.
9. Wait ~5 seconds — a new row "Developer ID Application" appears with today's date. That's it.
10. Close the sheet and Settings.

Confirm it worked — open Terminal and run (or type ! then this in our session):
security find-identity -v -p codesigning
You should now see a line like:
2) XXXXXXXX... "Developer ID Application: Your Name (TEAMID)"
The (TEAMID) in parentheses is your Team ID — note it for Step 3.

▎ If you don't see "Developer ID Application", your account may still be finishing enrollment (can take a few hours after payment). Wait and retry.

Step 2 — Create an app-specific password (in your browser)

Notarizing uploads to Apple; it logs in with a special password (not your real one).

1. Go to https://appleid.apple.com and sign in (bijalsanghavi@hotmail.com + 2FA).
2. Find the "Sign-In and Security" section → click "App-Specific Passwords".
3. Click "Generate an app-specific password" (or the +).
4. Name it notary → Create.
5. It shows a password like abcd-efgh-ijkl-mnop. Copy it now — it's shown only once. Paste it somewhere temporary.

Step 3 — Store the credential (Terminal)

This saves the login so package.sh can notarize without asking each time.

Type ! in our chat then paste this (replace the two placeholders):
xcrun notarytool store-credentials notary --apple-id bijalsanghavi@hotmail.com --team-id TEAMID --password abcd-efgh-ijkl-mnop
- TEAMID = the (TEAMID) you saw in Step 1's output.
- abcd-efgh-ijkl-mnop = the app-specific password from Step 2.

It prints "Validating your credentials…" then "Success. Credentials saved." (If macOS pops a keychain permission, click Allow.)

---
Then it's my turn

Tell me when Step 1 is done and I'll run a dry run (SKIP_NOTARIZE=1 ./package.sh) — proves build + Developer-IDation needed yet. After Step 3, I run the full ./package.sh → hands you dist-prod/Just Talk.dmg, signed +notarized + stapled, ready to send.

Quickest sanity check right now: run security find-identity -v -p codesigning (via !) and paste me the output — I'll tell you immediately whether the Developer ID cert is in place and what your Team ID is.

---
And — the hotkey change needs your test (separate, when you have a sec)

On branch feat/drop-input-monitoring, running now. To verify Input Monitoring is truly gone:
1. System Settings → Privacy & Security → Input Monitoring → if "Just Talk" is listed, toggle it OFF (or remove it).
2. Use your Fn hotkey → dictation should still start (now on Accessibility alone), and no emoji picker.
3. Dictate while the machine is busy → keyboard should stay responsive (no freeze).

If all three hold, we've matched Wispr at 2 permissions. If the hotkey dies with Input Monitoring off, tell me and I'll investigate.
