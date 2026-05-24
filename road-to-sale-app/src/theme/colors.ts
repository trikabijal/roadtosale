// Road to Sale colour tokens — dark mode primary, light mode secondary.
// Showroom environments favour dark mode; always test in both.

export const DarkColors = {
  background: '#0D0D0D',
  surface: '#1A1A1A',
  surfaceElevated: '#242424',
  border: '#2E2E2E',

  // Mic states
  micActive: '#22C55E',      // green — mic is on
  micMuted: '#EF4444',       // red — mic is muted

  // Checklist states
  stepComplete: '#22C55E',   // green
  stepPartial: '#F59E0B',    // amber
  stepPending: '#6B7280',    // grey
  stepOverride: '#3B82F6',   // blue — manually confirmed

  // Text
  textPrimary: '#F9FAFB',
  textSecondary: '#9CA3AF',
  textMuted: '#6B7280',

  // Brand
  accent: '#3B82F6',         // AuditPro blue
  accentPressed: '#2563EB',

  // Status
  success: '#22C55E',
  warning: '#F59E0B',
  error: '#EF4444',

  // Trade-in
  photoNeeded: '#374151',
  photoCaptured: '#065F46',
} as const;

export const LightColors = {
  background: '#F9FAFB',
  surface: '#FFFFFF',
  surfaceElevated: '#F3F4F6',
  border: '#E5E7EB',

  micActive: '#16A34A',
  micMuted: '#DC2626',

  stepComplete: '#16A34A',
  stepPartial: '#D97706',
  stepPending: '#9CA3AF',
  stepOverride: '#2563EB',

  textPrimary: '#111827',
  textSecondary: '#6B7280',
  textMuted: '#9CA3AF',

  accent: '#2563EB',
  accentPressed: '#1D4ED8',

  success: '#16A34A',
  warning: '#D97706',
  error: '#DC2626',

  photoNeeded: '#E5E7EB',
  photoCaptured: '#D1FAE5',
} as const;

// ColorScheme maps the same keys as DarkColors but with string values so
// either DarkColors or LightColors can be assigned to it at runtime.
export type ColorScheme = { readonly [K in keyof typeof DarkColors]: string };
