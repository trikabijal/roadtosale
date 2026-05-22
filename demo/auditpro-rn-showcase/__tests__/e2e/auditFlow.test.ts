// E2E audit-flow test — drives the scripted services as the screens would
// and asserts the full audit lands at the showcase target score (91%).
//
// Every interaction goes through the service interfaces, not through
// implementation internals — same seam used in production. If the scripted
// path drifts (e.g. someone rewires the audio script or removes a detection
// event), this test fails before the showcase fails in front of a prospect.

import { ScriptedAudioService } from '../../src/services/audio/ScriptedAudioService';
import { FakeCameraService } from '../../src/services/camera/FakeCameraService';
import { ScriptedCVService } from '../../src/services/cv/ScriptedCVService';
import { LocalBackendService } from '../../src/services/backend/LocalBackendService';
import { DetectionEvent } from '../../src/services/audio/AudioService';
import { Deal } from '../../src/types';

jest.useFakeTimers();

// Each script's longest line is ~4500ms; advance generously to cover them all.
const SCRIPT_DURATION_MS = 6000;
// FakeCameraService awaits a 250ms shutter delay; flush a bit more.
const SHUTTER_DURATION_MS = 400;

type StoreLike = {
  deal: Deal;
  setFlag(id: string, value: 'yes' | 'no', source: 'manual' | 'auto'): Promise<void>;
  setFeature(id: string, value: 'pass' | 'fail'): Promise<void>;
  setUseCase(id: string, value: boolean): Promise<void>;
  setPhoto(slot: string, uri: string): Promise<void>;
  setTimestamp(key: 'pencil' | 'mgrTo' | 'boSigned' | 'fiHandoff', value: number): Promise<void>;
  setBoolean(
    key: 'pencilMarked' | 'boCaptured' | 'fiHandoff' | 'routeCompleted',
    value: boolean
  ): Promise<void>;
};

async function runScreen(
  audio: ScriptedAudioService,
  store: StoreLike,
  screenId: string,
  stepId: string,
  onDetection: (e: DetectionEvent, store: StoreLike) => void
) {
  const session = await audio.start({ screenId, stepId });
  session.onDetection((e) => onDetection(e, store));
  // advanceTimersByTimeAsync flushes both timers and microtasks so the
  // detection-handler awaits (backend.updateDeal) resolve in order.
  await jest.advanceTimersByTimeAsync(SCRIPT_DURATION_MS);
  await session.stop();
}

async function snap(
  camera: { capturePhoto(slot: string): Promise<{ uri: string }> },
  store: StoreLike,
  slot: string
) {
  const promise = camera.capturePhoto(slot);
  await jest.advanceTimersByTimeAsync(SHUTTER_DURATION_MS);
  const photo = await promise;
  await store.setPhoto(slot, photo.uri);
}

