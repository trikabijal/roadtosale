# Spec 3 — Reference pictures on template + multiple photos per question + re-audit photos on the report

**Status:** Proposed
**Date:** 2026-05-13

---

## 1. Functional requirement

Four independent improvements to the photo flow, packaged together because they touch the same code paths.

**A. Reference pictures on the audit template.** A template author can attach 0..N reference pictures to any question on a checksheet. Each reference picture optionally has a caption (e.g. "what good looks like" / "common defect example"). During audit fill, the auditor sees these reference pictures inline with the question — small thumbnails that expand on tap. Validators and approvers see the same reference pictures on the audit-report page so they have the template's visual context while reviewing.

**B. Multiple photos per question in the audit and re-audit.** Today an auditor captures one photo per question. After this change, they capture 0..N photos — in **both original audits (kind=AUDIT) and intervention re-audits (kind=INTERVENTION)**. The audit-fill UI shows a thumbnail strip with a "+ Add another" affordance. Each photo can be deleted individually before the inspection is submitted. All photos for a given inspection are associated with the same answer (one `chks_question_result_id`).

**C. EXIF preservation at capture.** The mobile app captures and uploads photos with their original EXIF metadata (DateTimeOriginal, GPS, Make, Model, Software) intact. The server stores the uploaded file as-is; it does **not** parse EXIF in this spec. A future trust-factor spec can read EXIF from existing files via backfill — no schema migration required at that point.

**D. Re-audit photos surface on the audit report.** Today the audit-report page renders photos from the original audit only — photos captured during intervention re-audits exist in the system but are never displayed. After this change, the audit-report page renders photos from every inspection that contributes to a question's history: the original AUDIT-kind inspection plus any related INTERVENTION-kind inspections at the same location. Photos are grouped per source inspection and labeled with the inspection's context (kind, date, actor). The audit-report layout is adjusted to accommodate multiple thumbnail rows per question — one row per contributing inspection — instead of the current single thumbnail.

---

## 2. Schema changes

| Table | Change |
|---|---|
| `chks_question_reference_pictures` (new) | One row per reference picture per question. Columns: `id BIGSERIAL PK`, `chks_question_id BIGINT NOT NULL FK → chks_questions(id)`, `file_path VARCHAR(500) NOT NULL`, `caption VARCHAR(300)`, `sort_order INTEGER NOT NULL DEFAULT 0`, `created_at TIMESTAMPTZ NOT NULL DEFAULT now()`, `created_by_user_id BIGINT FK → users(id)`. Index `(chks_question_id, sort_order)`. |
| `user_checksheet_answer_files` | Verify it already supports multiple rows per `user_checksheet_answer_id` (it likely does — it's a child table). If a unique constraint on `(user_checksheet_answer_id)` exists, drop it. No other column change. |

No change to existing `chks_questions`, `chks_question_results`, or `user_checksheet_answers`.

---

## 3. High-level API

- Template-management endpoints for the checksheet/question carry the list of reference pictures alongside the question. Creating/editing a question includes the list; the GET endpoints return it.
- Two new endpoints handle reference-picture upload and delete on a question — they mirror the existing photo-upload pattern (multipart upload, returns the stored URL).
- The existing audit-fill photo upload endpoint already accepts a `chks_question_result_id`. To support multiple photos per inspection (audit or re-audit), the client just calls it N times. No new endpoint needed.
- The audit-detail and audit-report GET endpoints now return `referencePictures[]` on each question and `files[]` (an array, populated with 0..N rows). Additionally, for each question, they return photos from any related INTERVENTION-kind inspections at the same location, grouped per source inspection with attribution (`inspectionId`, `inspectionKind`, `capturedAt`, `actorUserId`).

---

## 4. Changes from existing API

| Endpoint | Change |
|---|---|
| `POST /api/checksheet/createChecksheet` (and the question-add variant) | Accept `referencePictures[]` on each question item — a list of `{ filePath, caption?, sortOrder? }`. |
| `GET /api/checksheet/getChecksheetDetail` (or wherever the template is fetched) | Include `referencePictures[]` on each question, ordered by `sortOrder`. |
| **New** `POST /api/checksheet/question/{questionId}/referencePicture` | Multipart upload. Body: file + optional `caption` + optional `sortOrder`. Returns the created row. Permission: requires template-edit role (preparer / data-validator / data-approver as applicable). |
| **New** `DELETE /api/checksheet/referencePicture/{id}` | Removes a reference picture. Permission: same as above. |
| `POST /api/userChecksheet/createUserChksAnsFile` | No contract change. Mobile client invokes once per photo; multiple invocations per `chks_question_result_id` are now expected — applies to both AUDIT-kind and INTERVENTION-kind inspections. |
| `GET /api/userChecksheet/getUserChecksheet*` and `/audit/userChecksheetMeta` | Each answer row's `files[]` reflects the full list of photos for that inspection (was 0 or 1, now 0..N). Additionally, the response includes photos from related INTERVENTION-kind inspections at the same location, grouped per source inspection (`inspectionId`, `inspectionKind`, `capturedAt`, `actorUserId`, `files[]`). |

---

## 5. Other changes

- **Frontend (smartcomply-angular):**
  - **Template editor:** for each question, add a "Reference pictures" section with upload, caption-edit, reorder (drag), and delete. Uses the new endpoints.
  - **Audit-report page:** show reference pictures next to the question header (small thumbnails, click-to-expand). For each question, render captured photos as a stack of inspection-scoped rows:
    - One row for the original AUDIT-kind inspection's photos (0..N thumbnails).
    - One additional row per INTERVENTION-kind inspection at the same location, each labeled with the re-audit's date and actor (e.g. "Re-audit 2026-04-15 · Ramesh K").
    - Reserve vertical space accordingly — the current single-thumbnail layout must expand to accommodate multiple rows of multiple thumbnails. Empty rows are not rendered.
- **Mobile (auditpro-mobile-app, React Native):**
  - **Audit-fill question screen:** show reference pictures inline at the top of the question (collapsible, with thumbnails). Auditor can tap to view full-screen. Applies to both AUDIT-kind and INTERVENTION-kind inspections.
  - **Photo capture:** replace the single-photo capture with a thumbnail strip that supports add (camera or gallery) + delete per photo. Same UI is used in original audits and intervention re-audits. On submit, each photo is POSTed individually via the existing endpoint.
  - **EXIF preservation:** the camera/image library used for capture must preserve EXIF metadata through any client-side processing (resize, compression, HEIC→JPEG conversion). At minimum, EXIF `DateTimeOriginal`, GPS coordinates, and `Make` / `Model` must arrive in the uploaded file. The choice of library is an implementation-time decision; `react-native-image-crop-picker` is a known working option. Resize dimensions and JPEG quality are a separate decision driven by bandwidth and storage requirements — **out of scope for this spec**.
- **Backward compatibility:**
  - Existing answers with one photo continue to display as before — the array just has one element.
  - Templates with no reference pictures continue to display as before — empty array.
  - Server stores EXIF-bearing files identically to non-EXIF files; no read-side change.
- **Out of scope (deferred):**
  - Server-side EXIF extraction or analysis. Will be addressed in a future trust-factor spec; backfill from existing files is possible because EXIF lives inside the uploaded bytes.
  - Limits on number of photos or reference pictures per question — none in v1. Add caps later if abuse appears.
