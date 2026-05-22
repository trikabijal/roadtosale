import {
  AuditEvent,
  AuditScore,
  Deal,
  DealFilter,
  DealSetup,
} from '../../types';

export interface BackendService {
  init(): Promise<void>;
  createDeal(setup: DealSetup): Promise<Deal>;
  updateDeal(id: string, patch: Partial<Deal>): Promise<Deal>;
  getDeal(id: string): Promise<Deal | null>;
  listDeals(filter?: DealFilter): Promise<Deal[]>;
  logEvent(event: AuditEvent): Promise<void>;
  getAuditTrail(dealId: string): Promise<AuditEvent[]>;
  computeScore(dealId: string): Promise<AuditScore>;
}
