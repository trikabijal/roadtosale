// SQLite schema for the local backend.
// Foreign keys + indexes on the columns we filter by.
export const SCHEMA_SQL = `
PRAGMA foreign_keys = ON;
PRAGMA journal_mode = WAL;

CREATE TABLE IF NOT EXISTS deals (
  id TEXT PRIMARY KEY,
  dealership TEXT NOT NULL,
  rep TEXT NOT NULL,
  customer TEXT NOT NULL,
  vehicle TEXT NOT NULL,
  status TEXT NOT NULL,
  current_step INTEGER NOT NULL DEFAULT 0,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  state_json TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_deals_dealership ON deals(dealership);
CREATE INDEX IF NOT EXISTS idx_deals_created_at ON deals(created_at);
CREATE INDEX IF NOT EXISTS idx_deals_status ON deals(status);

CREATE TABLE IF NOT EXISTS events (
  id TEXT PRIMARY KEY,
  deal_id TEXT NOT NULL,
  type TEXT NOT NULL,
  payload_json TEXT,
  timestamp INTEGER NOT NULL,
  FOREIGN KEY (deal_id) REFERENCES deals(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_events_deal_id ON events(deal_id);
CREATE INDEX IF NOT EXISTS idx_events_timestamp ON events(timestamp);

CREATE TABLE IF NOT EXISTS photos (
  id TEXT PRIMARY KEY,
  deal_id TEXT NOT NULL,
  slot_id TEXT NOT NULL,
  uri TEXT NOT NULL,
  source TEXT NOT NULL,
  captured_at INTEGER NOT NULL,
  FOREIGN KEY (deal_id) REFERENCES deals(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_photos_deal_id ON photos(deal_id);

CREATE TABLE IF NOT EXISTS audits (
  id TEXT PRIMARY KEY,
  deal_id TEXT NOT NULL,
  score INTEGER NOT NULL,
  passed INTEGER NOT NULL,
  total INTEGER NOT NULL,
  computed_at INTEGER NOT NULL,
  items_json TEXT NOT NULL,
  FOREIGN KEY (deal_id) REFERENCES deals(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_audits_deal_id ON audits(deal_id);
`;
