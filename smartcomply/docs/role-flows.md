# Role flows — landing pages + primary tasks

The principle: **land each role on the screen where their primary work lives, with
the next action one click away.** Anything else is secondary navigation.

Roles are ordered by **how often a single person in that role uses the system
per year**. The auditor on the road uses it nearly daily; the template
approver may sign off once a quarter. We optimise the experience proportional
to usage — high-frequency roles get the best landing pages.

Each section answers:

1. **Who they are** — one line.
2. **Frequency** — rough sessions per year per individual + the reasoning.
3. **What they came for** — the unspoken question in their head when they log in.
4. **Where they land** — the route + what's on screen.
5. **Primary action** — the minimum-click path to their daily job.
6. **Secondary** — things they sometimes need; named only, not prescribed.

This doc deliberately ignores admin/setup (user management, permissions, system
config). Those happen rarely and don't need optimised flows.

---

## 1. Auditor (field operator)

| | |
|---|---|
| **Who** | The person physically walking a Kia dealership, checking the checkpoints, taking photos. Mobile-first. |
| **Frequency** | ~200–400 sessions/year. Audit cycles typically run 5 months × ~5 days/week × 1–2 audits/day. They're in the app every working day during cycles, often multiple times. |
| **Came for** | "What's my next site, and how far am I through it?" |
| **Lands on** | `My Audits` — mobile home. Two stacked lists: **In Progress** (resume here) and **Assigned** (start next). Each card: dealership + city + % complete + days until target. |
| **Primary action** | Tap a card → checkpoint screen of that audit (resumes where they left off, or starts at #1). At the end of the walk: one **Submit** button. |
| **Secondary** | "Show me a past audit I submitted" (read-only history). "Plans" tab for intervention re-inspections — same UI as audits, separate list. |

**Today's gap.** The mobile lands on a generic dashboard. The auditor has to tap
through one menu before they see their queue. Fix: replace the home screen with
the queue itself.

**Backend already exists:** `GET /api/audit/myAssignments` + `GET /api/intervention-assignment/myPlans`.

---

## 2. Audit Validator (data validator)

| | |
|---|---|
| **Who** | Quality reviewer who validates submitted user_checksheets (audit instances). Different person, different power, from the template validator. Reviews completeness, evidence quality, and judgement consistency. |
| **Frequency** | ~80–150 sessions/year. With 200–400 audits per cycle × 2 cycles/year split across 3–5 validators, each works through ~80–150 reviews. Weekly cadence during cycle peaks. |
| **Came for** | "Which audits did my operators submit, and how many are waiting on me?" |
| **Lands on** | `Audits — Awaiting My Validation` — a filtered queue (UC status = SUBMITTED). Each row: dealership · city · operator · submitted date · # NOT-OK answers · # photos. Count badge in the sidebar. |
| **Primary action** | Click row → audit-report-style view in **review mode**: every answer + photo + AI verdict, plus per-row Validate/Flag toggles. One button at the bottom: **Validate** (advances to APPROVED queue) or **Decline (with comment)**. |
| **Secondary** | "Was the AI verdict different from the auditor's?" — disagreement filter on the queue. "Audits I've already validated" — filter dropdown. |

**Today's gap.** No queue view. The user has to find the right audit
through BI drill. Add a status-scoped inbox.

---

## 3. Audit Approver (data approver)

| | |
|---|---|
| **Who** | Final approver on user_checksheets. Usually a senior dealer-development manager. Approves a validated audit before it enters BI rollups for the period. |
| **Frequency** | ~80–150 sessions/year. Same volume as the validator — every audit they validated needs an approver pass before BI counts it. Often the same person wears both hats, alternating roles per cycle. |
| **Came for** | Same as audit validator — "what's in my inbox?" |
| **Lands on** | `Audits — Awaiting My Approval` — filtered queue (UC status = VALIDATED). |
| **Primary action** | Same review-mode page → **Approve** or **Decline**. Approved → enters BI. Declined → bounces back to the validator (or auditor, depending on the comment). |
| **Secondary** | "Show me audits I've approved in the last 30 days" — status filter. "Approval-rate spot check" — vs my predecessor (deferred). |

**Today's gap.** Same — needs a queue.

---

## 4. Manager / Understudy (HoDD's lieutenants)

| | |
|---|---|
| **Who** | Regional or cluster managers reporting to the HoDD. Each owns 1–2 regions or 20–40 dealers. |
| **Frequency** | ~30–60 sessions/year. Weekly BI checks during cycles + intervention creation 5–10 times/year + ad-hoc dealer review when a complaint surfaces. Not as heavy as inbox roles but actively engaged. |
| **Came for** | "Which of my dealers is hurting me, and what should I make them fix this month?" |
| **Lands on** | `Regional Dashboard` scoped to **their** region — same panels as national but filtered. If they own multiple regions, a region picker sits where the national crumb is. |
| **Primary action** | Click red dealer → dealer dashboard → "Create intervention plan" CTA on the worst category. The form pre-fills the audit, dealer, and category questions; they pick priority + target date and Save & Activate. |
| **Secondary** | "What did my predecessor try last quarter?" — past interventions panel on the region screen. "Compare two halves of FY" — multi-period view (deferred). |

**Today's gap.** The intervention-creation CTA isn't on the regional/dealer screens
yet. Manager has to navigate to `/interventions/new`, then re-pick audit + scope.
Fix: add a contextual "New intervention from here" button on dealer + region
dashboards that pre-fills targeting.

---

## 5. Dealer Principal

| | |
|---|---|
| **Who** | The owner / GM of a Kia dealership. Has 1–N locations under their dealership. The receiving end of the audits — when an auditor's report flags issues, the principal is the one who has to fix them and accept the intervention plan. |
| **Frequency** | ~15–25 sessions/year per principal. Their dealership gets audited a couple of times per cycle (×2 cycles) plus intervention plans that need acknowledgement 4–8 times/year plus the occasional plan-status check. Forced actions (acks) drive the cadence — they have to log in when something lands. |
| **Came for** | "How did my dealership do, and what am I being asked to fix?" |
| **Lands on** | `My Dealership` — a single page scoped to **their** auditee. Two stacked sections:<br>**(a) My audits** — every audit submitted at any of their locations, newest first. Each row: location · period · score (with band pill) · "View report" link.<br>**(b) Plans needing my acknowledgement** — every intervention plan targeting their dealership where `acknowledgedAt IS NULL`. Each card: campaign name · priority · target date · # questions · **Acknowledge** button. Acked plans drop off this list and reappear in the "active plans" tab below. |
| **Primary action** | Two flows depending on what brought them in: <br>(a) Review an audit — click row → opens the **same audit-report page** every other role sees (`/audit-report/:userChecksheetId`). Same scoring, same checkpoint cards, same re-audit overlay if interventions have been filled. Print button works the same as for HoDD / managers. <br>(b) Acknowledge a plan — single click. Stamps `acknowledgedAt` + `acknowledgedByUserId` on the intervention assignment so the operator can start the re-inspection. |
| **Secondary** | "What's the running compliance trend for my dealership?" — small score-over-time chart at the top of the page. "Past acknowledged plans" — collapsible second tab. "Close a plan as non-compliant" — escalation action with mandatory reason (handled on the plan card, behind a confirm). |

**Today's gap.** The `/my-plans` page exists for the acknowledge flow but doesn't
show audits and doesn't lead to the standard audit-report. Fix: rename it to
`/my-dealership`, add the audit-list section, and link the rows to the existing
`/audit-report/:userChecksheetId` route (no new screen needed — every other role
already uses it). Print view (`/audit-print/:userChecksheetId`) is also already
shared.

**Constraint to enforce.** This view is scoped to **the principal's auditee
only**. Backend already gates `myPlans` by user; the audit list needs the same
gate (server-side filter, not just UI).

---

## 6. Head of Dealer Development (HoDD)

| | |
|---|---|
| **Who** | Senior leader for the whole dealer network — the BI's primary consumer. Reads, rarely writes. |
| **Frequency** | ~12–25 sessions/year. Monthly board-level reviews plus end-of-cycle deep dives plus occasional ad-hoc checks. Mostly delegates day-to-day to managers, so per-session intensity is high but cadence isn't. |
| **Came for** | "Is the network compliant this period, and where is the bleeding?" |
| **Lands on** | `National Dashboard` — band split (G/A/R), Compliance % delta vs last audit, "What's Failing" top-6 categories, red-dealers list, intervention progress, AI insight callout. |
| **Primary action** | None — looking is the action. Drill by clicking: a region → the regional dashboard; a category in "What's Failing" → drill panel; a red dealer → the dealer dashboard. Three drill clicks max gets them to a specific audit report. |
| **Secondary** | "Has the network improved since interventions started?" — handled by the intervention summary band on the same dashboard. "Export to deck" — print report from any drill level. |

**Today's gap.** None. National dashboard is already the right landing.

---

## 7. Audit & Intervention Builder

| | |
|---|---|
| **Who** | The operations person who launches audit campaigns each period and creates intervention campaigns when BI surfaces problems. May or may not be the same person as the template creator — design assumes separate. |
| **Frequency** | ~12–25 sessions/year. Audit campaigns launch 2 times/year (one per cycle) + intervention launches 5–15 times/year + cleanup sessions to add locations, close campaigns, etc. Bursty around cycle starts. |
| **Came for** | Two distinct entry points depending on what triggered the session: <br>(a) Cycle start — "Time to launch the next audit campaign." <br>(b) BI flagged a problem — "Spin up an intervention for that category." |
| **Lands on** | `Campaigns` — a two-tab page: **Audits** | **Interventions**. Each tab shows active campaigns + their progress (locations covered / total, % complete, days remaining). Top-right CTA on each tab: **+ New Audit Campaign** / **+ New Intervention Campaign**. |
| **Primary action** | <br>(a) Audit launch: **+ New Audit** → form (name, template, dates, scope) → on save, picks the location set + assigns operators (round-robin or manual). One screen. <br>(b) Intervention launch: when arriving from BI drill, the URL pre-fills audit + category + scope. They confirm name, priority, target date, hit Save & Activate. The form filters out questions already in other campaigns automatically. |
| **Secondary** | "Close this campaign" — single button on a row when all assignments complete or the period ends. "Reassign an operator" — drilldown on an audit campaign's assignment row. |

**Cross-creation between the two tabs.** Both directions are first-class so the
builder never starts from a blank form when they came in with context:

- **From an intervention → pick an audit**: the intervention form's audit
  selector is the entry point. *Already exists today.*
- **From an audit → new intervention**: a CTA on the audit campaign row (and on
  the shared audit-detail page described below) that deep-links to the
  intervention form with `auditId` pre-filled. The form's question picker
  already filters out questions claimed by other active/draft interventions,
  so the user sees only the still-targetable subset of that audit's checksheet.

**Today's gap.** Two separate top-level entries (`/audits`, `/interventions`)
visually disconnect them, even though both are campaigns. Unify under one
`/campaigns` page with tabs. The audit → intervention CTA doesn't exist yet
(intervention → audit does).

---

## 8. Audit Template Creator

| | |
|---|---|
| **Who** | The person who authors checksheets — typically an audit consultant, sometimes the brand's quality team. Touches the system once per audit cycle, not daily. |
| **Frequency** | ~10–20 sessions/year. Templates are stable across periods. Author 1–3 new templates per year + 5–10 revision sessions on existing ones (each Validate / Approve decline → re-edit pulls them back in). |
| **Came for** | "I need to build / edit / clone a template." |
| **Lands on** | `Templates` — a single page listing all checksheets with status pills (Draft / Submitted / Validated / Approved). One CTA: **+ New Template**. Each row clickable to edit (Draft) or view (everything else). |
| **Primary action** | **+ New Template** → blank template builder with the 4-level hierarchy primitives (Zone → Category → Element → Question). Or **Clone** an existing one. Save as Draft any time; Submit when ready. |
| **Secondary** | "Why did this template get rejected?" — declined templates show a banner with the validator/approver comment. "Re-submit after edits" — single button on a rejected draft. |

**Today's gap.** Template creation UX exists but spreads across multiple submenu
items. Land them on one page that covers the lifecycle states.

---

## 9. Audit Template Validator

| | |
|---|---|
| **Who** | Subject matter reviewer who checks templates for completeness, scoring sanity, hierarchy clarity. Reviews when a creator submits. |
| **Frequency** | ~5–15 sessions/year. Template volume is low and validation passes are quick (sometimes one sitting). Mostly a quarterly visitor. |
| **Came for** | "What's in my inbox to review?" |
| **Lands on** | `Templates — Awaiting My Validation` — a filtered queue (status = SUBMITTED). Each row: template name, creator, date submitted, # of questions. No noise from drafts or approved templates. Count badge in the sidebar. |
| **Primary action** | Click row → side-by-side template content + comment box → **Validate** or **Decline (with comment)**. Decline → bounces back to creator with the comment surfaced on their draft. |
| **Secondary** | "Show me everything I've validated this year" — same page, status filter dropdown. "Browse approved templates" — read-only access from the same list. |

**Today's gap.** No queue view. Validator has to scroll through all templates and
filter mentally. Add the filtered landing.

---

## 10. Audit Template Approver

| | |
|---|---|
| **Who** | Final sign-off on a template. Often the HoDD or a senior brand owner. |
| **Frequency** | ~5–10 sessions/year. Lowest-touch role here — they only show up when a template is fully validated and ready for sign-off. Often handled in a single sitting per template. |
| **Came for** | Same as validator — "what's in my inbox?" |
| **Lands on** | `Templates — Awaiting My Approval` — filtered queue (status = VALIDATED). Same layout as the validator's inbox; different status filter. |
| **Primary action** | Click row → review → **Approve** or **Decline (with comment)**. An approved template becomes usable in new audit campaigns. |
| **Secondary** | Same as validator. |

**Today's gap.** Same as validator — no queue view.

---

## Shared screen: Audit Detail (`/campaigns/audits/:auditId`)

Every "Audits" listing — Campaigns tab, BI drill, mobile, admin search — opens
the same audit-detail page when a row is clicked. The page is the answer to
"tell me everything about this audit campaign in one screen".

### Page header — basic metadata

- **Audit name** (large, the page title)
- **Template** — link out to the template view
- **Status** — ACTIVE · DRAFT · CLOSED (pill)
- **Period** — start date → end date, with days-remaining countdown when ACTIVE
- **Created by** — user + timestamp
- **Quick stats strip** — total locations · done · in progress · not started · % complete (this is the same payload `GET /api/audit/list` already returns; just rendered above the tabs)

### Tab 1 — Locations (default)

Every location in scope for this audit. One row per `inspection (kind=AUDIT)`:

| Column | Source |
|---|---|
| Dealership · location label | auditees.name · auditee_locations.address |
| Region | regions.name |
| Operator | inspections.operator_user_id → users |
| UC status | inspections.status (ASSIGNED → IN_PROGRESS → SUBMITTED → VALIDATED → APPROVED, DECLINED bounces back) |
| Score | rolled-up pct_ok (post-intervention if any), only shown when status ≥ APPROVED |
| Submitted at | inspections.submitted_at |
| Row action | "View audit report" link to `/audit-report/:userChecksheetId` when status ≥ SUBMITTED |

Row supports a status filter (all / pending / in progress / submitted / approved)
and a region filter; both drive the same column, no new server call.

### Tab 2 — Interventions

Every intervention campaign whose `audit_id` = this audit. One row per `interventions`:

| Column | Source |
|---|---|
| Intervention name | interventions.name |
| Priority | interventions.priority (P1 / P2 / P3 pill) |
| Status | DRAFT / ACTIVE / CLOSED |
| Target date | interventions.target_date |
| Questions tracked | count of intervention_questions rows |
| Locations in scope | count of intervention_assignment_targets |
| Plans completed | count of `inspections (kind=INTERVENTION, status=APPROVED)` |
| Row action | link to intervention detail (existing `/campaigns/:id`) |

### Tab 3 — BI summary

The existing BI national dashboard, scoped to this audit. No new screen — just
the same `MainDashboardComponent` with `auditId` pinned from the route.
Cleanest tab to build because everything's reusable.

### Page actions (visible to roles with AUDIT_MANAGE)

Top-right, three CTAs:

- **+ Add locations** — extends scope. Reuses existing `POST /api/audit/addAuditAssignments`.
- **+ New intervention from this audit** — deep-link to intervention form with `auditId` pre-filled. The question picker already hides questions claimed by other active/draft campaigns, so the user sees exactly what's still targetable. (This is the audit → intervention cross-create direction from §7.)
- **Close audit** — available when status = ACTIVE and all assignments are APPROVED or the period has ended. Stamps closed_at and switches status to CLOSED.

For roles without AUDIT_MANAGE (HoDD, managers dropping in from BI, Dealer Principal viewing one of their dealership's audits), the tabs are read-only and the CTAs are hidden — same page shape, fewer buttons.

### Today's gap

Route `/audits/:auditId` doesn't exist. The data does — `GET /api/audit/{id}`
already returns audit + every assignment with status + scoring. The interventions
list scoped to an audit is the only new server piece needed (small query, can
piggyback the existing audit detail endpoint).

### Why this matters

The campaign listing is a list; it has to actually open to something. Without
this page, the only way to see "what's the state of FY26 H1 Kia Showroom Audit"
is to dig through BI dashboards, which are organised by region and dealer — not
by audit campaign. The Builder role specifically wants the campaign-shaped view
because their unit of work is the audit, not the dealer.

---

## What this doc deliberately leaves out

- **User / role / permission management** — admin task, rare, current screens are fine.
- **Department and section setup** — one-time setup at tenant onboarding.
- **System config (LLM provider, S3, email)** — done once, by Anthropic side.
- **Internal debug screens** (API logs, audit trail) — engineering tools, not role flows.
- **Mobile screens past the home queue** — the audit-walk flow is its own design doc.

---

## Cross-role conventions

A few patterns reused across the queues + landings above:

- **Sidebar count badges** for any role with an inbox (template/audit validators, approvers). The count is the role's specific filtered list — never a global counter.
- **Status filter dropdown** lives in the same place on every queue page so role X knows where to look when they swap into role Y.
- **Decline always requires a comment**. No silent rejections; the bounced-to role always sees why.
- **Crumb trail** on every drill page so a BI consumer who landed deep can step back without using the browser back button.

---

## Where to go from here

The doc is a target shape, not a plan. A reasonable sequencing if we choose to
build to it (subject to user prioritisation). The order roughly tracks the
frequency ranking above — fix the most-used screens first.

1. **Auditor mobile home → assignment queue** (§1 — daily users, biggest payoff).
2. **Audit Validator + Audit Approver inboxes** (§2, §3 — weekly users, today have no queue view at all).
3. **Manager regional landing + "new intervention from here" CTA** (§4 — closes the BI-to-action loop).
4. **Dealer Principal `/my-dealership` consolidation** (§5 — audits list + acknowledge cards on one page; audit rows reuse the existing audit-report).
5. **Unify `/audits` + `/interventions` → `/campaigns` with tabs**, plus the **Audit Detail page** (Locations / Interventions / BI tabs) and the **audit → new intervention** CTA on it (§7 + shared screen — biggest user-visible feature here, opens up the "what's happening on FY26 H1" view that doesn't exist today).
6. **Template inboxes for validator + approver** (§9, §10 — low frequency but cheap to ship since they reuse the audit-inbox shape).
7. **Templates page consolidation** (§8 — current screens work, just scattered).
