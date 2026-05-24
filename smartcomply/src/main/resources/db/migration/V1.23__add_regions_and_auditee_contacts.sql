-- ============================================================================
-- V1.23: Region entity + auditees contact columns + BI indexes
-- ----------------------------------------------------------------------------
-- Context: JV's dealership/auditee tables (auditees, auditee_locations,
-- auditee_types, countries, states, cities) are created by Hibernate
-- ddl-auto=update at first boot — they are NOT managed by Flyway. This
-- migration adds the new region entity and BI-supporting indexes in a way
-- that is safe for both fresh DBs (where ddl-auto creates the tables AFTER
-- this migration runs) and existing DBs like UAT (where the tables are
-- already there).
--
-- Strategy:
--   - regions table is created here (it's brand new, so always safe).
--   - states.region_id is added by ALTER ... IF EXISTS — no-op on fresh DB,
--     where ddl-auto will create it later from the JPA entity definition.
--   - Indexes are added inside DO blocks gated on table existence.
--
-- Limitation: states.region_id NOT NULL constraint cannot be enforced here
-- against a UAT DB whose states table predates this migration (ALTER ... SET
-- NOT NULL would fail if any row has a NULL region_id). The seed script
-- (scripts/seed-kia-dealerships.sql) populates region_id for every state it
-- inserts; once all rows have a value, run a follow-up V1.24 migration to
-- enforce NOT NULL at the DB level. The JPA entity already enforces it at
-- application-write time.
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. regions table
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS regions (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR NOT NULL,
    country_id  BIGINT,
    created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP,
    deleted_at  TIMESTAMP,
    created_by  BIGINT NOT NULL,
    updated_by  BIGINT,
    deleted_by  BIGINT
);

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'countries')
       AND NOT EXISTS (
           SELECT 1 FROM information_schema.table_constraints
           WHERE constraint_name = 'fk_regions_country_id_countries_id'
       ) THEN
        ALTER TABLE regions
            ADD CONSTRAINT fk_regions_country_id_countries_id
            FOREIGN KEY (country_id) REFERENCES countries(id);
    END IF;
END $$;

-- ---------------------------------------------------------------------------
-- 2. states.region_id (only on existing UAT-style DBs)
-- ---------------------------------------------------------------------------
ALTER TABLE IF EXISTS states ADD COLUMN IF NOT EXISTS region_id BIGINT;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'states')
       AND NOT EXISTS (
           SELECT 1 FROM information_schema.table_constraints
           WHERE constraint_name = 'fk_states_region_id_regions_id'
       ) THEN
        ALTER TABLE states
            ADD CONSTRAINT fk_states_region_id_regions_id
            FOREIGN KEY (region_id) REFERENCES regions(id);
    END IF;
END $$;

-- ---------------------------------------------------------------------------
-- 3. auditees contact columns (only on existing DBs; ddl-auto on fresh DB)
-- ---------------------------------------------------------------------------
ALTER TABLE IF EXISTS auditees ADD COLUMN IF NOT EXISTS phone   VARCHAR;
ALTER TABLE IF EXISTS auditees ADD COLUMN IF NOT EXISTS email   VARCHAR;
ALTER TABLE IF EXISTS auditees ADD COLUMN IF NOT EXISTS website VARCHAR;

-- ---------------------------------------------------------------------------
-- 4. Indexes for BI grouping and FK lookup
-- ---------------------------------------------------------------------------
DO $$
BEGIN
    -- Region grouping (BI dashboards group by region)
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'states') THEN
        CREATE INDEX IF NOT EXISTS idx_states_region_id  ON states(region_id);
        CREATE INDEX IF NOT EXISTS idx_states_country_id ON states(country_id);
    END IF;

    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'cities') THEN
        CREATE INDEX IF NOT EXISTS idx_cities_state_id ON cities(state_id);
    END IF;

    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'regions') THEN
        CREATE INDEX IF NOT EXISTS idx_regions_country_id ON regions(country_id);
    END IF;

    -- Dealership lookups (auditee_type filter, location join)
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'auditees') THEN
        --CREATE INDEX IF NOT EXISTS idx_auditees_auditee_type_id ON auditees(auditee_type_id);
        CREATE INDEX IF NOT EXISTS idx_auditees_status          ON auditees(status);
    END IF;

    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'auditee_locations') THEN
        CREATE INDEX IF NOT EXISTS idx_auditee_locations_auditee_id      ON auditee_locations(auditee_id);
        CREATE INDEX IF NOT EXISTS idx_auditee_locations_city_id         ON auditee_locations(city_id);
        --CREATE INDEX IF NOT EXISTS idx_auditee_locations_auditee_type_id ON auditee_locations(auditee_type_id);
    END IF;
END $$;