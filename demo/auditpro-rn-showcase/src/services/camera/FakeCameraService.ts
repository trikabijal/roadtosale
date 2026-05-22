import { CameraService, CapturedPhoto } from './CameraService';

const PLACEHOLDER_GRADIENT =
  'data:image/svg+xml;utf8,<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 200 200"><defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1"><stop offset="0%" stop-color="%23EDFAF4"/><stop offset="100%" stop-color="%23D6F5E8"/></linearGradient></defs><rect width="200" height="200" fill="url(%23g)"/></svg>';

export class FakeCameraService implements CameraService {
  async capturePhoto(_slotId: string): Promise<CapturedPhoto> {
    // Simulate a brief shutter delay so screens that wait can render flash animations.
    await new Promise((r) => setTimeout(r, 250));
    return {
      uri: PLACEHOLDER_GRADIENT,
      metadata: { capturedAt: Date.now(), source: 'fake' },
    };
  }

  async isPermissionGranted(): Promise<boolean> {
    return true;
  }

  async requestPermission(): Promise<boolean> {
    return true;
  }
}
