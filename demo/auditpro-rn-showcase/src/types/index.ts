export type DealSetup = {
  dealership: string;
  rep: string;
  customer: string;
  vehicle: string;
};

export type FlagValue = 'yes' | 'no' | null;
export type FlagSource = 'manual' | 'auto';

export type Flag = {
  value: FlagValue;
  source: FlagSource;
  setAt: number | null;
};

export type FeatureState = 'pass' | 'fail' | null;

export type Deal = {
  id: string;
  setup: DealSetup;
  createdAt: number;
  updatedAt: number;
  currentStep: number;
  flags: Record<string, Flag>;
  features: Record<string, FeatureState>;
  useCases: Record<string, boolean>;
  photos: Record<string, string | null>;
  notes: { trade: string };
  timestamps: {
    pencil: number | null;
    boSigned: number | null;
    mgrTo: number | null;
    fiHandoff: number | null;
  };
  pencilMarked: boolean;
  boCaptured: boolean;
  fiHandoff: boolean;
  // True once the rep has advanced past the test-drive screen with the route
  // recorded (5.1 audit pass). Real GPS service will flip this on route-match
  // completion; today the screen flips it on Next.
  routeCompleted: boolean;
  status: 'in_progress' | 'completed';
};

export type AuditEvent = {
  id?: string;
  dealId: string;
  type: string;
  payload?: Record<string, unknown>;
  timestamp: number;
};

export type AuditScore = {
  score: number;
  passed: number;
  total: number;
  items: AuditScoreItem[];
};

export type AuditScoreItem = {
  id: string;
  label: string;
  passed: boolean;
  // Earned fraction in [0, 1]. Binary items contribute 0 or 1; weighted items
  // (e.g. 4.2 walkaround features) contribute a partial value. Score is the
  // sum of earned values divided by item count.
  earned: number;
  source: 'manual' | 'auto' | 'photo' | 'gps' | 'cv' | 'timestamp' | 'feature' | 'unknown';
  detail?: string;
};

export type DealFilter = {
  status?: 'in_progress' | 'completed';
};

export type PhotoMetadata = {
  capturedAt: number;
  width?: number;
  height?: number;
  source: 'real' | 'fake';
};
