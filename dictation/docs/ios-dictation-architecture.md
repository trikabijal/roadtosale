# iOS dictation architecture — Just Talk keyboard ⇄ app

How the Just Talk **keyboard** and **container app** cooperate to dictate on iOS. This is the
authoritative model; the code is expected to match the swim-lane below. A rendered visual version
lives alongside at [`ios-dictation-architecture.html`](./ios-dictation-architecture.html).

---

## The hard constraints (these dictate the whole design)

1. A **keyboard extension cannot access the microphone** (iOS sandbox). Only the container app can record.
2. Keyboard and app are **separate processes / sandboxes** — no shared memory. They coordinate only via
   an **App Group** (shared `UserDefaults`) and **Darwin notifications**.
3. iOS **destroys and recreates the keyboard extension** when the user leaves and returns to the host
   app. The keyboard has **no durable in-memory state**.
4. iOS **suspends/kills backgrounded apps**, and a keyboard **cannot launch an app into the background**
   (only foreground, via `openURL`).

## What actually runs — two processes only

| | Process | What lives in it |
|---|---|---|
| **1** | `DictationKeyboard.appex` (extension) | `KeyboardViewController`, `KeyboardViewModel` (thin UI, **no durable state**), plus a compiled-in copy of `DictationHandoff`. |
| **2** | `DictationContainerApp` (app) | `RecordSessionModel` (the Flow Session), `FlowSessionAudio` (the one `AVAudioEngine`), plus a compiled-in copy of `DictationHandoff`. |

- **`DictationHandoff`** is not a process — it's a Swift `enum` of static functions in the shared
  `DictationCoreBase` package. Both binaries link it, so each carries its **own compiled copy of the
  code**. The **data** it manages lives once, on disk, in the App Group container.
- **`FlowSessionAudio`** runs **only inside Process 2**. It owns the single `AVAudioEngine` (silent
  keep-alive + mic tap). The keyboard never touches it.

## "Darwin" — what it means

**Darwin** is the open-source Unix/Mach core under macOS & iOS. A *Darwin notification*
(`CFNotificationCenterGetDarwinNotifyCenter`) is the OS-level **cross-process signal bus** — the one
lightweight way two sandboxed processes can poke each other. It **carries no payload**, just a name.
That is *why* the App Group variables exist: the Darwin signal is the doorbell; the variables are what
you read when it rings.

---

## The model: 3 variables + 2 signals

Everything is a state machine over **three App Group variables**, with strict one-way ownership:

| Variable | Type | Writer | Reader | Meaning |
|---|---|---|---|---|
| `heartbeat` | timestamp | **App** (3s timer) | Keyboard | App alive? Fresh (<8s) → signal it. Stale → relaunch it. |
| `capturing` | bool | **App** | Keyboard | Dictation in progress? Decides start-vs-stop; re-syncs the keyboard after iOS recreates it. |
| `pendingText` | string+ts | **App** | Keyboard | Finished transcript. Keyboard polls, consumes (one-shot), inserts. |

*(`level` — a float mic loudness for a future waveform — also crosses the App Group but isn't a
control variable.)*

**Directional split (the invariant that removes the races):**

- **App → variables:** the app is the **sole writer**. The three variables *are* the app's state, on disk.
- **variables → keyboard:** the keyboard is the **sole reader**. Its own state is *derived* from them.
- **keyboard → app:** two Darwin signals only — **`START`** and **`STOP`**.

No variable is ever written by both sides. `openURL` is **not** a third signal — it's just how a
`START` resolves when `heartbeat` is stale (the app must be launched first).

## State names

| Object | State | Meaning |
|---|---|---|
| **App** (`RecordSessionModel.Phase`) | `warming` | Launched, loading model, not yet holding audio. |
| | `ready` | Warm & alive, engine silent, waiting. |
| | `capturing` | Mic tap on (same name as the `capturing` variable, on purpose). |
| | `transcribing` | Audio → text. |
| **Keyboard** | `not speaking` | `capturing == false`. Mic shown, "Tap anywhere to dictate". |
| | `speaking` | `capturing == true`. Golden wave, "Tap to finish". |

