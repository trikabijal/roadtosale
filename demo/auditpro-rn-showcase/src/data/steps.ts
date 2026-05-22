// Screen list and progress percentages — keyed by screen index in nav stack.
// Defines the canonical audit step ordering used by the top-of-screen
// progress bar and any future "jump to step" navigation.
export type StepDef = {
  index: number;
  routeName: ScreenName;
  label: string;
  percent: number;
  audioScreenId?: string;
};

export type ScreenName =
  | 'Setup'
  | 'Dashboard'
  | 'Greet'
  | 'Discovery'
  | 'FeatureMatch'
  | 'FrontLineReady'
  | 'Walkaround'
  | 'TestDrive'
  | 'TradeIn'
  | 'Pencil'
  | 'BuyersOrder'
  | 'FIHandoff'
  | 'AuditComplete';

export const STEPS: StepDef[] = [
  { index: 0, routeName: 'Dashboard', label: 'Dashboard', percent: 0 },
  { index: 1, routeName: 'Greet', label: '1.1–1.2 · Greet', percent: 8, audioScreenId: 'greet' },
  { index: 2, routeName: 'Discovery', label: '2.1 · Discovery', percent: 16, audioScreenId: 'discovery' },
  { index: 3, routeName: 'FeatureMatch', label: '3.1 · Feature Match', percent: 24, audioScreenId: 'feature_match' },
  { index: 4, routeName: 'FrontLineReady', label: '3.2 · Front-Line Ready', percent: 33, audioScreenId: 'front_line_ready' },
  { index: 5, routeName: 'Walkaround', label: '4.1 · Walkaround', percent: 41, audioScreenId: 'walkaround' },
  { index: 6, routeName: 'TestDrive', label: '5.1 · Test Drive', percent: 50, audioScreenId: 'test_drive' },
  { index: 7, routeName: 'TradeIn', label: '6.1 · Trade-In', percent: 58, audioScreenId: 'trade_in' },
  { index: 8, routeName: 'Pencil', label: '7.1 · First Pencil', percent: 66 },
  { index: 9, routeName: 'BuyersOrder', label: "7.2 · Buyer's Order", percent: 75 },
  { index: 10, routeName: 'FIHandoff', label: '9.1 · F&I Handoff', percent: 83, audioScreenId: 'fi_handoff' },
  { index: 11, routeName: 'AuditComplete', label: 'Audit Complete', percent: 100 },
];

export const STEP_BY_ROUTE: Record<ScreenName, StepDef | undefined> = STEPS.reduce(
  (acc, s) => {
    acc[s.routeName] = s;
    return acc;
  },
  {} as Record<ScreenName, StepDef | undefined>
);
