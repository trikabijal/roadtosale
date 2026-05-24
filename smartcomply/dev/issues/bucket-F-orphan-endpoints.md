# Triage orphan API endpoints (Surprise / NPD / APIHistory / /hello)

## Why

During the API surface audit ahead of the V1.28 collapse, we found four
endpoint clusters that don't fit anywhere in the audit / template / BI
flow. They run in production with fail-soft authorization (any
authenticated caller passes), which means if any of them is wired to
real data, it's effectively unprotected.

## What

Decide for each whether to keep / migrate / delete:

| Endpoint cluster | Surface | Question |
|---|---|---|
| `POST /api/surpriseChecksheet/createSurpriseChecksheets` and `createSurpriseChecksheetFields` | `SurprizeChecksheetController.java` (note: spelled "surprize") | Used anywhere? Mobile / web? If unused, delete. |
| `POST /api/npd/*` (8 endpoints — create/update/delete/search/get/etc.) | `NpdMasterController.java` | What is "NPD"? Tenant-specific master data? |
| `/api/apiHistory` | `APIHistoryContoller.java` (note: typo "Contoller") — controller class exists with `@RequestMapping("/api/apiHistory")` but **no methods** declared | Empty controller. Delete or finish. |
| `GET /api/user/hello` | `UserController.java` healthcheck stub | Move to `/actuator/health` (Spring standard) or delete. |

## Acceptance

For each cluster:
- [ ] Confirm if any consumer (web FE, mobile app) calls it. Quick grep across `smartcomply-angular` and `auditpro-mobile-app` is enough.
- [ ] If unused → delete the controller + service files.
- [ ] If used → document the consumer in `docs/api.md` and add it to the authorization map (replace fail-soft with proper role check).

## Impact

Pre-demo: low (fail-soft auth keeps things working). Post-demo, when we
flip to deny-by-default (issue #16), un-mapped routes block. So this
needs to be done before that flip lands.

## Source

API audit conversation 2026-05-10. Bucket F in the four-bucket
classification.
