import React, { useEffect, useRef } from 'react';
import { StyleSheet, View } from 'react-native';
import { CameraView, useCameraPermissions } from 'expo-camera';
import { useServices } from '../services/context';
import { RealCameraService } from '../services/camera/RealCameraService';

// Mounts a real <CameraView /> and registers its capture callback with the
// active RealCameraService. Renders nothing when the active camera is the
// fake/scripted service — keeps showcase mode free of permission prompts
// and live camera sessions.
//
// Drop this on any screen that calls services.camera.capturePhoto(). The
// capture surface is positioned off-screen because the audit screens render
// their own UI; the camera here just exists so takePictureAsync() has a
// real device handle to invoke.
export const RealCameraSurface: React.FC = () => {
  const { camera } = useServices();
  const isReal = camera instanceof RealCameraService;
  const ref = useRef<CameraView | null>(null);
  const [permission, requestPermission] = useCameraPermissions();

  useEffect(() => {
    if (!isReal) return;
    if (permission && !permission.granted && permission.canAskAgain) {
      requestPermission();
    }
  }, [isReal, permission, requestPermission]);

  useEffect(() => {
    if (!isReal) return;
    const real = camera as RealCameraService;
    const token = real.acquireCapture(async () => {
      const view = ref.current;
      if (!view) {
        throw new Error('CameraView ref not attached yet');
      }
      const photo = await view.takePictureAsync({ skipProcessing: true });
      if (!photo) throw new Error('CameraView returned no photo');
      return { uri: photo.uri, width: photo.width, height: photo.height };
    });
    return () => real.releaseCapture(token);
  }, [camera, isReal]);

  if (!isReal || !permission?.granted) return null;

  return (
    <View style={styles.host} pointerEvents="none" accessibilityElementsHidden>
      <CameraView ref={ref} style={styles.cam} facing="back" />
    </View>
  );
};

const styles = StyleSheet.create({
  // 1×1 pixel placement keeps the camera session active without occupying UI.
  // takePictureAsync() still returns the full-resolution image.
  host: {
    position: 'absolute',
    width: 1,
    height: 1,
    opacity: 0,
    top: 0,
    left: 0,
  },
  cam: { width: 1, height: 1 },
});
