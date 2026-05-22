import { Platform } from 'react-native';
import {
  AuditEvent,
  AuditScore,
  AuditScoreItem,
  Deal,
  DealFilter,
  DealSetup,
} from '../../types';
import { AUDIT_ITEMS } from '../../data/auditItems';
import { BackendService } from './BackendService';
import { sqliteAdapter } from './sqliteAdapter';

const DB_NAME = 'auditpro.db';

function uuid(): string {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    const v = c === 'x' ? r : (r & 0x3) | 0x8;
    return v.toString(16);
  });
}

function emptyDealState(setup: DealSetup): Deal {
  const now = Date.now();
  return {
    id: uuid(),
    setup,
    createdAt: now,
    updatedAt: now,
    currentStep: 0,
    flags: {},
    features: {},
    useCases: {},
    photos: {},
    notes: { trade: '' },
    timestamps: {
      pencil: null,
      boSigned: null,
      mgrTo: null,
      fiHandoff: null,
    },
    pencilMarked: false,
    boCaptured: false,
    fiHandoff: false,
    routeCompleted: false,
    status: 'in_progress',
  };
}

// Minimal interface used by the SQLite branch. Mirrors expo-sqlite's async API.
type DbHandle = {
  runAsync(sql: string, params?: unknown[]): Promise<unknown>;
  getFirstAsync<T>(sql: string, params?: unknown[]): Promise<T | null>;
  getAllAsync<T>(sql: string, params?: unknown[]): Promise<T[]>;
};

export type LocalBackendOptions = {
  tenant?: string;
  inMemory?: boolean;
};

export class LocalBackendService implements BackendService {
  private db: DbHandle | null = null;
  private memoryDeals = new Map<string, Deal>();
  private memoryEvents: AuditEvent[] = [];
  private opts: LocalBackendOptions;
  private useMemoryFallback = false;

  constructor(opts: LocalBackendOptions = {}) {
    this.opts = opts;
  }

  async init(): Promise<void> {
    if (Platform.OS === 'web' || this.opts.inMemory) {
      this.useMemoryFallback = true;
      return;
    }
    try {
      const db = (await sqliteAdapter.open(DB_NAME)) as DbHandle | null;
      if (db) {
        this.db = db;
      } else {
        this.useMemoryFallback = true;
      }
    } catch (e) {
      // SQLite open failed (corrupt file, missing native module, etc.) — fall
      // back to in-memory so the app still launches. The rep loses persistence
      // until a reinstall, but that beats a crashed launch.
      console.warn('[AuditPro] SQLite open failed, falling back to in-memory:', e);
      this.useMemoryFallback = true;
      this.db = null;
    }
  }

