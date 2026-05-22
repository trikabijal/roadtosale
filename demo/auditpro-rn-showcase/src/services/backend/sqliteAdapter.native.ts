import * as SQLite from 'expo-sqlite';
import { SCHEMA_SQL } from './schema';

export type SQLiteAdapter = {
  open(name: string): Promise<SQLite.SQLiteDatabase | null>;
};

export const sqliteAdapter: SQLiteAdapter = {
  async open(name: string) {
    const db = await SQLite.openDatabaseAsync(name);
    await db.execAsync(SCHEMA_SQL);
    return db;
  },
};
