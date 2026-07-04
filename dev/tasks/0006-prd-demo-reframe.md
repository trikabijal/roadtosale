# 0006 — PRD: Demo Reframe (Sales + Service, Training + Real Conversation)

> **Status:** Draft for implementation
> **Author:** Bijal
> **Scope:** The two HTML demo prototypes only —
> `demo/html-prototype/RoadToSale_BI.html` (manager) and
> `demo/html-prototype/RoadToSale_Mobile.html` (rep/advisor). No product code.
> **Audience:** written so a junior developer can build it exactly.

---

## 1. Why this change

The pitch changed. The old demo positioned Road to Sale as a **sales-process audit /
compliance** tool, and only covered the **live floor** (capture). The new pitch has
**two pillars working as a loop**, across **two roles**, and deliberately avoids the
"audit/compliance/finance" framing.

**The one-line frame:** *"Your floor tells you what to practice. The practice fixes
your floor."*

- **Training** — reps practice real conversations against **AI customers** (discovery,
  objection handling, service recommendations) before trying them on a real customer.
- **Real Conversation** — on the floor / in the service lane, it captures what actually
  gets said and guides the rep through the process live.
- **The loop** — the floor surfaces each rep's weak step → that becomes an assigned
  practice drill → practice lifts the score → the floor rate improves. **No competitor
  closes this loop.** This is the thing the demo must make obvious.

### Both pillars are required (not either/or)
Dealership sales/service has **high turnover** — new people arrive constantly, so
*everyone* needs training at some point. That makes Training two things at once, and
the demo must show **both on-ramps into one practice engine**:

1. **Onboarding (new hire).** Ramp a new rep fast against AI customers **before** they
   touch the floor and burn real customers. Quick onboarding is a **major** selling
   point given the churn. This path is a structured curriculum, not driven by a floor
   gap (they have no floor history yet).
2. **Targeted remediation (existing rep).** The floor finds the rep's *specific*
   mistake → we assign a drill on exactly that. "**Find it, then fix it on specifics**"
   is the differentiator.

Both ship. "Lead with Training" vs "lead with the floor" is only a **demo-sequencing**
choice for a given buyer — never a suggestion that you get one and not the other. Every
path lands on the loop.

### Two roles (decided)
- **Sales** — the showroom road to the sale (greet → close).
- **Service** — the service lane. **Featured prominently**: service is a larger profit
  center than pure sales (recommended-service presentation, declined-service recovery,
  hours & dollars per RO).

### Hard boundary (keep as an explicit selling point)
**Sales side and service side only — nothing to do with the finance office (F&I),
compliance, or the deal numbers.** We coach the *conversation*, not the paperwork or
the money. Do **not** show F&I screens, compliance scoring, or deal financials.

---

## 2. Goals

1. Show **both pillars** (Training + Real Conversation) — today the demo only shows the
   floor half.
2. Show **both roles** (Sales + Service), with Service given real weight.
3. Make **the loop** unmistakable (floor gap → assigned drill → improved floor).
4. Reframe language away from **audit/compliance**; keep it **coaching**-flavored.
5. Keep the existing visual quality/polish and the guided-autoplay demo capability.

---

## 3. Language / branding changes (apply everywhere)

| Old | New |
|---|---|
| "Compliance" / "compliance rate" | "Process followed" / "adherence" / "followed rate" |
| "Audit" / "Audit Complete" | "Sale recap" (sales) / "Service recap" (service); "capture", "review" |
| Finance/F&I handoff step & F&I screens | **Remove** (out of scope) |
| Sales-only framing | Sales **and** Service |

- Keep the **"Road to Sale — by AuditPro"** logo lockup.
- Keep the palette, fonts (Syne + system), card styles, dark rep-app / light BI look.
- Tone: coaching, not policing. "Where deals leak," "what to practice," "what got
  skipped" — not "violation," "non-compliant."

---

## 4. Roles & processes (use these as the real content)

