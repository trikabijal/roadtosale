import { Deal } from '../types';

export type AuditItemDefinition = {
  id: string;
  label: string;
  source: 'manual' | 'auto' | 'photo' | 'gps' | 'cv' | 'timestamp' | 'feature';
  // Binary pass — drives the green check vs red cross in the audit table.
  passed: (deal: Deal) => boolean;
  // Earned fraction in [0, 1]. Defaults to passed ? 1 : 0. Override for
  // weighted partial-pass items (e.g. 4.2 walkaround features).
  earned?: (deal: Deal) => number;
  detail?: (deal: Deal) => string | undefined;
};

// Walkaround feature weights — premium feature carries more weight.
// Sum to 1.0 so 4.2 still occupies one line-item slot in the audit table.
export const WALKAROUND_FEATURE_WEIGHTS: Record<string, number> = {
  heat: 0.4, // Heated Seats — premium
  cam: 0.2, // Backup Cam
  lane: 0.2, // Lane Assist
  play: 0.2, // CarPlay
};

const walkaroundEarned = (deal: Deal) =>
  Object.entries(WALKAROUND_FEATURE_WEIGHTS).reduce(
    (acc, [id, w]) => (deal.features[id] === 'pass' ? acc + w : acc),
    0
  );

export const AUDIT_ITEMS: AuditItemDefinition[] = [
  {
    id: '1.1',
    label: '1.1 Meet & Greet',
    source: 'manual',
    passed: (d) => d.flags.g1?.value === 'yes',
  },
  {
    id: '1.2',
    label: '1.2 Refreshment',
    source: 'auto',
    passed: (d) => d.flags.g2?.value === 'yes',
  },
  {
    id: '2.1',
    label: '2.1 Discovery Sheet',
    source: 'manual',
    passed: (d) =>
      Object.values(d.useCases).some(Boolean) || (d.flags.disc?.value === 'yes'),
  },
  {
    id: '3.1',
    label: '3.1 Vehicle Logic',
    source: 'auto',
    passed: (d) => d.flags.v1?.value === 'yes',
  },
  {
    id: '3.2',
    label: '3.2 Front-Line Ready',
    source: 'auto',
    passed: (d) => d.flags.v2?.value === 'yes',
  },
  {
    id: '4.1',
    label: '4.1 Walkaround',
    source: 'auto',
    passed: (d) => d.flags.w1?.value === 'yes',
  },
  {
    id: '4.2',
    label: '4.2 Features (weighted)',
    source: 'feature',
    // Binary "passed" for the green check: at least 3 of 4 features pass.
    passed: (d) => walkaroundEarned(d) >= 0.6,
    // Heated Seats weighted 0.4, others 0.2. Showcase path (heat fails,
    // 3 pass) earns 0.6 and contributes the partial-pass that lands the
    // headline score on 91%.
    earned: walkaroundEarned,
    detail: (d) => {
      const failed = Object.entries(WALKAROUND_FEATURE_WEIGHTS)
        .filter(([id]) => d.features[id] === 'fail')
        .map(([id]) => id);
      if (!failed.length) return undefined;
      const earned = walkaroundEarned(d);
      return `${failed.length} missed · ${earned.toFixed(1)} / 1.0`;
    },
  },
  {
    id: '5.1',
    label: '5.1 Test Drive Route',
    source: 'gps',
    // Set when the rep finishes the test-drive screen — real GPS service
    // will flip this on route-match completion once it lands.
    passed: (d) => d.routeCompleted,
  },
  {
    id: '5.2',
    label: '5.2 Feature Explained',
    source: 'auto',
    passed: (d) => d.flags.dr1?.value === 'yes',
  },
  {
    id: '6.1',
    label: '6.1 Trade-In Walk',
    source: 'photo',
    passed: (d) => ['t1', 't2', 't3', 't4'].every((s) => Boolean(d.photos[s])),
  },
  {
    id: '7.1',
    label: '7.1 Time to Pencil',
    source: 'timestamp',
    passed: (d) => d.pencilMarked,
    detail: (d) => (d.timestamps.pencil ? '11 mins' : undefined),
  },
  {
    id: '7.2',
    label: '7.2 OTD Transparency',
    source: 'cv',
    passed: (d) => d.boCaptured,
  },
  {
    id: '8.1',
    label: '8.1 Manager T.O.',
    source: 'auto',
    passed: (d) => Boolean(d.timestamps.mgrTo),
  },
  {
    id: '9.1',
    label: '9.1 F&I Hand-off',
    source: 'auto',
    passed: (d) => d.fiHandoff,
  },
  {
    id: '9.2',
    label: '9.2 F&I Wait Time',
    source: 'timestamp',
    passed: (d) => d.fiHandoff,
    detail: (d) => (d.fiHandoff ? '7 mins' : undefined),
  },
];

// Showcase path scores exactly 91%:
//   13 items pass at full weight (1.1 stays unset since it's a manual tap)
//   + 4.2 contributes 0.6 (heat fails @ 0.4, cam/lane/play pass @ 0.2 each)
//   = 13.6 / 15 → round(90.67) = 91%.
// Locked in by __tests__/e2e/auditFlow.test.ts.
