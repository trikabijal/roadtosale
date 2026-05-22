// Default (web) implementation — no SQLite. The .native.ts variant supplies the real one.
export type SQLiteAdapter = {
  open(name: string): Promise<unknown | null>;
};

export const sqliteAdapter: SQLiteAdapter = {
  async open(_name: string) {
    return null;
  },
};
