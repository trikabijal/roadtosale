-- Dedupe duplicate auditee_locations rows.
--
-- The seed import created multiple rows per dealer with identical addresses
-- (one per service line — sales/service/parts — but the audit model treats
-- a "location" as a physical site, so they collapse to one row per
-- (auditee_id, address)). 417 duplicate groups, 527 duplicate rows.
--
-- For each (auditee_id, address) group we keep the lowest-id row as canonical,
-- collapse audit_assignments that reference duplicates onto a single winner
-- per (audit_id, canonical_loc_id), re-parent user_checksheets from losers
-- to the winner, soft-delete the losing assignments and the duplicate
-- auditee_locations rows.

-- Replace the table-wide unique constraint on audit_assignments with a
-- partial unique index that only counts active rows. The original
-- constraint blocks the dedupe (soft-deleted losers conflict with
-- redirected winners) and is also wrong semantically — once a row is
-- soft-deleted, it should not occupy the unique slot.
ALTER TABLE audit_assignments DROP CONSTRAINT IF EXISTS uk_audit_assignments_audit_loc;
CREATE UNIQUE INDEX IF NOT EXISTS uk_audit_assignments_audit_loc_active
  ON audit_assignments (audit_id, auditee_location_id)
  WHERE deleted_at IS NULL;

CREATE TEMP TABLE _dup_loc_map AS
WITH groups AS (
  SELECT auditee_id, address, MIN(id) AS canonical_id, array_agg(id ORDER BY id) AS all_ids
    FROM auditee_locations
   WHERE deleted_at IS NULL
   GROUP BY auditee_id, address
  HAVING COUNT(*) > 1
)
SELECT canonical_id, unnest(all_ids[2:]) AS dup_id FROM groups;

CREATE INDEX ON _dup_loc_map (dup_id);

-- For every active audit_assignment whose location is a duplicate (or is the
-- canonical of a dup group), compute the target canonical loc id.
CREATE TEMP TABLE _aa_target AS
SELECT aa.id AS aa_id,
       aa.audit_id,
       aa.auditee_location_id AS current_loc_id,
       COALESCE(m.canonical_id, aa.auditee_location_id) AS canonical_loc_id
  FROM audit_assignments aa
  LEFT JOIN _dup_loc_map m ON aa.auditee_location_id = m.dup_id
 WHERE aa.deleted_at IS NULL
   AND (
        m.canonical_id IS NOT NULL
        OR aa.auditee_location_id IN (SELECT canonical_id FROM _dup_loc_map)
   );

CREATE INDEX ON _aa_target (audit_id, canonical_loc_id);
CREATE INDEX ON _aa_target (aa_id);

-- Pick a single winner per (audit_id, canonical_loc_id): the lowest-id AA.
CREATE TEMP TABLE _aa_winner AS
SELECT DISTINCT ON (audit_id, canonical_loc_id)
       audit_id, canonical_loc_id, aa_id AS winner_aa_id
  FROM _aa_target
 ORDER BY audit_id, canonical_loc_id, aa_id;

CREATE INDEX ON _aa_winner (audit_id, canonical_loc_id);

-- Loser map: every aa_target row that isn't the winner for its group.
CREATE TEMP TABLE _aa_loser AS
SELECT t.aa_id AS loser_aa_id, w.winner_aa_id
  FROM _aa_target t
  JOIN _aa_winner w
    ON w.audit_id = t.audit_id
   AND w.canonical_loc_id = t.canonical_loc_id
 WHERE t.aa_id <> w.winner_aa_id;

CREATE INDEX ON _aa_loser (loser_aa_id);

-- Step 1: re-parent user_checksheets from losers to winners.
UPDATE user_checksheets uc
   SET audit_assignment_id = l.winner_aa_id,
       updated_at = CURRENT_TIMESTAMP
  FROM _aa_loser l
 WHERE uc.audit_assignment_id = l.loser_aa_id;

-- Step 2: soft-delete loser audit_assignments.
UPDATE audit_assignments
   SET deleted_at = CURRENT_TIMESTAMP,
       updated_at = CURRENT_TIMESTAMP
 WHERE id IN (SELECT loser_aa_id FROM _aa_loser);

-- Step 3: redirect winner audit_assignments that are still pointing at a
-- duplicate location onto the canonical location. (Winners that already
-- point at the canonical are no-ops.) The unique constraint
-- (audit_id, auditee_location_id) is safe here because we picked exactly
-- one winner per (audit_id, canonical_loc_id) and the soft-deleted losers
-- still satisfy the constraint as long as no other active row claims the
-- same (audit_id, canonical_loc_id) — which is what the winner-selection
-- guarantees.
UPDATE audit_assignments aa
   SET auditee_location_id = m.canonical_id,
       updated_at = CURRENT_TIMESTAMP
  FROM _dup_loc_map m
 WHERE aa.auditee_location_id = m.dup_id
   AND aa.deleted_at IS NULL;

-- Step 4: soft-delete the duplicate auditee_locations rows.
UPDATE auditee_locations al
   SET deleted_at = CURRENT_TIMESTAMP,
       updated_at = CURRENT_TIMESTAMP
  FROM _dup_loc_map m
 WHERE al.id = m.dup_id
   AND al.deleted_at IS NULL;

DROP TABLE _aa_loser;
DROP TABLE _aa_winner;
DROP TABLE _aa_target;
DROP TABLE _dup_loc_map;
