-- V1.30 — dealer-principal acknowledgement columns on inspections
--
-- M-ACK-001 was deferred during V1.28 because there was no clean way to
-- identify "who is the dealer principal for this dealership/location."
-- V1.28 added `dealer_principal_user_id` on inspections, so that constraint
-- lifts. This migration completes the ack flow:
--
--   acknowledged_at  — when the dealer principal acked the plan
--   acknowledged_by  — which user (must match the inspection's
--                      dealer_principal_user_id; service-side check)
--
-- Acknowledge is a SOFT signal: it stamps these columns but does NOT advance
-- the inspection status (status stays at ASSIGNED until the operator starts
-- the re-inspection, at which point it flips to IN_PROGRESS via the regular
-- createOrUpdate path). This means the dealer principal's ack and the
-- operator's start are independent — neither blocks the other.

ALTER TABLE IF EXISTS inspections
  ADD COLUMN IF NOT EXISTS acknowledged_at TIMESTAMP,
  ADD COLUMN IF NOT EXISTS acknowledged_by BIGINT;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.table_constraints
         WHERE constraint_name = 'fk_inspections_acknowledged_by'
    ) THEN
        ALTER TABLE inspections
          ADD CONSTRAINT fk_inspections_acknowledged_by
            FOREIGN KEY (acknowledged_by) REFERENCES users(id);
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_inspections_acknowledged_at
    ON inspections (acknowledged_at) WHERE acknowledged_at IS NOT NULL;
