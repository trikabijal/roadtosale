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

**Entry:** after the setup card, land on a **role picker** (Sales rep / Service advisor)
or a home that offers two modes:
- **Real Conversation (Live)** — the guided on-floor / in-lane flow.
- **Training (Practice)** — practice against an AI customer.

### 5.1 Real Conversation — Sales
Reframe the existing sales flow: drop "Compliance"/"Audit Complete"; rename the tail to a
**"Sale recap"** screen (steps followed vs skipped, coaching cue, "what to practice next"
that links to Training). Keep audio detection + auto-confirm.

### 5.2 Real Conversation — Service (NEW)
Build the 4.2 service-lane flow with the same mechanics (script card, audio detection,
yes/no auto-confirm, photos for the walkaround, a recommended-service card showing the
**$ value**, and an objection-handling card for declined service). End on a **"Service
recap"** with the recommended vs approved dollars and "what to practice next."

### 5.3 Training — practice with an AI customer (NEW)
- **Scenario picker** — a few cards per role (Sales: "Price objection," "Trade-in fight,"
  "Just looking"; Service: "Declined maintenance," "Too expensive," "I'll go to my shop").
- **Roleplay screen** — a chat/voice-style exchange with an **AI customer**: the AI raises
  the objection, the rep responds (canned/scripted for the demo), live coaching hints.
- **Score/feedback recap** — a score, what went well, what to fix, and a **"this drill was
  assigned because your floor rate on [step] is low"** line — this makes the loop explicit.

### 5.4 The loop in mobile
On the rep home / recap, show a **"Assigned to you"** practice card that came from a real
floor gap (e.g., "Manager turnover skipped on 3 of your last 5 deals → practice the close").

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
A tab (e.g., "Coaching loop") that shows, for both departments:
- **Who practiced** this week, drills completed, average practice score.
- **Drills auto-assigned from floor gaps** — a table linking a rep's weak floor step →
  the drill assigned → practice score → **before/after floor rate** (the loop, closing).
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
