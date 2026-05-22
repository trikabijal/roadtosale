// Contract tests — every BackendService implementation must satisfy these.
// LocalBackendService is run with `inMemory: true` so the suite does not
// touch native SQLite; the same suite will exercise the cloud backend once
// it lands.

import { BackendService } from './BackendService';
import { LocalBackendService } from './LocalBackendService';

function backendServiceContract(name: string, factory: () => BackendService) {
  describe(`BackendService contract: ${name}`, () => {
    it('creates, retrieves, and lists deals', async () => {
      const svc = factory();
      await svc.init();
      const deal = await svc.createDeal({
        dealership: 'Riverside Honda',
        rep: 'Marcus Webb',
        customer: 'Sarah Mitchell',
        vehicle: '2025 Honda CR-V EX-L',
      });
      expect(deal.id).toBeTruthy();
      expect(deal.status).toBe('in_progress');

      const fetched = await svc.getDeal(deal.id);
      expect(fetched?.id).toBe(deal.id);

      const all = await svc.listDeals();
      expect(all.find((d) => d.id === deal.id)).toBeDefined();
    });

    it('updates deal flags and merges nested patches', async () => {
      const svc = factory();
      await svc.init();
      const deal = await svc.createDeal({
        dealership: 'Riverside Honda',
        rep: 'Marcus Webb',
        customer: 'Sarah Mitchell',
        vehicle: '2025 Honda CR-V EX-L',
      });
      const t = Date.now();
      const after = await svc.updateDeal(deal.id, {
        flags: { g1: { value: 'yes', source: 'manual', setAt: t } },
        features: { heat: 'fail', cam: 'pass' },
      });
      expect(after.flags.g1?.value).toBe('yes');
      expect(after.features.heat).toBe('fail');
      expect(after.features.cam).toBe('pass');
    });

    it('logs and retrieves audit events', async () => {
      const svc = factory();
      await svc.init();
      const deal = await svc.createDeal({
        dealership: 'D',
        rep: 'R',
        customer: 'C',
        vehicle: 'V',
      });
      await svc.logEvent({
        dealId: deal.id,
        type: 'flag_set',
        payload: { id: 'g1', value: 'yes' },
        timestamp: Date.now(),
      });
      const events = await svc.getAuditTrail(deal.id);
      expect(events.length).toBe(1);
      expect(events[0].type).toBe('flag_set');
    });

    it('computeScore returns the weighted 91% on the showcase path', async () => {
      const svc = factory();
      await svc.init();
      const deal = await svc.createDeal({
        dealership: 'Riverside Honda',
        rep: 'Marcus Webb',
        customer: 'Sarah Mitchell',
        vehicle: '2025 Honda CR-V EX-L',
      });

      const t = Date.now();
      const flag = (value: 'yes' | 'no') => ({ value, source: 'auto' as const, setAt: t });
      // 13 binary line items pass; 1.1 (manual greet) intentionally left
      // unset to mirror the showcase flow where the rep skips the manual tap.
      await svc.updateDeal(deal.id, {
        flags: {
          g2: flag('yes'),
          v1: flag('yes'),
          v2: flag('yes'),
          w1: flag('yes'),
          dr1: flag('yes'),
          disc: flag('yes'),
        },
        useCases: { fam: true, hw: true },
        features: { cam: 'pass', lane: 'pass', play: 'pass', heat: 'fail' },
        photos: {
          t1: 'file://t1.jpg',
          t2: 'file://t2.jpg',
          t3: 'file://t3.jpg',
          t4: 'file://t4.jpg',
        },
        timestamps: { pencil: t, mgrTo: t, boSigned: t, fiHandoff: t },
        pencilMarked: true,
        boCaptured: true,
        fiHandoff: true,
        routeCompleted: true,
        currentStep: 12,
      });

      const score = await svc.computeScore(deal.id);
      expect(score.total).toBe(15);
      expect(score.score).toBe(91);
      const four_two = score.items.find((i) => i.id === '4.2');
      expect(four_two?.earned).toBeCloseTo(0.6, 5);
      expect(four_two?.passed).toBe(true);
    });
  });
}

backendServiceContract('Local (in-memory)', () => new LocalBackendService({ inMemory: true }));
