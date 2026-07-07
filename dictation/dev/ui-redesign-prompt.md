# JustTalk — UI redesign prompt

Paste the block below into your design tool, attach the current-UI screenshots, and run it.

---

Redesign the UI for "JustTalk," an on-device macOS dictation app (like Wispr Flow, but
privacy-first: all speech-to-text and AI cleanup run locally). It's a MENU-BAR app — no
dock icon, no main window. You hold/tap a key, speak, and the cleaned text is pasted into
whatever app you're in. Dark, native macOS feel. Users are fast dictators on Apple Silicon,
macOS 26. I want a cohesive visual identity across all surfaces, with the floating HUD as
the signature moment. Screenshots of the current UI are attached.

Design ALL of these surfaces, and every state listed:

1) MENU-BAR GLYPH (the small icon in the macOS menu bar). Three states, must read at ~16pt
   monochrome (it's a template image that adapts to light/dark menu bars):
   - Idle: a mic.
   - Recording: same mark, red/active.
   - Transcribing: a waveform, orange.

2) FLOATING HUD — a small capsule "pill," bottom-center of screen, frosted/translucent
   material, ~360×60, always-on-top, non-activating (never steals focus), draggable.
   This is the hero. Design all states:
   - Recording: mic icon (red) + a live audio level meter (currently 7 bars) + a single line
     of live partial transcript (truncates so newest words show), or "Listening…".
   - Low input: mic-slash icon (orange) + "Speak up — I can barely hear you".
   - Processing: waveform (orange) + "Transcribing…" / "Finishing…" + idle meter.
   - Inserted (success, ~5s): green checkmark + "Inserted" + a small "Mark wrong" button.
   - Failed: yellow warning triangle + short error + a "Retry" button + a dismiss ✕.

3) MENU POPOVER (opens when you click the menu-bar glyph), ~320px wide, dark. Top to bottom:
   - Status line: a colored status dot (green idle / red recording / orange transcribing)
     + status text ("Ready — hold Fn to talk") + a loading spinner while the model loads.
   - Optional warning banner (orange triangle + one line of actionable text).
   - Recent transcripts (up to 5): each row = 2 lines of transcript text, a timestamp,
     a confidence %, a copy button, and (top row only) a re-transcribe ↻ button. Corrected
     rows are dimmed with a "✗ corrected" tag.
   - Stats line: "This week: 87 transcripts · Avg 72% confidence · 1% corrected".
   - Action row: Settings · History · Setup (accent links) … Quit.

4) HISTORY WINDOW (480×540): a search field ("Search transcripts…") over a scrollable list
   of past transcripts; each row copies back to the clipboard. Empty state: "No transcripts yet".

5) SETTINGS WINDOW (420×560): a grouped form —
   - Dictation: activation-key picker; activation-mode picker (Hold to talk / Sticky /
     Hold + double-tap to lock); Auto-paste toggle; Play sounds toggle; Streaming (beta) toggle.
   - Startup: Launch at login.
   - Voice model: provider picker; model-tier picker.
   - Cleanup: level (Off / Light / Full); engine (Apple Foundation Models / Rule-based).

6) APP ICON — a distinctive mark for the .app (all macOS icon sizes).

CONSTRAINTS:
- Native macOS: SF Pro type, system materials/vibrancy, rounded geometry, standard controls.
- Dark-first; the menu-bar glyph must also work as a monochrome template on light menu bars.
- State must be legible at a glance and NOT by color alone — pair each state with a distinct
  icon/shape (color-blind safe). Semantic colors (recording red, warning orange/yellow,
  success green) are separate from the brand accent.
- The HUD must stay calm and unobtrusive — it floats over other apps while you dictate.
- Accessibility: high contrast, legible at small sizes, respect reduced motion.

DELIVERABLES: mockups for every surface and state above; the 3 menu-bar glyph states; the
app icon; and a small token set — palette (incl. semantic state colors), type scale, spacing,
corner radii, and the HUD material. Give it a real point of view, not a generic macOS template.

---

## Add-ons (optional)
- Tell it your brand accent (current build shows a pink/magenta accent — keep or change).
- Ask for light-mode variants of the popover + windows (macOS users flip appearance).

## Screenshots to attach
menu-bar glyph (idle/recording/transcribing) · HUD (recording, inserted, finishing) ·
popover · history window · settings window · app icon.
