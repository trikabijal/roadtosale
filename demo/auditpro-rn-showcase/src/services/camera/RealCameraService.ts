import { Camera, CameraView } from 'expo-camera';
import { CameraService, CapturedPhoto } from './CameraService';

// Brokers CameraView capture for any screen that mounts <RealCameraSurface />.
// Each registration is identified by an opaque token so that during a
// fast navigation transition (old screen mid-unmount, new screen already
// mounted) the old screen's cleanup can't clobber the new screen's
// registration. Callers register via acquireCapture() and pass the returned
// token to releaseCapture() on unmount.

type CaptureFn = () => Promise<{ uri: string; width?: number; height?: number }>;
export type CaptureToken = symbol;

export class RealCameraService implements CameraService {
  private capture: CaptureFn | null = null;
  private activeToken: CaptureToken | null = null;

  acquireCapture(capture: CaptureFn): CaptureToken {
    const token: CaptureToken = Symbol('camera-capture');
    this.capture = capture;
    this.activeToken = token;
    return token;
  }

  releaseCapture(token: CaptureToken): void {
    if (this.activeToken !== token) return; // a newer surface owns the slot
    this.capture = null;
    this.activeToken = null;
  }

  async capturePhoto(_slotId: string): Promise<CapturedPhoto> {
    if (!this.capture) {
      throw new Error(
        'No active CameraView is mounted. Mount <RealCameraSurface /> on the active screen before calling capturePhoto.'
      );
    }
    const photo = await this.capture();
    return {
      uri: photo.uri,
      metadata: {
        capturedAt: Date.now(),
        width: photo.width,
        height: photo.height,
        source: 'real',
      },
    };
  }

  async isPermissionGranted(): Promise<boolean> {
    const { status } = await Camera.getCameraPermissionsAsync();
    return status === 'granted';
  }

  async requestPermission(): Promise<boolean> {
    const { status } = await Camera.requestCameraPermissionsAsync();
    return status === 'granted';
  }
}

export { CameraView };
