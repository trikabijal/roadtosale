# PR Review Guide — AuditPro AI & Branding (April 2026)

## Overview

Work done across 2 days (April 25-26, 2026) covering: AI-powered photo assessment, multi-provider LLM engine, template upload enhancement, and white-label branding system.

**4 PRs across 3 repos.**

---

## PR #1: LLM Module (extract to own repo)

**Repo:** `auditpro` → **NEW REPO: `auditpro-llm`** (to be created)
**Branch:** `feat/llm-module`
**Size:** 22 files, 2,783 lines added
**Reviewer:** Bijal (personal review — this becomes a shared library)

### What it does
Extractable multi-provider LLM module with zero host-app dependencies. Supports Anthropic Claude, OpenAI, Google Gemini.

### Key files
```
src/main/java/com/checkSheet/llm/
├── LlmClient.java                    Interface: assessImage() + complete()
├── LlmResponse.java                  Base response with tokens, latency
├── AnthropicLlmResponse.java         Anthropic cache token breakdown
├── LlmModel.java                     14 models with per-token costs
├── LlmProvider.java                  Enum: ANTHROPIC, OPENAI, GOOGLE_GEMINI
├── LlmConfig.java                    Spring config, provider selection
├── LlmException.java                 Module's own exception (no host deps)
├── BenchmarkingLlmClient.java        Shadow traffic decorator
├── SpendingGuard.java                Circuit breaker (cost + call limits)
├── CostBreakdown.java                Provider-aware cost calculation
├── HttpClientFactory.java            Timeouts + retry with backoff
├── provider/
│   ├── AnthropicVisionProvider.java   Prompt caching, cache_control
│   ├── OpenAiVisionProvider.java      Chat completions API
│   └── GeminiVisionProvider.java      generateContent API
└── docs/
    ├── api.md                         Full API reference
    ├── architecture.md                Design decisions, extraction guide
    └── flows.md                       6 end-to-end flow traces
```

### Review focus
- Zero imports from `com.checkSheet.*` (except `com.checkSheet.llm.*`)
- CostBreakdown accuracy per provider
- SpendingGuard thread safety
- HTTP retry logic

---

## PR #2: AI Photo Assessment (backend)

**Repo:** `auditpro`
**Branch:** `feat/ai-photo-assessment` → `development`
**Size:** 56 files, 12,992 lines added
**Reviewer:** Team

### What it does
End-to-end AI photo assessment: auditor uploads photo → AI evaluates against checkpoint criteria → returns OK/NOT_OK with explanation.

### Key components

**AI Assessment API:**
- `POST /api/ai/assess` — photo + checkpoint IDs → AI judgement
- `GET /api/ai/assessment` — retrieve existing assessment
- `GET /api/ai/assessments` — all assessments for a checksheet
- IDOR protection: verifies user owns the checksheet
- File upload: photo stored only after successful AI call

**Admin API:**
- `POST /api/ai/admin/benchmark?enabled=true` — toggle shadow benchmarking
- `GET /api/ai/admin/status` — spending guard, provider, model info
- `POST /api/ai/admin/spending/reset` — reset circuit breaker
- Gated behind SUPER_ADMIN permission

**Template Upload Enhancement:**
- Excel upload now creates questions AND answer options (OK/NOT_OK text)
- Supports all answer types: Subjective Condition, Objective, Subjective, NA
- Auto-skips example rows from downloaded template
- Validates: @Transactional, numeric limits, missing result header

**Database:**
- `V1.19__create_ai_assessments_table.sql` — assessments with FK constraints
- `V1.20__add_provider_and_token_tracking.sql` — provider, tokens, latency columns

**Tests:** 34 automated tests (17 service, 6 controller, 11 provider)

### Review focus
- Security: IDOR check in assessPhoto(), admin permission gate
- File handling: photo stored after LLM success, filename sanitized
- Error propagation: LlmException → CustomException with correct HTTP status
- Template upload: @Transactional restored, NumberFormatException now throws

---

## PR #3: Grok Provider (backend)

