# Rename Checksheet (template) → AuditTemplate across API surface

## Why

The user_checksheet → Inspection rename landed in V1.28 (the schema
collapse). The other half of the noun migration is still pending: the
**template** entity is still called `Checksheet` in code, schema, and
API.

This is confusing because:
1. End users (Kia) call it an "Audit Template" already (FE customer
   terminology overlay).
2. The API endpoint `/api/checksheet/createChecksheet` reads
   awkwardly — what's a checksheet vs an audit, again?
3. After the V1.28 collapse, "Inspection" (runtime) and "Checksheet"
   (template) are two distinct nouns in the codebase that should both
   match user-visible language.

## Scope

Two parts:

### Part 1: API surface rename (Bucket A)

Endpoints currently under `/api/checksheet/*`, `/api/checksheetValidation/*`,
`/api/checksheetApproval/*` get renamed to `/api/auditTemplate/*`,
`/api/auditTemplateValidation/*`, `/api/auditTemplateApproval/*`.
Old paths kept as deprecated aliases for one release cycle.

### Part 2: Template-authoring endpoint review (Bucket C — 30 endpoints)

The template authoring sub-flow has ~30 endpoints across:
- `ChksHeaderController` (5)
- `ChksHeaderDataController` (7)
- `ChksQuestionController` (6)
- `ChksQuestionResultController` (5)
- `ChksGeneralFieldController` (3)
- Plus assorted on `ChecksheetController` (~10)

30 endpoints to scaffold a template seems high for what's effectively
CRUD on five entities (Checksheet, Header, HeaderData, Question,
QuestionResult). Smells like CRUD-per-entity duplication. Probably
collapsible to <10 with a generic structure-edit endpoint that takes a
patch document.

**Action**: review each endpoint, document its consumer, decide:
keep / merge / delete.

## Acceptance

- [ ] `/api/auditTemplate/*` URL surface live. Old URLs return 308
      redirects with deprecation warning header.
- [ ] All Java class names `Checksheet*` → `AuditTemplate*` (Lombok
      will regenerate the getters/setters consistently).
- [ ] Schema rename `checksheets` → `audit_templates` (separate Flyway
      migration, V1.29 or later).
- [ ] Bucket C endpoint count down from ~30 to a documented and
      defensible smaller number.

## Impact

Big — touches the same kind of breadth as V1.28 did. Differs from V1.28
in that the **runtime Inspection** is unaffected; this is purely about
template authoring and demo-time impact is minimal (templates are
seeded, not created live during the demo).

Defer until **after** the demo. The user explicitly said in the
2026-05-10 design conversation: "I think it's fairly intrusive before
the demo, and I would like to show intervention if possible, so let's
take a step back and not do the schema change right now. Add it to the
list of things to do in GitLab."

## Source

API audit conversation 2026-05-10. Buckets A + C in the four-bucket
classification, combined per the user's "combine A with C" direction.

## Related

- V1.28 (collapsed inspections schema) — the runtime half of this
  noun migration, already shipped.
- Issue #15 (user_checksheets → inspections schema rename) — subsumed
  by V1.28; close as duplicate.
