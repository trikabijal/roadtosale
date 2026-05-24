-- ============================================================================
-- Tenant data migration — Honda Road to Sale: dealership + location
-- ----------------------------------------------------------------------------
-- Loaded by Flyway only when tenant.id=honda.
-- All INSERTs are idempotent (ON CONFLICT / NOT EXISTS guards).
--
-- Creates:
--   1 auditee  — "Honda Demo Dealership"  (auditees table)
--   1 location — "Honda Demo Showroom"    (auditee_locations table)
--
-- The auditee + location use fixed IDs in the 2000-block to avoid collisions
-- with the Kia seed (which uses IDs up to ~1900 for cities/dealers). The ON
-- CONFLICT (id) DO NOTHING guard makes re-runs safe.
--
-- auditee_locations requires a city_id FK (nullable in practice — the
-- Hibernate entity allows it). For the demo seed we use NULL because the
-- Honda demo is a US dealership and no US geography rows exist in the base
-- seed (Kia seeds India only). A follow-up V200.004 can add US geography
-- if the pilot needs region-scoped BI drill-down.
-- ============================================================================

BEGIN;

-- ---------------------------------------------------------------------------
-- 1. Auditee — the brand entity (Honda Demo Dealership)
-- ---------------------------------------------------------------------------
INSERT INTO auditees (id, name, code, status, created_at, created_by)
VALUES (2001, 'Honda Demo Dealership', 'HONDA_DEMO_001', 'ACTIVE', CURRENT_TIMESTAMP, 1)
ON CONFLICT (id) DO NOTHING;

-- ---------------------------------------------------------------------------
-- 2. Auditee location — the physical showroom
--    pin_code is NOT NULL per the entity; use a placeholder for the demo.
--    city_id is nullable (no US city rows seeded); left NULL here.
-- ---------------------------------------------------------------------------
INSERT INTO auditee_locations (id, auditee_id, address, pin_code, status, created_at, created_by)
VALUES (
    2001,
    2001,
    'Honda Demo Showroom, 1 Motor Drive, Demo City, CA 90001',
    '90001',
    'ACTIVE',
    CURRENT_TIMESTAMP,
    1
)
ON CONFLICT (id) DO NOTHING;

-- ---------------------------------------------------------------------------
-- 3. Advance sequences past the seeded IDs
-- ---------------------------------------------------------------------------
SELECT setval('auditees_id_seq',          GREATEST(3000, (SELECT MAX(id) FROM auditees)          + 1), false);
SELECT setval('auditee_locations_id_seq', GREATEST(3000, (SELECT MAX(id) FROM auditee_locations) + 1), false);

COMMIT;

-- Verification
SELECT al.id, al.address, al.status, a.name AS auditee
FROM auditee_locations al
JOIN auditees a ON a.id = al.auditee_id
WHERE al.id = 2001;
