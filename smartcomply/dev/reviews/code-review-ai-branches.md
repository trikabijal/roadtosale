# Code Review: AI Photo Assessment Feature

**Branch:** `feat/ai-photo-assessment` vs `development`  
**Reviewer:** Claude Opus 4.6 (automated)  
**Date:** 2026-04-25  
**Commits reviewed:** 5 (a00cdcc..ee32c55)

---

## Phase 0: Gates

### 0.1 Linting / Compilation

**PASS.** `./mvnw compile` succeeds (BUILD SUCCESS). Two pre-existing warnings from unrelated files (User.java hashCode, EndpointRegistry.java deprecation) -- not introduced by this branch.

### 0.2 Reuse & Duplication

**nit** -- All three providers (`AnthropicVisionProvider`, `OpenAiVisionProvider`, `GeminiVisionProvider`) share an identical code structure: validate key, build headers, build body, call `HttpClientFactory.postWithRetry()`, parse response. This is intentional (documented in architecture.md as a design decision), and each provider's request/response format is different enough that a shared base class would add complexity without reducing code. No action needed.

No copy-paste duplication detected between providers beyond the structural pattern. The `CostBreakdown.compute()` vs `CostBreakdown.computeAnthropic()` split is appropriate given Anthropic's unique cache-creation/read distinction.

---

## Phase 1: Correctness

### 1.1 Code Path Tracing

All paths traced successfully:

- `AiAssessmentController.assessPhoto()` -> `AiAssessmentServiceImpl.assessPhoto()` -> `LlmClient.assessImage()` -> `BenchmarkingLlmClient` (decorator) -> spending guard check -> provider `callApi()` -> `HttpClientFactory.postWithRetry()` -> parse response -> record cost -> return. Flow is correct.
- Admin controller -> `requireAdmin()` -> `PermissionService.hasPermission("SUPER_ADMIN")` -> `BenchmarkingLlmClient.setEnabled()` / `getSpendingGuard()`. Correct.
- Entity fields align with migration columns (V1.19 + V1.20). All columns in the entity exist in the DDL. Indexes match.

### 1.2 Finding: Gemini default model not in LlmModel enum

**warning** -- `GeminiVisionProvider` defaults to `gemini-2.0-flash` (line 26), but the `LlmModel` enum has no entry with `apiModelId = "gemini-2.0-flash"`. `LlmModel.fromApiModelId("gemini-2.0-flash")` returns `null`. This means:
- Cost estimation returns `$0.00` for all Gemini calls using the default model.
- The spending guard records `$0.00` for Gemini calls, so Gemini usage does not count toward the spending limit.
- Benchmark results show `cost=$0.000000` for Gemini.

**File:** `src/main/java/com/checkSheet/llm/LlmModel.java`  
**Fix:** Add `GEMINI_2_0_FLASH("gemini-2.0-flash", LlmProvider.GOOGLE_GEMINI, 0.10, 0.40, "Gemini 2.0 Flash")` to the enum (verify current pricing with Google).

### 1.3 Test Coverage

All 34 AI-specific tests pass (17 service, 6 controller, 4+3+4 provider tests). The pre-existing `ApplicationTests.contextLoads` fails due to missing DB connection -- unrelated to this branch.

**Covered:**
- Happy path (assess, retrieve single, retrieve list)
- Auth failure, null photo, empty photo, unsupported media type, checksheet not found, checkpoint not found
- JSON parsing (valid, markdown-wrapped, invalid judgement, clamped confidence, missing confidence, garbage text)
- Prompt construction
- Provider: missing API key, response parsing, missing usage block

**warning** -- Missing test coverage:
1. No tests for `AiAdminController` (toggle benchmark, get status, reset spending guard).
2. No tests for `SpendingGuard` (trip on cost limit, trip on call limit, reset, concurrent access).
3. No tests for `BenchmarkingLlmClient` (fan-out behavior, shadow failure isolation, spending guard integration).
4. No tests for `CostBreakdown` (compute with known model, compute with unknown model, Anthropic cache-creation cost).

**File:** Test files under `src/test/java/com/checkSheet/`  
**Fix:** Add unit tests for the above. At minimum, `SpendingGuard` and `CostBreakdown` should have tests since they handle money.

### 1.4 Edge Cases

