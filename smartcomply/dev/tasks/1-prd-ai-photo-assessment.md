# PRD: Photo-Based AI Assessment API

## Overview

Add an API endpoint that accepts a photograph for a checksheet line item (checkpoint) and returns an AI-generated assessment — **OK** or **NOT OK** — with an explanation. The auditor takes a photo during a facility audit; the system evaluates it against the checkpoint's criteria using an AI vision model and returns a suggested judgement.

This is **Capability #1** from the AI automation roadmap (`docs/architecture.md` §"AI photo assessment"). It covers ~35 of 44 subjective checkpoints (63% of total template).

---

## Problem Statement

Today, all checksheet checkpoints are assessed manually by an auditor walking through a facility. For subjective condition checks (damage, cleanliness, compliance, paint, organization), the auditor visually inspects and selects OK/NOT OK from a dropdown. This is:

- **Slow** — 44 subjective checkpoints assessed one-by-one
- **Inconsistent** — different auditors may judge the same condition differently
- **Unverifiable** — no photographic evidence is stored with the answer

---

## Solution

### API Contract

**Endpoint:** `POST /api/ai/assess`

**Content-Type:** `multipart/form-data`

**Request Parameters:**

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `photo` | `MultipartFile` | Yes | Photo of the element being assessed |
| `userChecksheetId` | `Long` | Yes | The user checksheet instance this assessment belongs to |
| `chksQuestionResultId` | `Long` | Yes | The specific checkpoint (question result) to evaluate against |

**Response (JSON):**

```json
{
  "status": true,
  "message": "AI assessment completed",
  "data": {
    "id": 42,
    "suggestedJudgement": "NOT_OK",
    "explanation": "Small dent visible on the upper-right section of the ACP panel. Logo appears intact.",
    "confidence": 0.87,
    "photoUrl": "/api/checksheet/doc/ai-assessments/photo_1234.jpg",
    "chksQuestionResultId": 15,
    "userChecksheetId": 100,
    "assessedAt": "2026-04-25T10:30:00Z"
  }
}
```

### How It Works

1. **Auditor** opens a checkpoint on the mobile app and takes a photo.
2. **Frontend** sends the photo + checkpoint IDs to `POST /api/ai/assess`.
3. **Backend** does the following:
   a. Validates the request (checkpoint exists, user has access to this checksheet).
   b. Stores the photo via `FileStorageUtil` under `ai-assessments/`.
   c. Loads the checkpoint context:
      - `ChksQuestion.name` — the checkpoint name (e.g., "Front ACP + Logo — Damage")
      - `ChksQuestion.description` — the checkpoint description
      - `ChksQuestionResultOption` entries — the OK and NOT OK criteria text
   d. Calls the AI vision API (Anthropic Claude) with:
      - The photo (base64-encoded)
      - A prompt constructed from the checkpoint context
   e. Parses the AI response into a structured result.
   f. Persists the assessment in a new `AiAssessment` table.
   g. Returns the result to the frontend.
4. **Auditor** reviews the AI suggestion and can **accept** or **override** it when submitting their answer.

### AI Prompt Construction

The prompt is constructed generically from existing checksheet data — no hardcoded checkpoint logic:

```
You are an expert facility auditor. Evaluate the following checkpoint based on the provided photo.

Checkpoint: {ChksQuestion.name}
Description: {ChksQuestion.description}

Criteria:
{For each ChksQuestionResultOption:}
- {option.judgement}: {option.option}

Based on the photo, determine if this checkpoint passes or fails.

Respond in this exact JSON format:
{
  "judgement": "OK" or "NOT_OK",
  "explanation": "Brief explanation of what you observed",
  "confidence": 0.0 to 1.0
}
```

---

## Data Model

### New Entity: `AiAssessment`

