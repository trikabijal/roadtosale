import { GPSService, RouteSnapshot } from './GPSService';

export class ScriptedGPSService implements GPSService {
  private snapshot: RouteSnapshot = { distanceKm: 0, matchPercent: 0, durationSeconds: 0 };
  private startedAt: number | null = null;
  private timer: ReturnType<typeof setInterval> | null = null;

  async startTracking(_routeId: string): Promise<void> {
    this.startedAt = Date.now();
    this.snapshot = { distanceKm: 0, matchPercent: 0, durationSeconds: 0 };
    this.timer = setInterval(() => {
      const elapsed = (Date.now() - (this.startedAt ?? Date.now())) / 1000;
      this.snapshot = {
        distanceKm: Math.min(8.4, elapsed * 0.7),
        matchPercent: Math.min(94, Math.round(elapsed * 8)),
        durationSeconds: elapsed,
      };
    }, 250);
  }

  async stopTracking(): Promise<RouteSnapshot> {
    if (this.timer) {
      clearInterval(this.timer);
      this.timer = null;
    }
    this.snapshot = { distanceKm: 8.4, matchPercent: 94, durationSeconds: 720 };
    return this.snapshot;
  }

  getCurrent(): RouteSnapshot {
    return this.snapshot;
  }
}
