export type RouteSnapshot = {
  distanceKm: number;
  matchPercent: number;
  durationSeconds: number;
};

export interface GPSService {
  startTracking(routeId: string): Promise<void>;
  stopTracking(): Promise<RouteSnapshot>;
  getCurrent(): RouteSnapshot;
}
