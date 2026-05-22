import { PhotoMetadata } from '../../types';

export type CapturedPhoto = {
  uri: string;
  metadata: PhotoMetadata;
};

export interface CameraService {
  capturePhoto(slotId: string): Promise<CapturedPhoto>;
  isPermissionGranted(): Promise<boolean>;
  requestPermission(): Promise<boolean>;
}
