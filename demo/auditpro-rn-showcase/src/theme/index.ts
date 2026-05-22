// Design tokens for AuditPro — colors, typography, spacing.
// Single source of truth; every component reads from here, never inlines hex codes.
export const colors = {
  primary: '#1D52E8',
  primaryDark: '#1239B8',
  primaryLight: '#EEF3FF',
  primaryGradEnd: '#3B72F8',
  primaryGradLight: '#5B8FFF',
  success: '#15A354',
  successLight: '#EDFAF4',
  successDeep: '#0a6e38',
  error: '#DC2525',
  errorLight: '#FEF0F0',
  screenBg: '#f1f1f6',
  cardBg: '#ffffff',
  cardBgSoft: '#F7F7FB',
  textPrimary: '#000000',
  textMuted: '#636370',
  textSubtle: '#888888',
  textGhost: '#c0c0cc',
  setupBg: '#08080F',
  setupCard: 'rgba(12,12,22,0.97)',
  setupBorder: 'rgba(255,255,255,0.08)',
  setupSurface: 'rgba(255,255,255,0.05)',
  setupInputBg: 'rgba(255,255,255,0.07)',
  setupInputBorder: 'rgba(255,255,255,0.12)',
  setupInputBorderFocus: '#1D52E8',
  setupTextDim: 'rgba(255,255,255,0.45)',
  border: '#e0e0e8',
  borderSoft: '#f0f0f5',
  borderDashed: '#c8c8d4',
  divider: '#e8e8f2',
  badgeBg: '#E0EAFF',
  hairline: '#ddd',
  passBg: '#DFF7EC',
  passBorder: 'rgba(21,163,84,0.25)',
};

export const fonts = {
  display: 'Syne_700Bold',
  displayHeavy: 'Syne_800ExtraBold',
  body: undefined as string | undefined, // System default
};

export const radii = {
  card: 13,
  cardSmall: 10,
  button: 13,
  buttonSmall: 11,
  pill: 20,
  phone: 38,
};

export const space = {
  xs: 4,
  sm: 6,
  md: 8,
  lg: 13,
  xl: 16,
  xxl: 24,
};

export const fontSize = {
  setupHeading: 28,
  screenTitle: 18,
  brand: 14,
  body: 13,
  sub: 12,
  label: 11,
  labelSmall: 10,
  eyebrow: 9,
};
