// Contract tests — every CameraService implementation must satisfy these.
// RealCameraService is excluded from this Node-side suite because it depends
// on a mounted <CameraView /> ref that only exists at runtime; its surface is
// covered by a focused unit test below.

import { CameraService } from './CameraService';
import { FakeCameraService } from './FakeCameraService';
import { RealCameraService } from './RealCameraService';

function cameraServiceContract(name: string, factory: () => CameraService) {
  describe(`CameraService contract: ${name}`, () => {
    it('returns a uri and metadata', async () => {
      const svc = factory();
      const result = await svc.capturePhoto('test-slot');
      expect(result.uri).toBeTruthy();
      expect(result.metadata.capturedAt).toBeGreaterThan(0);
      expect(result.metadata.source).toMatch(/^(real|fake)$/);
    });

    it('reports permission state without throwing', async () => {
      const svc = factory();
      const granted = await svc.isPermissionGranted();
      expect(typeof granted).toBe('boolean');
    });
  });
}

cameraServiceContract('Fake', () => new FakeCameraService());

describe('RealCameraService — capture broker', () => {
  it('throws a useful error when no CameraView is mounted', async () => {
    const svc = new RealCameraService();
    await expect(svc.capturePhoto('slot')).rejects.toThrow(
      /No active CameraView/i
    );
  });

  it('delegates to the registered capture function', async () => {
    const svc = new RealCameraService();
    svc.acquireCapture(async () => ({ uri: 'file://x.jpg', width: 100, height: 200 }));
    const photo = await svc.capturePhoto('slot');
    expect(photo.uri).toBe('file://x.jpg');
    expect(photo.metadata.source).toBe('real');
    expect(photo.metadata.width).toBe(100);
    expect(photo.metadata.height).toBe(200);
  });

  it('clears the active capture when its owner releases', async () => {
    const svc = new RealCameraService();
    const token = svc.acquireCapture(async () => ({ uri: 'file://x.jpg' }));
    svc.releaseCapture(token);
    await expect(svc.capturePhoto('slot')).rejects.toThrow(/No active CameraView/i);
  });

  it('a stale release does not clobber the newer registration', async () => {
    // Mirrors the rapid-nav case: an unmounting surface tries to release
    // after a newer surface has already taken over.
    const svc = new RealCameraService();
    const oldToken = svc.acquireCapture(async () => ({ uri: 'file://old.jpg' }));
    svc.acquireCapture(async () => ({ uri: 'file://new.jpg' }));
    svc.releaseCapture(oldToken); // should be a no-op
    const photo = await svc.capturePhoto('slot');
    expect(photo.uri).toBe('file://new.jpg');
  });
});
