import { useCallback, useState } from 'react';
import { useServices } from '../services/context';
import { useDealStore } from '../store/deal';

// Single source of truth for the photo capture flow used by every audit
// photo screen (FrontLineReady, TradeIn, BuyersOrder). Owns the per-slot
// flash-key state used to retrigger the shutter animation, calls the active
// camera service, and writes the resulting URI through to the deal store.
export type PhotoCapture = {
  flashKeys: Record<string, number>;
  capture: (slot: string) => Promise<void>;
};

export function usePhotoCapture(): PhotoCapture {
  const services = useServices();
  const setPhoto = useDealStore((s) => s.setPhoto);
  const [flashKeys, setFlashKeys] = useState<Record<string, number>>({});

  const capture = useCallback(
    async (slot: string) => {
      setFlashKeys((prev) => ({ ...prev, [slot]: Date.now() }));
      const photo = await services.camera.capturePhoto(slot);
      await setPhoto(slot, photo.uri);
    },
    [services.camera, setPhoto]
  );

  return { flashKeys, capture };
}