| Column | Type | Description |
|--------|------|-------------|
| `id` | `BIGINT PK` | Auto-generated ID |
| `user_checksheet_id` | `BIGINT FK` | Links to `user_checksheets` |
| `chks_question_result_id` | `BIGINT FK` | Links to `chks_question_results` |
| `photo_path` | `VARCHAR` | Stored photo file path |
| `suggested_judgement` | `VARCHAR` | AI's suggestion: `OK` or `NOT_OK` |
| `explanation` | `TEXT` | AI's reasoning |
| `confidence` | `DOUBLE` | AI confidence score (0.0–1.0) |
| `ai_model` | `VARCHAR` | Model used (e.g., `claude-sonnet-4-20250514`) |
| `prompt_sent` | `TEXT` | The full prompt sent to AI (audit trail) |
| `raw_response` | `TEXT` | Raw AI response (audit trail) |
| `assessed_at` | `TIMESTAMP` | When assessment was performed |
| `created_at` | `TIMESTAMP` | Row creation time |
| `created_by` | `BIGINT FK` | User who triggered the assessment |

### New Flyway Migration: `V1.19__create_ai_assessments_table.sql`

---

## Components to Build

### 1. Entity: `AiAssessment`
JPA entity following existing patterns (soft delete, audit columns, Lombok).

### 2. Repository: `AiAssessmentRepository`
Spring Data JPA repository with finders:
- `findByUserChecksheetIdAndChksQuestionResultId(Long, Long)`
- `findByUserChecksheetId(Long)`

### 3. DTO: `AiAssessmentDTO`
Response DTO matching the API contract above.

### 4. Service Interface: `AiAssessmentService`
```java
ResponseDTO<?> assessPhoto(MultipartFile photo, Long userChecksheetId, Long chksQuestionResultId);
ResponseDTO<?> getAssessment(Long userChecksheetId, Long chksQuestionResultId);
```

### 5. Service Implementation: `AiAssessmentServiceImpl`
Orchestrates: validation → file storage → prompt building → AI call → parse → persist → respond.

### 6. AI Client: `AiVisionClient`
Dedicated client for calling the Anthropic Claude API:
- Takes: base64 image + prompt
- Returns: parsed AI response (judgement, explanation, confidence)
- Configurable via `application.properties`:
  - `ai.anthropic.api-key` — API key (from `.env`)
  - `ai.anthropic.model` — model to use (default: `claude-sonnet-4-20250514`)

### 7. Controller: `AiAssessmentController`
- `POST /api/ai/assess` — run AI assessment
- `GET /api/ai/assessment?userChecksheetId=X&chksQuestionResultId=Y` — retrieve existing assessment

### 8. Flyway Migration
Create the `ai_assessments` table.

---

## Configuration

Add to `application-uat.properties` (and `.env`):

```properties
ai.anthropic.api-key=${AI_ANTHROPIC_API_KEY}
ai.anthropic.model=claude-sonnet-4-20250514
ai.anthropic.max-tokens=1024
```

Add `AI_ANTHROPIC_API_KEY` to `.env.example`.

---

## Security

- The endpoint requires JWT authentication (existing `JwtAuthenticationFilter` handles this).
- The API key for Anthropic is stored in environment variables, never in code or properties files committed to git.
- Photos are stored on the server filesystem under the existing `file.uploadDir` path.

---

## Scope Boundaries

**In scope:**
- Single photo → single checkpoint assessment
- Subjective condition checkpoints (SUBJECTIVE_CONDITION type)
- OK/NOT_OK judgement with explanation and confidence
- Persistent storage of assessments for audit trail
- Retrieval of existing assessments

**Out of scope (future work):**
- Object counting for OBJECTIVE checkpoints (Capability #2)
- Reference image comparison (Capability #3)
- Video walkthrough (Capability #4)
- Auto-updating `UserChecksheetAnswer` — the auditor explicitly accepts/rejects the AI suggestion from the frontend
- Batch assessment of multiple checkpoints from one photo

---

## Dependencies

- **Anthropic Java SDK** — HTTP client for Claude API (or use RestTemplate/WebClient with direct API calls)
- **Existing infrastructure** — `FileStorageUtil`, `ResponseDTO`, JWT auth, Spring async

---

## Success Criteria

1. Auditor can upload a photo for any SUBJECTIVE_CONDITION checkpoint and receive an AI judgement within 5–10 seconds.
2. The AI response includes a clear explanation referencing what was observed in the photo.
3. All assessments are persisted with full audit trail (prompt, response, photo, model used).
4. The API handles errors gracefully — invalid checkpoint, unsupported answer type, AI service unavailable.
5. Existing checksheet flows are not affected — this is a new, additive API.
