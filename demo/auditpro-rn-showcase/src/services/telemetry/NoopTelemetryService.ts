import { TelemetryService } from './TelemetryService';

// Drops every event. Stand-in until a real telemetry provider (PostHog,
// Mixpanel, Segment) is wired up — at which point swap this slot in
// composition.ts. Consumers must not depend on telemetry side-effects.
export class NoopTelemetryService implements TelemetryService {
  track(_event: string, _properties?: Record<string, unknown>): void {
    // intentionally drops events
  }
  identify(_userId: string, _traits?: Record<string, unknown>): void {
    // intentionally a no-op
  }
}