  async createDeal(setup: DealSetup): Promise<Deal> {
    const deal = emptyDealState(setup);
    if (this.useMemoryFallback || !this.db) {
      this.memoryDeals.set(deal.id, deal);
      return deal;
    }
    await this.db.runAsync(
      `INSERT INTO deals (id, dealership, rep, customer, vehicle, status, current_step, created_at, updated_at, state_json)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
      [
        deal.id,
        setup.dealership,
        setup.rep,
        setup.customer,
        setup.vehicle,
        deal.status,
        deal.currentStep,
        deal.createdAt,
        deal.updatedAt,
        JSON.stringify(deal),
      ]
    );
    return deal;
  }

  async updateDeal(id: string, patch: Partial<Deal>): Promise<Deal> {
    const current = await this.getDeal(id);
    if (!current) throw new Error(`Deal ${id} not found`);
    const merged: Deal = {
      ...current,
      ...patch,
      flags: { ...current.flags, ...(patch.flags ?? {}) },
      features: { ...current.features, ...(patch.features ?? {}) },
      useCases: { ...current.useCases, ...(patch.useCases ?? {}) },
      photos: { ...current.photos, ...(patch.photos ?? {}) },
      notes: { ...current.notes, ...(patch.notes ?? {}) },
      timestamps: { ...current.timestamps, ...(patch.timestamps ?? {}) },
      updatedAt: Date.now(),
    };
    if (this.useMemoryFallback || !this.db) {
      this.memoryDeals.set(id, merged);
      return merged;
    }
    await this.db.runAsync(
      `UPDATE deals SET status = ?, current_step = ?, updated_at = ?, state_json = ? WHERE id = ?`,
      [merged.status, merged.currentStep, merged.updatedAt, JSON.stringify(merged), id]
    );
    return merged;
  }

  async getDeal(id: string): Promise<Deal | null> {
    if (this.useMemoryFallback || !this.db) {
      return this.memoryDeals.get(id) ?? null;
    }
    const row = await this.db.getFirstAsync<{ state_json: string }>(
      `SELECT state_json FROM deals WHERE id = ?`,
      [id]
    );
    if (!row) return null;
    return JSON.parse(row.state_json) as Deal;
  }

  async listDeals(filter?: DealFilter): Promise<Deal[]> {
    if (this.useMemoryFallback || !this.db) {
      let deals = Array.from(this.memoryDeals.values());
      if (filter?.status) deals = deals.filter((d) => d.status === filter.status);
      return deals.sort((a, b) => b.createdAt - a.createdAt);
    }
    const rows = filter?.status
      ? await this.db.getAllAsync<{ state_json: string }>(
          `SELECT state_json FROM deals WHERE status = ? ORDER BY created_at DESC`,
          [filter.status]
        )
      : await this.db.getAllAsync<{ state_json: string }>(
          `SELECT state_json FROM deals ORDER BY created_at DESC`
        );
    return rows.map((r) => JSON.parse(r.state_json) as Deal);
  }

  async logEvent(event: AuditEvent): Promise<void> {
    const id = event.id ?? uuid();
    if (this.useMemoryFallback || !this.db) {
      this.memoryEvents.push({ ...event, id });
      return;
    }
    await this.db.runAsync(
      `INSERT INTO events (id, deal_id, type, payload_json, timestamp) VALUES (?, ?, ?, ?, ?)`,
      [
        id,
        event.dealId,
        event.type,
        event.payload ? JSON.stringify(event.payload) : null,
        event.timestamp,
      ]
    );
  }

  async getAuditTrail(dealId: string): Promise<AuditEvent[]> {
    if (this.useMemoryFallback || !this.db) {
      return this.memoryEvents.filter((e) => e.dealId === dealId);
    }
    const rows = await this.db.getAllAsync<{
      id: string;
      deal_id: string;
      type: string;
      payload_json: string | null;
      timestamp: number;
    }>(
      `SELECT id, deal_id, type, payload_json, timestamp FROM events WHERE deal_id = ? ORDER BY timestamp ASC`,
      [dealId]
    );
    return rows.map((r) => ({
      id: r.id,
      dealId: r.deal_id,
      type: r.type,
      payload: r.payload_json ? JSON.parse(r.payload_json) : undefined,
      timestamp: r.timestamp,
    }));
  }

  async computeScore(dealId: string): Promise<AuditScore> {
    const deal = await this.getDeal(dealId);
    if (!deal) throw new Error(`Deal ${dealId} not found`);
    const items: AuditScoreItem[] = AUDIT_ITEMS.map((def) => {
      const passed = def.passed(deal);
      const earned = def.earned ? def.earned(deal) : passed ? 1 : 0;
      return {
        id: def.id,
        label: def.label,
        passed,
        earned,
        source: def.source,
        detail: def.detail?.(deal),
      };
    });
    // Sum weighted earned fractions; each item's max contribution is 1.0.
    const earnedTotal = items.reduce((acc, i) => acc + i.earned, 0);
    const passed = items.filter((i) => i.passed).length;
    const total = items.length;
    const score = Math.round((earnedTotal / total) * 100);
    return { score, passed, total, items };
  }
}
