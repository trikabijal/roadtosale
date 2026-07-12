# Just Talk — Design System (macOS + iOS)

One brand, two builds. This is the spec both platforms follow. The **cross-platform** values live once in
`Shared/Sources/DictationCore/DesignTokens.swift` (+ `BrandPalette.swift`); each UI target wraps the raw
values (RGB tuples / `Double`) in its own `Color`/`CGFloat`. Everything **platform-specific** is called
out below so a divergence is a *decision*, not a drift.

## Shared tokens — the single source of truth

`DesignTokens` (raw values, no SwiftUI/AppKit):

| Token | Value | Notes |
|---|---|---|
| `Color.accent` / `accentDeep` | gold `(0.86,0.62,0.20)` / `(0.70,0.48,0.12)` | The brand accent (from `BrandPalette`). |
| `Color.success` | `(0.16,0.62,0.36)` | Was three different greens. Pair with a check/seal icon. |
| `Color.warning` | `(1.00,0.62,0.04)` | Orange. |
| `Color.recording` | `(0.90,0.27,0.23)` | The recording red / mic dot. Was two reds. |
| `Color.ink` / `muted` | `(0.09,0.09,0.11)` / `(0.42,0.42,0.46)` | Neutral text on light surfaces. |
| `Radius.window/card/control/chip` | `16 / 16 / 10 / 8` | One card radius both platforms (was mac 12 vs iOS 16/22). |
| `Space.xs…xxl` | `4/8/12/16/20/24` | Spacing scale (iOS had none). |
| `Wave.barCount/barSpacing` | `27 / 5` | Discrete waveform bars. |
| `Wave.fullScaleRMS/floor` | `0.18 / 0.12` | **Shared loudness response** — a given RMS reads equally loud on both waveforms (was 0.2 / 0.167 / 0.12). |

**Consumers:** macOS `JustTalk/Theme.swift` (accent, semantic, radius, spacing), iOS `JTBrand`
(`OnboardingFlow.swift`) + `ContentView`, both waveforms (`RecordingHUD.WaveMeter`,
`KeyboardView.Waveform`), and the vocabulary/junk-phrase/limit/locale constants documented in
`ios-dictation-architecture.md` and the consistency audit.

## Legitimately platform-specific (keep divergent)

These are HIG-correct per-platform choices, NOT drift:

- **Grounds/theme.** macOS chrome (menu bar, Settings, HUD) is **dark-first** (`Theme.Palette` grounds) —
  it floats over arbitrary desktops and reads best dark. iOS uses **system semantic** backgrounds
  (adaptive) and a warm **light** onboarding (`JTBrand.paper`). Both should still pull *accent + semantic*
  from the shared tokens; only the grounds differ.
- **The floating pill.** The macOS `GoldShimmerBorder` + `.ultraThinMaterial` glass pill is an
  always-on-top HUD affordance with no iOS equivalent (iOS surfaces dictation in the keyboard). Keep.
- **Native window/keyboard framing.** macOS titled onboarding window + traffic-light dots; iOS full-bleed
  paged onboarding + keyboard surface. Keep.

## Open decisions (tracked)

- **Brand accent on macOS chrome.** macOS `Theme.Palette.accent` shipped **pink `#FF2E7E`** while
  everything else is gold. Staged to gold pending a product call (see the accent swatch). If gold is
  confirmed, macOS chrome matches the pill + iOS. *(Decision pending.)*
- **Serif display numerals.** iOS Home renders big **serif** stat numbers (Wispr-style); macOS shows
  stats in **mono**. Decide if serif numerals are a brand signature (mirror on macOS) or iOS-only.
- **Onboarding palette.** macOS onboarding uses a **multi-colour per-step** accent (gold/red/blue/green);
  iOS is **monochrome** ink+gold. Same wizard skeleton, opposite visual language — unify or keep.

## Rule

If a value must look the same on both platforms, it lives in `DesignTokens`/`BrandPalette` and both sides
read it. No ad-hoc `Color(red:…)` / magic radius for shared concepts. Platform-specific values stay in
`Theme` (macOS) or `JTBrand`/views (iOS), and are listed above so the divergence is intentional.
