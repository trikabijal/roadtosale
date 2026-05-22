import { useEffect, useRef, useState } from 'react';
import { useServices } from '../services/context';
import {
  AudioSession,
  DetectionEvent,
  TranscriptEvent,
} from '../services/audio/AudioService';
import { ScriptedAudioService } from '../services/audio/ScriptedAudioService';
import { useDealStore } from '../store/deal';

export type AudioState = {
  transcript: string;
  trigger: string | null;
  detection: DetectionEvent | null;
};

export function useAudioScript(screenId: string, stepId: string): AudioState {
  const services = useServices();
  const deal = useDealStore((s) => s.deal);
  const [state, setState] = useState<AudioState>({
    transcript: 'Listening...',
    trigger: null,
    detection: null,
  });
  const lastScreenIdRef = useRef<string>('');

  useEffect(() => {
    let cancelled = false;
    let session: AudioSession | null = null;
    if (lastScreenIdRef.current === screenId) return;
    lastScreenIdRef.current = screenId;

    // Inject deal context for transcript interpolation when supported.
    const audio = services.audio;
    if (audio instanceof ScriptedAudioService && deal) {
      audio.setContext({
        customer: deal.setup.customer,
        dealership: deal.setup.dealership,
        rep: deal.setup.rep,
        vehicle: deal.setup.vehicle,
      });
    }

    setState({ transcript: 'Listening...', trigger: null, detection: null });
    audio
      .start({ screenId, stepId })
      .then((s) => {
        if (cancelled) {
          // Effect was torn down before start resolved — clean up immediately.
          s.stop().catch(() => {});
          return;
        }
        session = s;
        s.onTranscript((e: TranscriptEvent) => {
          setState((prev) => ({
            ...prev,
            transcript: e.text,
            trigger: e.trigger ?? prev.trigger,
          }));
        });
        s.onDetection((e: DetectionEvent) => {
          setState((prev) => ({ ...prev, detection: e }));
        });
      })
      .catch((err) => {
        if (cancelled) return;
        // Surface failures (e.g. mic permission denied on a real audio
        // provider) instead of leaving the screen showing "Listening..."
        // forever.
         
        console.error(`[AuditPro] audio.start failed for ${screenId}`, err);
        setState((prev) => ({ ...prev, transcript: 'Audio unavailable.' }));
      });

    return () => {
      cancelled = true;
      session?.stop().catch(() => {
        // best-effort cleanup; nothing actionable on failure here.
      });
    };
    // We intentionally key only on screenId so identity changes of services don't restart audio mid-screen.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [screenId]);

  return state;
}