describe('audit flow — end-to-end through scripted services', () => {
  it('showcase run with heated-seats failure lands on score == 91', async () => {
    const backend = new LocalBackendService({ inMemory: true });
    await backend.init();
    const camera = new FakeCameraService();
    const cv = new ScriptedCVService();
    const audio = new ScriptedAudioService({
      context: {
        customer: 'Sarah Mitchell',
        dealership: 'Riverside Honda',
        rep: 'Marcus Webb',
        vehicle: '2025 Honda CR-V EX-L',
      },
    });

    const deal = await backend.createDeal({
      dealership: 'Riverside Honda',
      rep: 'Marcus Webb',
      customer: 'Sarah Mitchell',
      vehicle: '2025 Honda CR-V EX-L',
    });

    // Lightweight in-memory store stand-in — mirrors what the Zustand store
    // would do via setFlag / setFeature / etc., minus React.
    const store: StoreLike = {
      deal,
      async setFlag(id: string, value: 'yes' | 'no', source: 'manual' | 'auto') {
        const updated = await backend.updateDeal(deal.id, {
          flags: { [id]: { value, source, setAt: Date.now() } },
        });
        store.deal = updated;
      },
      async setFeature(id: string, value: 'pass' | 'fail') {
        const updated = await backend.updateDeal(deal.id, {
          features: { [id]: value },
        });
        store.deal = updated;
      },
      async setUseCase(id: string, value: boolean) {
        const updated = await backend.updateDeal(deal.id, {
          useCases: { [id]: value },
        });
        store.deal = updated;
      },
      async setPhoto(slot: string, uri: string) {
        const updated = await backend.updateDeal(deal.id, {
          photos: { [slot]: uri },
        });
        store.deal = updated;
      },
      async setTimestamp(key: 'pencil' | 'mgrTo' | 'boSigned' | 'fiHandoff', value: number) {
        const updated = await backend.updateDeal(deal.id, {
          timestamps: { ...store.deal.timestamps, [key]: value },
        });
        store.deal = updated;
      },
      async setBoolean(
        key: 'pencilMarked' | 'boCaptured' | 'fiHandoff' | 'routeCompleted',
        value: boolean
      ) {
        const updated = await backend.updateDeal(deal.id, { [key]: value } as Partial<Deal>);
        store.deal = updated;
      },
    };

    const onDetection = (e: DetectionEvent, s: StoreLike) => {
      // Mirror what each screen's useEffect does when audio fires a detection.
      if (e.type === 'auto-confirm') {
        // Step → flag id mapping must match the screens. 2.1 is satisfied by
        // useCases (set on usecase-detected events below), and 9.1 flips the
        // fiHandoff boolean rather than a flag.
        const flagId = (
          {
            '1.2': 'g2',
            '3.1': 'v1',
            '3.2': 'v2',
            '4.1': 'w1',
            '5.2': 'dr1',
          } as Record<string, string>
        )[e.stepId];
        if (flagId) s.setFlag(flagId, 'yes', 'auto');
        if (e.stepId === '9.1') s.setBoolean('fiHandoff', true);
      }
      if (e.type === 'feature-pass' && e.payload?.featureId) {
        s.setFeature(String(e.payload.featureId), 'pass');
      }
      if (e.type === 'feature-fail' && e.payload?.featureId) {
        s.setFeature(String(e.payload.featureId), 'fail');
      }
      if (e.type === 'usecase-detected' && e.payload?.useCase) {
        s.setUseCase(String(e.payload.useCase), true);
      }
      if (e.type === 'voice-detected' && e.stepId === '8.1') {
        s.setTimestamp('mgrTo', Date.now());
      }
    };

    // 1.1 stays unset — manual greet, the rep skips the tap in the showcase.
    await runScreen(audio, store, 'greet', '1', onDetection);
    await runScreen(audio, store, 'discovery', '2', onDetection);
    await runScreen(audio, store, 'feature_match', '3', onDetection);

    // Front-line ready: audio auto-confirms 3.2; the rep then captures the
    // two front-of-lot photos.
    await runScreen(audio, store, 'front_line_ready', '3.2', onDetection);
    for (const slot of ['p1', 'p2']) {
      await snap(camera, store, slot);
    }

    await runScreen(audio, store, 'walkaround', '4', onDetection);

    // Test drive: audio confirms 5.2 (feature explained) via the script;
    // 5.1 is satisfied when the rep stops the tracked route on Next, which
    // we mirror here by flipping routeCompleted.
    await runScreen(audio, store, 'test_drive', '5', onDetection);
    await store.setBoolean('routeCompleted', true);

    // Trade-in: 4 photo slots filled by the rep.
    await runScreen(audio, store, 'trade_in', '6', onDetection);
    for (const slot of ['t1', 't2', 't3', 't4']) {
      await snap(camera, store, slot);
    }

    // Pencil + manager T.O.
    await store.setBoolean('pencilMarked', true);
    await store.setTimestamp('pencil', Date.now());
    await runScreen(audio, store, 'manager_to', '8', onDetection);

    // Buyer's order — CV extracts; the screen flips boCaptured on success.
    const boPromise = camera.capturePhoto('bo');
    await jest.advanceTimersByTimeAsync(SHUTTER_DURATION_MS);
    const bo = await boPromise;
    const cvPromise = cv.extractBuyersOrder(bo.uri);
    await jest.advanceTimersByTimeAsync(500);
    const extracted = await cvPromise;
    expect(extracted.signaturePresent).toBe(true);
    await store.setBoolean('boCaptured', true);
    await store.setTimestamp('boSigned', Date.now());

    // F&I handoff
    await runScreen(audio, store, 'fi_handoff', '9', onDetection);
    await store.setTimestamp('fiHandoff', Date.now());

    const score = await backend.computeScore(deal.id);
    expect(score.total).toBe(15);
    expect(score.score).toBe(91);

    // Sanity checks on the partial-pass shape.
    const fourTwo = score.items.find((i) => i.id === '4.2');
    expect(fourTwo?.earned).toBeCloseTo(0.6, 5);
    expect(fourTwo?.passed).toBe(true);

    // 1.1 should be the only fully-failed item — guards against drift in the
    // detection wiring (e.g. an auto-confirm that stops firing).
    const failed = score.items.filter((i) => !i.passed);
    expect(failed.map((i) => i.id)).toEqual(['1.1']);
  });
});