> **The keyboard holds NO state machine of its own.** It has exactly **two** appearances and both are a
> pure function of the single shared `capturing` variable — because iOS destroys + recreates the keyboard
> on every host-app switch, any local state we "kept in sync" would eventually drift (that was the
> golden-wave-vs-gray-wave desync). Instead a single 80 ms poll reads the variables and renders **one**
> value — `KeyboardPresentation { mode, level, label }` — and the view draws only that. Wave, colour,
> button, and label are therefore facets of the same snapshot; they cannot disagree. The struct is the
> enforcement: you can't update one element's state without the others.
>
> "Transcribing" is **not** a keyboard state — after a `STOP` the keyboard is simply `not speaking`; the
> transcript arrives via the poll and is inserted, with `label` showing a transient "Transcribing…" /
> "Inserted ✓" hint that never drives the wave or colour. `pendingText`/watchdogs are bookkeeping the
> **render reads**, never independent visual states.

## Heartbeat is liveness — orthogonal to phase

`heartbeat` is written by a **separate 3-second timer** that runs the entire time the app process is
alive — identically in `ready`, `capturing`, **and** `transcribing`. It never pauses for transcription.
Its only meaning: **is this process alive?** Fresh ⟺ alive; stale (>8s) ⟺ iOS killed it. The keyboard
reads it **only** when deciding a *new* tap → signal vs relaunch; never mid-dictation. *Coupling to
watch: the heartbeat loop must never share a thread a long transcribe can block.*

---

## The flow (swim-lane)

Keyboard sends signals; the app writes the variables; the keyboard reads them back.

```mermaid
sequenceDiagram
    participant K as Keyboard
    participant V as 3 Variables (App Group)
    participant A as App
    Note over K: not speaking — mic shown
    K->>A: START  (👆 tap · if heartbeat stale → openURL first)
    Note over A: ready → capturing (mic on)
    A->>V: writes capturing = true
    V-->>K: 80ms poll reads capturing = true
    Note over K: speaking (wave)
    K->>A: STOP  (👆 tap)
    Note over A: capturing → transcribing
    A->>V: writes capturing = false
    V-->>K: poll reads capturing = false
    Note over K: not speaking ("Transcribing…" hint)
    Note over A: transcribing → ready
    A->>V: writes pendingText = present
    V-->>K: poll finds pendingText
    Note over K: insert ("Inserted ✓")
```

## Keep-alive (why there's no repeat app-switch)

`FlowSessionAudio` is **one** `AVAudioEngine` that starts once and runs continuously, looping **silent
audio** so iOS keeps the app resident (`UIBackgroundModes: audio`) with **no mic held** between
dictations (no orange indicator). A dictation just **installs/removes a mic tap** — no engine restart.
This is what lets the app stay warm all day: only the *first* dictation after an iOS kill / reboot cold-
launches (one `openURL`); every later one takes the seamless `START` path. *(Two separate engines fought
one session and threw `'what'` 2003329396 after a cold relaunch — hence the single-engine design.)*

## Race conditions fixed (history)

| Bug | Root cause | Fix / invariant |
|---|---|---|
| 2nd tap *started* not *stopped* | keyboard tracked recording in local (recreated) state | start/stop decided **only** from `capturing` in the App Group |
| stuck "Transcribing", no paste | cross-process `UserDefaults` cache staleness | `synchronize()` on every transcript write + read |
| text vanished, keyboard stuck | a stale keyboard instance's `done` observer inserted into a dead proxy | removed the `done` observer; **only the visible instance polls + inserts** |
| mic dead after cold relaunch (`'what'`) | two `AVAudioEngine`s fighting one session | one engine, starts once, capture = add/remove a tap |
| wave/button/colour desynced (golden vs gray, stuck button) after app died mid-record | keyboard held per-element local state that drifted from `capturing` | keyboard holds **no** local state — one 80 ms poll renders a single `KeyboardPresentation` from `capturing`; a stop-ack watchdog clears a stuck flag if the app died |
| app suspended mid-long-dictation (lost a 66s take) | iOS suspends an app "playing" pure silence | keep-alive plays a ~-78 dB tone, not zeros |
| long recording could grow audio unbounded | no cap on a single dictation's accumulated buffers | app auto-finishes at 10 min (`maxCaptureSeconds`) — ~38 MB ceiling |
