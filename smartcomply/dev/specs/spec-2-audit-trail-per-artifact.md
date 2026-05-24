# Spec 2 — Audit trail per artifact

**Status:** Proposed
**Date:** 2026-05-13

---

## 1. Functional requirement

Every artifact submitted, modified, or deleted during an inspection captures the following context, automatically, on every write:

1. **Actor** — the logged-in user.
2. **Device** — the mobile device that originated the write: device UUID, model, manufacturer, OS version, app version, virtual-device (emulator) flag.
3. **Geolocation** — latitude, longitude, accuracy, and a mock-location flag (Android's `isFromMockProvider`).
4. **Network** — the IP address from which the request reached the server.
5. **Timestamps** — client time (when the action happened on the device) and server time (when the server received it).

Artifacts that record audit trail:

- Answers (created, changed, deleted)
- Photos (uploaded, deleted)
- Comments / remarks (added, edited)
- Status transitions (SUBMITTED, VALIDATED, APPROVED, DECLINED)
- Future artifact types follow the same pattern.

The audit trail surfaces as a drill-down on the audit-report page: a "history" view per question (and per inspection overall) showing the timeline of every action with actor, device, geolocation, and timestamps.

Web-originated writes (validator and approver acting from the SPA) record actor + IP address + server timestamp; device, geolocation, mock-location, and client-timestamp fields are stored as NULL.

---

## 2. Schema changes

Two new tables.

### `mobile_devices`

One row per physical device install, keyed by the Capacitor device UUID. Latest known OS / app versions live here; per-event versions are also captured in the trail table so the version-at-the-time of a specific write is preserved.

| Column | Type | Notes |
|---|---|---|
| `id` | BIGSERIAL PK | |
| `device_uuid` | VARCHAR(128) UNIQUE NOT NULL | Capacitor `Device.getId()` |
| `model` | VARCHAR(80) | "iPhone 14", "SM-G998B" |
| `manufacturer` | VARCHAR(40) | "Apple", "Samsung" |
| `latest_os_version` | VARCHAR(40) | Updated on every write from this device |
| `latest_app_version` | VARCHAR(20) | Updated on every write from this device |
| `is_virtual` | BOOLEAN NOT NULL DEFAULT FALSE | Emulator flag from `Device.getInfo().isVirtual` |
| `first_seen_at` | TIMESTAMPTZ NOT NULL DEFAULT now() | |
| `last_seen_at` | TIMESTAMPTZ NOT NULL DEFAULT now() | |

### `inspection_audit_trail`

Append-only event log. One row per artifact write.

| Column | Type | Notes |
|---|---|---|
| `id` | BIGSERIAL PK | |
| `inspection_id` | BIGINT NOT NULL FK → `inspections(id)` | |
| `artifact_type` | VARCHAR(20) NOT NULL | ANSWER, PHOTO, COMMENT, STATUS |
| `artifact_id` | BIGINT | The answer/photo/comment row id (NULL for STATUS) |
| `action` | VARCHAR(10) NOT NULL | CREATE, UPDATE, DELETE |
| `prev_value` | JSONB | Value before the change; NULL on CREATE |
| `next_value` | JSONB | Value after the change; NULL on DELETE |
| `actor_user_id` | BIGINT NOT NULL FK → `users(id)` | |
| `mobile_device_id` | BIGINT FK → `mobile_devices(id)` | NULL for web-originated writes |
| `os_version_at_event` | VARCHAR(40) | Snapshotted at write time |
| `app_version_at_event` | VARCHAR(20) | Snapshotted at write time |
| `geo_lat` | NUMERIC(9,6) | NULL when not provided |
| `geo_lng` | NUMERIC(9,6) | NULL when not provided |
| `geo_accuracy_m` | INTEGER | NULL when not provided |
| `is_mock_location` | BOOLEAN | Android `isFromMockProvider`. NULL when not provided / iOS / web |
| `ip_address` | INET | Server-observed; populated by server for every request |
| `client_ts` | TIMESTAMPTZ | NULL for web writes |
| `server_ts` | TIMESTAMPTZ NOT NULL DEFAULT now() | |

Indexes:

- `(inspection_id, server_ts DESC)` — full inspection timeline
- `(artifact_type, artifact_id, server_ts DESC)` — history of one artifact
- `(actor_user_id, server_ts DESC)` — what one user has done across audits

---

## 3. High-level API

- Every existing write endpoint accepts an optional `clientContext` block carrying device, geolocation, and client-timestamp data.
- On every write, the server:
  1. Upserts the `mobile_devices` row keyed by `clientContext.device.uuid`, refreshing `latest_os_version`, `latest_app_version`, and `last_seen_at`.
  2. Inserts one `inspection_audit_trail` row per artifact written. The server populates `ip_address` and `server_ts` itself; everything else comes from the `clientContext` block (or is NULL).
- One new read endpoint exposes the trail for an inspection or a single artifact within it, joined with `mobile_devices` so the consumer sees device details without a second call.

---

## 4. Changes from existing API

| Endpoint | Change |
|---|---|
| `POST /api/userChecksheet/createOrUpdate` | Accept `clientContext`. On status change, emit one STATUS trail row. |
| `POST /api/userChecksheet/createOrUpdateUserChksAns` | Accept `clientContext`. Emit one ANSWER trail row per answer written (CREATE if new, UPDATE if existing). |
| `POST /api/userChecksheet/createUserChksAnsFile` | Accept `clientContext`. Emit one PHOTO trail row (CREATE). |
| `DELETE /api/userChecksheet/userChksAnsFile/{id}` (or equivalent) | Accept `clientContext`. Emit one PHOTO trail row (DELETE). |
| `POST /api/userChecksheetValidation/...` | Accept `clientContext`. Emit one STATUS trail row. |
| `POST /api/userChecksheetApproval/...` | Accept `clientContext`. Emit one STATUS trail row. |
| **New** `GET /api/inspection/{inspectionId}/auditTrail` | Optional query params: `artifactType`, `artifactId`. Returns the timeline rows joined with `mobile_devices`. |

The `clientContext` block shape:

```json
{
  "device": {
    "uuid": "abc-123-def",
    "model": "SM-G998B",
    "manufacturer": "Samsung",
    "osVersion": "Android 14",
    "appVersion": "1.4.2",
    "isVirtual": false
  },
  "geolocation": {
    "lat": 28.6139,
    "lng": 77.2090,
    "accuracyM": 12,
    "isMock": false
  },
  "clientTimestamp": "2026-05-13T14:32:11+05:30"
}
```

All sub-fields optional individually. Server stores whatever it receives. If `clientContext` is entirely absent, the trail row is still written with actor + IP + server timestamp; the device, geolocation, mock-location, and client-timestamp fields are NULL.

---

## 5. Other changes

- **Frontend (smartcomply-angular):**
  - Add a "History" link/icon next to each question on the audit-report page. Clicking opens a side drawer that calls `GET /api/inspection/{id}/auditTrail?artifactType=ANSWER&artifactId={answerId}` and renders the timeline (actor name, device summary, geolocation as a small map pin, timestamps).
  - Add an inspection-level "View full audit trail" link that opens the same drawer scoped to the whole inspection.
- **Mobile (auditpro-mobile-app):**
  - Integrate `@capacitor/device` (for device UUID, model, manufacturer, OS version, isVirtual) and `@capacitor/geolocation` (for lat/lng/accuracy + the Android `isFromMockProvider` flag).
  - Before every write, build the `clientContext` block and attach it to the request body.
  - If geolocation permission is denied or unavailable, send `clientContext` with `device` populated and `geolocation` omitted — the server stores what it gets.
- **Backward compatibility:** existing clients that don't send `clientContext` continue to work unchanged. Their writes generate trail rows with NULL device / geo / client-ts fields. Actor, IP, and server timestamp are always populated.
- **Existing data:** no backfill. The trail starts the day this lands. Audits in flight at cutover have partial history.