**warning** -- No file size limit configured for photo uploads. Spring Boot's default `spring.servlet.multipart.max-file-size` is 1MB, and `max-request-size` is 10MB. A large photo (e.g., 20MB RAW) will be Base64-encoded (33% larger), creating a ~27MB string in memory before being sent to the LLM API. LLM providers have their own image size limits (Anthropic: 20MB, OpenAI: 20MB, Gemini: 20MB), but the app should reject oversized uploads early.

**File:** `src/main/resources/application.properties` (or equivalent)  
**Fix:** Add explicit multipart size limits:
```properties
spring.servlet.multipart.max-file-size=10MB
spring.servlet.multipart.max-request-size=15MB
```

### 1.5 Security

**blocker** -- **No authorization check on assessment endpoints.** `AiAssessmentController` endpoints (`/api/ai/assess`, `/api/ai/assessment`, `/api/ai/assessments`) only verify that the user is authenticated (JWT required, not in whitelist), but do not check:
1. Whether the authenticated user has permission to access the specified `userChecksheetId`.
2. Whether the `chksQuestionResultId` belongs to the specified `userChecksheetId`.

Any authenticated user can assess photos for any checksheet and read any other user's AI assessments by guessing IDs. This is an IDOR (Insecure Direct Object Reference) vulnerability.

**File:** `src/main/java/com/checkSheet/service/AiAssessmentServiceImpl.java`, lines 91-95  
**Fix:** After loading `userChecksheet`, verify the current user is the assigned auditor or has an appropriate permission (e.g., `CHECKSHEET_FILL_ANSWER`). Also verify that `chksQuestionResultId` belongs to a question in the loaded checksheet.

---

**warning** -- **Path traversal via original filename.** Line 126 of `AiAssessmentServiceImpl`:
```java
String fileName = UUID.randomUUID() + "_" + photo.getOriginalFilename();
```
`getOriginalFilename()` is user-controlled and could contain `../` sequences. The UUID prefix helps, but the concatenation could still produce a path like `abc123_../../etc/passwd`. Whether this is exploitable depends on `FileStorageUtil.storeFile()` implementation.

**File:** `src/main/java/com/checkSheet/service/AiAssessmentServiceImpl.java`, line 126  
**Fix:** Sanitize the filename: `photo.getOriginalFilename().replaceAll("[^a-zA-Z0-9._-]", "_")` or just use the UUID alone with the correct extension.

---

**nit** -- `@CrossOrigin(origins = "*")` is on all controllers. This is a pre-existing pattern across the entire codebase (22 controllers), not introduced by this branch. Flagging it as it allows any origin to make authenticated requests. Should be addressed project-wide.

### 1.6 Error Propagation

Error propagation is correct:
- `LlmException` -> caught in `AiAssessmentServiceImpl` -> wrapped as `CustomException` with mapped HTTP status -> caught in controller -> `ResponseDTO` with status false.
- Parse failures from `parseAiResponse()` -> `CustomException` (500 default).
- Provider-specific `RestClientException` -> `LlmException(502)` -> `CustomException(502)`.

No swallowed errors found.

### 1.7 Concurrency

