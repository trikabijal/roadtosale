import * as SQLite from 'expo-sqlite';

export const DB_NAME = 'road_to_sale.db';

export const CREATE_TABLES_SQL = `
  CREATE TABLE IF NOT EXISTS sessions (
    id TEXT PRIMARY KEY,
    smart_comply_assignment_id INTEGER NOT NULL,
    smart_comply_uc_id INTEGER,
    customer_json TEXT NOT NULL,
    vehicle_json TEXT NOT NULL,
    checksheet_id INTEGER NOT NULL,
    status TEXT NOT NULL DEFAULT 'active',
    started_at TEXT NOT NULL,
    ended_at TEXT,
    checklist_json TEXT NOT NULL DEFAULT '{}',
    trade_in_json TEXT,
    session_note TEXT,
    created_at TEXT NOT NULL DEFAULT (datetime('now')),
    updated_at TEXT NOT NULL DEFAULT (datetime('now'))
  );

  CREATE TABLE IF NOT EXISTS pending_writes (
    id TEXT PRIMARY KEY,
    session_id TEXT NOT NULL,
    retry_count INTEGER NOT NULL DEFAULT 0,
    last_attempted_at TEXT,
    status TEXT NOT NULL DEFAULT 'queued',
    payload TEXT NOT NULL,
    created_at TEXT NOT NULL DEFAULT (datetime('now'))
  );

  CREATE TABLE IF NOT EXISTS template_cache (
    template_id INTEGER PRIMARY KEY,
    etag TEXT NOT NULL,
    fetched_at TEXT NOT NULL,
    template_json TEXT NOT NULL
  );

  CREATE TABLE IF NOT EXISTS appointments_cache (
    cache_key TEXT PRIMARY KEY,
    fetched_at TEXT NOT NULL,
    appointments_json TEXT NOT NULL
  );
`;

let _db: SQLite.SQLiteDatabase | null = null;

export async function getDb(): Promise<SQLite.SQLiteDatabase> {
  if (!_db) {
    _db = await SQLite.openDatabaseAsync(DB_NAME);
    await _db.execAsync(CREATE_TABLES_SQL);
  }
  return _db;
}

// For tests — reset the singleton
export function _resetDbForTests(): void {
  _db = null;
}