### 4.1 Sales — road to the sale (reframe existing flow)
Greet → Needs discovery → Vehicle match → Front-line ready → Walkaround →
Test drive → Trade-in → Figures/pencil → **Close** (replaces the old "Manager T.O. →
F&I handoff → Audit Complete" tail). Keep the audio-detection / auto-confirm mechanic.

### 4.2 Service — the service lane (NEW, feature it)
A parallel "road to the sale" for the service drive:
1. **Greet / write-up** — confirm the customer's concern, appointment vs walk-in.
2. **Walkaround inspection** — note existing damage, photos (reuse the photo mechanic).
3. **Multi-point inspection (MPI) review** — red/yellow/green findings.
4. **Recommended-service presentation** — present needed maintenance with the *why* and
   the **dollar value** (this is the profit moment).
5. **Objection / declined service** — handle "not today / too expensive / I'll wait,"
   offer options, schedule a follow-up (declined-service recovery).
6. **Approval & active delivery** — what was done, next visit.

### 4.3 Objections & competitors (surface in both roles)
The pitch calls out "how objections actually got handled" and "which objection/competitor
keeps coming up."
- **Sales:** trade-in value fight, "I need to think about it," "the store down the street."
- **Service:** "too expensive," "I'll do it later," "I'll go to my local/indie shop."

---

## 5. Mobile prototype — `RoadToSale_Mobile.html` (rep / advisor)

Keep the phone-in-frame layout, side panels, setup screen, and guided autoplay engine.

**Entry:** the setup card gains a **"Lead with…" demo toggle** — *Training-first* or
*Floor-first* — so the presenter opens on whichever pillar fits the buyer (both are
still reachable; see §1 "both required"). After setup, land on a **home** with a
**role picker** (Sales rep / Service advisor) and two modes:
- **Real Conversation (Live)** — the guided on-floor / in-lane flow.
- **Training (Practice)** — practice against an AI customer (this must be a *deep*,
  first-class flow — equal weight to the floor flow, not an add-on).

### 5.1 Real Conversation — Sales
Reframe the existing sales flow: drop "Compliance"/"Audit Complete"; rename the tail to a
**"Sale recap"** screen (steps followed vs skipped, coaching cue, "what to practice next"
that links to Training). Keep audio detection + auto-confirm.

### 5.2 Real Conversation — Service (NEW)
Build the 4.2 service-lane flow with the same mechanics (script card, audio detection,
yes/no auto-confirm, photos for the walkaround, a recommended-service card showing the
**$ value**, and an objection-handling card for declined service). End on a **"Service
recap"** with the recommended vs approved dollars and "what to practice next."

### 5.3 Training — the deep, first-class experience (this is the main fix)
Training is NOT a bolt-on scripted chat. Build the **full arc of a practice session** so a
training-first buyer sees exactly *how training happens*. Two on-ramps (§1), one engine.

**A. Training home — two on-ramps:**
- **Onboarding path (new hire):** a structured curriculum — "Your first week / floor-ready
  in N days" — a sequence of modules covering the whole road-to-sale (greet, discovery,
  walkaround, objection handling, close for Sales; write-up → recommendation → declined-
  service for Service), each a practice rep against an AI customer, with progress and a
  **"floor-ready" certification**. This is the fast-onboarding win. NOT driven by a floor gap.
- **Targeted drills (existing rep):** cards **assigned from real floor gaps** (e.g.,
  "Trade-in objection — assigned: you lost the trade-in fight on 3 of 5 deals"), plus free
  practice. This is the find→fix-on-specifics differentiator.

**B. Meet the AI customer (persona setup) — makes it not a chatbot:** before starting, a
persona card — name, buyer type, situation, mood, difficulty dial — plus the rep's
objective. e.g., *"Denise, 38, trading a high-mileage RAV4, has a competing quote from the
store down the street, price-driven, guarded. Objective: run discovery, handle the trade +
competitor objection, ask for the sale."*

**C. The live roleplay (the core — must feel real):** a **voice-call-style** screen
(waveform + live two-way transcript) with the AI customer. The customer talks like a real
person — raises objections, pushes back when the rep folds, warms when handled well. A
**live coaching rail** nudges in real time ("she gave a buying signal — ask for the sale";
"you dropped price before she objected — hold it") and a **step tracker** lights up as the
rep hits discovery / presentation / objection / close. Scripted/canned for the demo (§8),
but it must *read* as adaptive. A shortcut/weak answer visibly lowers the outcome.

**D. The debrief scorecard (the payoff):** overall score + skill breakdown (discovery /
objection handling / close), an **annotated transcript** flagging the key moments with
specific coaching, which objections came up and how each was handled, and **"try again."**