**Repo:** `auditpro`
**Branch:** `feat/grok-llm-provider` → `development` (merge after PR #1)
**Size:** 7 files, 193 lines added
**Reviewer:** Bijal

### What it does
Adds xAI Grok as 4th LLM provider. OpenAI-compatible API at `api.x.ai`.

### Key files
- `GrokVisionProvider.java` — implements LlmClient
- `LlmProvider.java` — adds `GROK` enum value
- `LlmModel.java` — adds `GROK_2_VISION` ($2/$10 per M tokens)
- `CostBreakdown.java` — handles GROK in cached token switch
- `LlmConfig.java` — adds GROK case to provider selection

---

## PR #4: Frontend — Branding + Template + Labels

**Repo:** `auditpro-angular`
**Branch:** `development` → `development` (on origin)
**Size:** 229 files, 3,777 lines added, 662 removed
**Reviewer:** Team

### What it does

**White-label branding system:**
- Base + customer overlay model — `./install-customer.sh kia` applies branding
- SCSS variables as single source of truth → CSS custom properties → TypeScript
- All 86+ hardcoded brand colors replaced with variables
- All domain labels ("Checksheet", "Department", "Section") use LABELS constant
- All toast messages, SweetAlert dialogs, chart colors centralized
- APP_NAME constant for product name
- SVG icon colors themed per customer

**Template download enhancement:**
- ExcelJS for styled Excel: dark headers, grey example rows, green indicator
- Answer Type Guide reference sheet
- All 4 answer types documented with examples

**Customer overlays:**
- `customers/kia/` — Kia India (midnight black, Kia red, Audit/Zone/Dealership)
- `customers/_default/` — generic AuditPro
- `install-customer.sh` — applies theme, logo, labels, icons in one command

**QA automation:**
- `qc/screenshot-test.ts` — Playwright tests 12 screens for branding issues
- Checks: unrendered templates, old colors, old product names, empty labels
- `npm run qc` to run

### Key files to review
```
src/styles/_variables-default.scss       Default theme
src/styles/_variables-kia.scss          Kia theme (now in customers/kia/)
src/app/constants/ui-colors.ts          TS color accessor (reads CSS vars)
src/app/constants/labels.ts             Domain terminology constants
src/app/constants/common.ts             APP_NAME
customers/kia/*                         Kia overlay files
install-customer.sh                     Customer install script
qc/screenshot-test.ts                   Visual QA automation
docs/branding.md                        Branding guide
docs/installation.md                    Full setup guide
docs/visual-qa.md                       QA test guide
```

### Review focus
- No hardcoded brand colors remaining (automated test confirms)
- Labels migration completeness — all visible domain text uses constants
- install-customer.sh handles all override files correctly
- Base code commits with default (generic) branding, not Kia

---

## PR #5: LLM Module Extraction + CI/CD (backend)

**Repo:** `auditpro`
**Branch:** `feat/ai-photo-assessment` (same branch, latest commits)
**Reviewer:** Team

### What it does
- Replaces embedded `com.checkSheet.llm` package with external `com.trika:llm:1.0.0` JAR from GitHub Packages
- Deletes 17 local LLM source files (now in `trikabijal/llm` repo)
- Updates all imports: `com.checkSheet.llm` → `com.trika.llm`
- Adds `LlmStartupCheck`: logs ERROR if JAR missing, WARN if no API keys
- Adds `.gitlab-ci.yml`: 4-stage pipeline (compile → test → package → docker)
- Adds `docs/build.md`: full build guide, LLM dependency setup, CI/CD docs

### CI/CD Pipeline
```
PR → compile → test (23 tests) → feedback on MR
development/main → compile → test → package WAR → docker (manual)
```

### Review focus
- `pom.xml`: LLM dependency + GitHub Packages repository config
- `.gitlab-ci.yml`: Maven settings inject GitHub token for LLM JAR
- `LlmStartupCheck.java`: startup validation logic
- All `com.trika.llm` imports resolve correctly

---

## Repos

| Repo | Platform | URL | CI/CD |
|------|----------|-----|-------|
| **trikabijal/llm** | GitHub | https://github.com/trikabijal/llm | GitHub Actions: compile → test → package JAR |
| **auditpro** | GitLab | gitlab.tiez.net | GitLab CI: compile → test → package WAR → docker |
| **auditpro-angular** | GitLab | gitlab.tiez.net | (needs adding) |

## Build Dependency Chain
```
trikabijal/llm (GitHub)
  ↓ publishes com.trika:llm:1.0.0 JAR
auditpro (GitLab)
  ↓ pulls JAR, builds WAR, packages Docker
auditpro-angular (GitLab)
  ↓ ng build, customer overlay
```

## Merge Order

```
1. trikabijal/llm           ✅ DONE — live on GitHub, JAR published locally
2. feat/ai-photo-assessment → merge to auditpro development (includes CI/CD)
3. Frontend development     → merge to auditpro-angular origin/development
```

The Grok provider branch (`feat/grok-llm-provider`) merges into `trikabijal/llm`, not auditpro — it's now LLM repo's concern.

---

## Test Evidence

| Test | Result |
|------|--------|
| LLM module compile (standalone) | PASS (14 source files, `com.trika.llm`) |
| LLM module tests | 11/11 PASS |
| LLM JAR package | 34KB JAR at `com.trika:llm:1.0.0` |
| Backend compilation (against external JAR) | PASS |
| Backend unit tests | 23/23 PASS |
| Frontend build | PASS (5.09 MB bundle) |
| Visual QA (12 screens) | 12/12 PASS — zero branding issues |
| AI accuracy (Haiku 4.5) | 85-100% on defect photos |
| AI accuracy (Gemini Flash) | 73% (budget model, improves with hints) |
| Multi-provider benchmark | 6 models tested, cost data collected |
| Template upload | Verified with 54-checkpoint Kia template |
| Template download | Styled Excel with examples, 2 sheets |
| Customer install (Kia) | Logo, colors, labels, icons all applied |
| Customer revert (default) | Clean revert to generic branding |
| Startup check (no JAR) | ERROR logged with pom.xml instructions |
| Startup check (no keys) | WARN logged listing missing providers |

---

## Cost Data (from live testing)

| Model | Cost per Image | Cost per 55-Checkpoint Audit |
|-------|:--------------:|:----------------------------:|
| Gemini 2.5 Flash | 0.02¢ | ~1¢ |
| Claude Haiku 4.5 | 0.19¢ | ~10¢ |
| GPT-4o | 0.33¢ | ~18¢ |
| Claude Sonnet 4 | 0.56¢ | ~31¢ |