**warning** -- **SpendingGuard race condition on `reset()`.** The `reset()` method (line 56-60) sets three fields non-atomically:
```java
spentMicros.set(0);
callCount.set(0);
tripped = false;
```
If a concurrent call to `recordCall()` happens between `spentMicros.set(0)` and `tripped = false`, the call count could increment from 0 while `tripped` is still true, meaning `checkBeforeCall()` still rejects. This is a minor window -- admin resets are rare and the outcome is conservative (rejects when it shouldn't, not the reverse). Low risk.

**File:** `src/main/java/com/checkSheet/llm/SpendingGuard.java`, lines 56-60  
**Fix:** Consider setting `tripped = false` first, or use a single `synchronized` block for `reset()`.

---

**nit** -- `BenchmarkingLlmClient.enabled` is `volatile`, ensuring visibility. The shadow provider list and executor are set once at construction and never modified. Thread safety looks correct.

### 1.8 Data Integrity

- Migration V1.19 creates the table with proper foreign keys and indexes. V1.20 adds provider/token columns.
- Entity column definitions match the DDL (`VARCHAR(500)`, `VARCHAR(10)`, `TEXT`, `DOUBLE PRECISION`, `BIGINT`, `INTEGER`, `TIMESTAMP`).
- Composite index `idx_ai_assessments_lookup` on `(user_checksheet_id, chks_question_result_id)` matches the repository query pattern. Good.
- The individual indexes on `user_checksheet_id` and `chks_question_result_id` are redundant given the composite index covers the first column, but the second standalone index is useful. Minor inefficiency, not worth changing.

---

## Phase 2: Portability

### 2.1 Environment

- No hardcoded paths or URLs except the three provider API endpoints (correct -- these are stable public APIs).
- API keys configured via `@Value` with env var fallbacks (`${ANTHROPIC_KEY:}`). Good.
- `.env.example` documents all required variables. Good.
- Gemini passes the API key as a URL query parameter (`?key=apiKey`). This is Google's standard approach for this API, but be aware that API keys in URLs can appear in access logs and proxy logs.

### 2.2 Dependencies

No new Maven dependencies were added. The code uses `org.json`, `spring-web` (RestTemplate), and `lombok` -- all already in the project's dependency tree. Clean.

---

## Phase 3: Cost

### 3.1 API Cost Accuracy

**warning** -- **`CostBreakdown.compute()` and `publishResult()` calculate cost differently.** Looking at `BenchmarkingLlmClient`:
- `recordCost()` (line 117-120) calls `computeCost()` which dispatches to `CostBreakdown.compute()` or `CostBreakdown.computeAnthropic()`. This correctly accounts for cached token pricing.
- But `computeCost()` is called twice per response in the benchmark path: once in `recordCost()` and once in `publishResult()`. The computation is pure (no side effects), but it is redundant.

**File:** `src/main/java/com/checkSheet/llm/BenchmarkingLlmClient.java`, lines 117-131  
**Fix (nit):** Cache the `CostBreakdown` result and pass it to both `recordCost` and `publishResult`.

---

**warning** -- As noted in 1.2, the default Gemini model `gemini-2.0-flash` has no pricing entry in `LlmModel`, so all Gemini costs are estimated as $0.00. This undermines the spending guard for Gemini-as-primary configurations.

### 3.2 Unnecessary Calls

No redundant API calls found. The flow is: one LLM call per assessment (plus shadow calls only when benchmarking is enabled). Photo is stored only after a successful LLM call (line 127), preventing orphan files.

### 3.3 Credit Safety

The spending guard correctly:
- Checks before every call (primary and shadow).
- Records cost after every successful call.
- Trips on either dollar limit or call count limit.
- Stays tripped until admin reset.
- Uses AtomicLong/AtomicInteger for thread-safe updates.

One subtle behavior: the call that causes the trip still succeeds (the guard trips after `recordCall`, not before). This is documented and acceptable -- the alternative (pre-calculating cost) would require knowing the token count before the API call, which is not possible.

---

## Phase 4: Customer Impact

### 4.2 Error Messages

Error messages are user-appropriate and do not leak internal details:
- `"Anthropic AI service is unavailable. Please try again later."` -- good, no stack traces.
- `"Unsupported image format. Supported: JPEG, PNG, GIF, WebP"` -- helpful.
- `"LLM spending guard tripped. Spent: $X / $Y limit..."` -- this message from `SpendingGuard` is exposed to the end user via the 429 response. It reveals internal spending data.

**warning** -- The spending guard trip message (SpendingGuard line 32-33) includes dollar amounts and call counts. When this propagates to the end user via `LlmException` -> `CustomException` -> `ResponseDTO`, it leaks internal cost data.

**File:** `src/main/java/com/checkSheet/llm/SpendingGuard.java`, line 32  
**Fix:** Use a generic message for the exception: `"AI service temporarily unavailable. Please contact your administrator."` Log the detailed spend info at ERROR level instead.

---

## Phase 5: Code Quality

### 5.1 Facade Compliance

**PASS.** The LLM module (`com.checkSheet.llm`) has zero imports from the host application. Verified by checking all import statements in all 11 files in the `llm` package -- they only import from `org.springframework`, `org.json`, `org.slf4j`, `lombok`, and `java.*`. The module can be extracted into its own JAR without modification.

The host app interacts with the module only through:
- `LlmClient` interface (injected into `AiAssessmentServiceImpl`)
- `BenchmarkingLlmClient` (instanceof-checked in `AiAdminController`)
- `LlmException` (caught and wrapped in `AiAssessmentServiceImpl`)
- `LlmConfig` properties (set via `application.properties`)

### 5.2 Readability

Code is clean and well-organized. Method names are descriptive (`assessImage`, `buildUserPrompt`, `parseAiResponse`, `extractJson`, `checkBeforeCall`, `recordCall`). The service impl follows a numbered step pattern with clear comments. The decorator pattern in `BenchmarkingLlmClient` is straightforward.

### 5.3 Code Smells

**nit** -- `AiAssessmentServiceImpl.SYSTEM_PROMPT` is a 15-line text block constant embedded in the service. As the prompt evolves, consider moving it to a resource file or making it configurable. Not urgent.

**nit** -- `LlmProvider.value` is redundant with `name()`. The field is always set to `this.name()` (line 15). Consider removing it and using `name()` directly. Low priority since it works correctly.

### 5.4 Dead Code

No dead code found. No unused imports. No commented-out blocks in production code.

### 5.5 Documentation Accuracy

The three LLM module docs (`llm/docs/api.md`, `architecture.md`, `flows.md`) are thorough and accurate. They match the current code:
- All public methods documented with correct signatures.
- Architecture diagram matches actual class hierarchy.
- Flow traces match actual call chains.
- Cost estimation notes correctly describe the cached-token pricing difference.
- Spending guard behavior documented accurately.

**nit** -- `flows.md` line 37 says "Stores the photo to disk via `FileStorageUtil.storeFile()`" as step before the LLM call, but the actual code stores the photo AFTER the LLM call (step 9, line 127). The flow doc's step ordering doesn't match the code.

**File:** `src/main/java/com/checkSheet/llm/docs/flows.md`, line 37  
**Fix:** Update Flow 1's step-by-step to reflect that photo storage happens after the LLM call.

### 5.6 Efficiency

- Base64 encoding an image in memory (line 108) is unavoidable for the API format.
- `extractJson()` handles three cases (markdown code block, bare code block, brace extraction) with simple string operations. Efficient.
- `HttpClientFactory.postWithRetry()` with exponential backoff + jitter is well-implemented.

### 5.7 Observability

- Benchmark results are logged as structured key=value pairs. Good for log aggregation.
- Spending guard trips log at ERROR level. Good.
- Shadow failures log at WARN level. Good.
- Provider API failures log with full exception context. Good.

**warning** -- `AiAssessmentServiceImpl.parseAiResponse()` logs the full raw AI response text at ERROR level on parse failure (line 213):
```java
logger.error("Failed to parse AI response: {}", textContent, e);
```
If the AI returns unexpected content (e.g., due to prompt injection), this raw content ends up in logs. Low risk since the input is model output, not user input, but worth noting.

---

## Summary

| Severity | Count |
|----------|-------|
| **Blocker** | 1 |
| **Warning** | 8 |
| **Nit** | 6 |

### Blockers (must fix before merge)

1. **IDOR vulnerability** (1.5) -- No authorization check that the current user owns/has access to the specified `userChecksheetId`. Any authenticated user can assess and read assessments for any checksheet.

### Warnings (should fix before merge)

1. **Gemini default model missing from LlmModel enum** (1.2) -- Cost estimation returns $0 for default Gemini config.
2. **Missing test coverage** (1.3) -- No tests for AdminController, SpendingGuard, BenchmarkingLlmClient, CostBreakdown.
3. **No explicit file size limit** (1.4) -- Relies on Spring Boot defaults; should be explicit.
4. **Path traversal via original filename** (1.5) -- `getOriginalFilename()` is unsanitized.
5. **SpendingGuard.reset() non-atomic** (1.7) -- Minor race window during admin reset.
6. **Gemini cost = $0 undermines spending guard** (3.1) -- Same root cause as #1 above.
7. **Spending guard trip message leaks cost data** (4.2) -- Internal dollar amounts exposed to end users.
8. **flows.md step ordering mismatch** (5.5) -- Doc says photo stored before LLM call; code stores it after.

### Recommendation: **FAIL -- fix blocker #1 (IDOR) before merge.**

The IDOR vulnerability is a clear security issue. The remaining warnings are important but could be addressed in a follow-up commit on the same branch before merge.