**E. Loop back:** the debrief closes the loop from the training side — "you've drilled this
3× this week, 61→84; we're watching your floor rate on it" — mirroring the manager's BI view.

### 5.4 The loop in mobile
On the rep home / recap, show an **"Assigned to you"** practice card from a real floor gap
(e.g., "Manager turnover skipped on 3 of your last 5 deals → practice the close") that
deep-links into the roleplay. New hires instead see their **onboarding progress** card.

---

## 6. BI prototype — `RoadToSale_BI.html` (manager)

Keep the layout, nav, Chart.js usage, tabs. Reframe and extend.

### 6.1 Department switch
Top-level toggle: **Sales | Service** (default Sales). Each shows its own KPIs and matrix.

### 6.2 Sales view (reframe existing)
Rename "Sales Floor Dashboard" wording away from compliance. Metrics: deals, closed,
walked, close ratio. Keep the step-followed bars, closed-vs-walked gap chart, and rep ×
step matrix — relabeled "process followed," not "compliance."

### 6.3 Service view (NEW — the profit story)
Service-specific KPIs and matrix:
- KPIs: **ROs today**, **recommended-service presentation rate**, **declined-service $
  (recoverable)**, **$ / RO** (or hours/RO).
- Advisor × service-step matrix (write-up, walkaround, MPI review, recommendation,
  objection handling, follow-up).
- A **declined-service** breakdown (top decline reasons = the objection view).

### 6.4 Training + the loop (NEW tab — the differentiator)
A tab (e.g., "Coaching") that shows, for both departments, BOTH training on-ramps (§1):

- **Onboarding tracker (new hires — the turnover win):** list of new hires with **ramp
  progress**, **days-to-floor-ready** (vs a target), modules completed, and certification
  status. Headline a value stat like "new hire floor-ready in X days vs Y before." This
  addresses the high-turnover pain directly.
- **The loop (existing reps):** **who practiced** this week + avg practice score, and a
  table that **closes the loop** — rep's weak floor step → drill auto-assigned → practice
  score → **before/after floor rate**.
- One clear "this is the loop" visual: floor gap → practice → floor improvement.

### 6.5 Objections & competitors (surface)
Somewhere in each department: "objections/competitors that keep coming up" (counts),
matching §4.3.

---

## 7. Functional requirements

1. Both files remain **single self-contained HTML** (inline CSS/JS; Chart.js via the
   existing CDN is fine). Open-in-browser, no build step.
2. Preserve the **guided autoplay** demo mode in mobile (the "Play Guided Demo" path).
3. All "Compliance"/"Audit" wording removed per §3.
4. **No F&I / finance / compliance / deal-financial** screens anywhere.
5. Mobile shows **both roles** and **both pillars** (Real Conversation + Training) and at
   least one explicit **loop** link (floor gap → assigned drill).
6. BI has a **Sales|Service** switch, a **Service** view with profit KPIs, and a
   **Coaching-loop** view that visibly closes the loop.
7. Objections/competitors surfaced in both (§4.3).
8. Keep it responsive and visually consistent with the current prototypes.
9. Numbers should be internally consistent and realistic for a Honda-style store.

---

## 8. Non-goals
- No real backend, no real AI — the AI-customer roleplay is scripted/canned for the demo.
- No F&I, finance, compliance, or deal-economics content.
- No changes to product code (`road-to-sale-app/`, etc.) — demo prototypes only.
- Not building new roles beyond Sales + Service.

---

## 9. Success / acceptance
- Opening each HTML shows the new framing with zero "compliance/audit" wording and zero
  F&I/finance content.
- A viewer can see, in the mobile demo: a Sales live flow, a Service live flow, and a
  Training roleplay with a score — plus a loop link.
- A viewer can see, in the BI demo: Sales and Service dashboards (Service with profit
  KPIs) and a Coaching-loop view that ties practice to floor improvement.
- The guided autoplay still runs.

## 10. Open questions
- Exact Service KPIs to headline ($/RO vs hours/RO vs effective labor rate) — using
  recommended-presentation rate + declined-service $ + $/RO unless told otherwise.
- Whether Training roleplay should feel voice-first (mic UI) or chat-first — defaulting to
  a hybrid (chat bubbles + the existing audio-wave motif) for demo clarity.
