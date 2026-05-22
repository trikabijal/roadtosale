// Contract tests — every AudioService implementation must satisfy these.
// Add `audioServiceContract('Real', () => new RealAudioService())` once a
// real provider lands; both providers run the same suite.

import { AudioService, DetectionEvent, TranscriptEvent } from './AudioService';
import { ScriptedAudioService } from './ScriptedAudioService';

jest.useFakeTimers();

function audioServiceContract(name: string, factory: () => AudioService) {
  describe(`AudioService contract: ${name}`, () => {
    it('emits transcripts when a screen script runs', async () => {
      const svc = factory();
      const transcripts: TranscriptEvent[] = [];
      const session = await svc.start({ screenId: 'greet', stepId: '1' });
      session.onTranscript((e) => transcripts.push(e));
      await jest.advanceTimersByTimeAsync(5000);
      await session.stop();
      expect(transcripts.length).toBeGreaterThan(0);
      expect(transcripts.some((t) => /coffee or water/.test(t.text))).toBe(true);
    });

    it('emits the 1.2 auto-confirm detection on the greet script', async () => {
      const svc = factory();
      const detections: DetectionEvent[] = [];
      const session = await svc.start({ screenId: 'greet', stepId: '1' });
      session.onDetection((e) => detections.push(e));
      await jest.advanceTimersByTimeAsync(5000);
      await session.stop();
      expect(
        detections.find((d) => d.stepId === '1.2' && d.type === 'auto-confirm')
      ).toBeDefined();
    });

    it('emits feature-pass and feature-fail detections on walkaround', async () => {
      const svc = factory();
      const detections: DetectionEvent[] = [];
      const session = await svc.start({ screenId: 'walkaround', stepId: '4' });
      session.onDetection((e) => detections.push(e));
      await jest.advanceTimersByTimeAsync(6000);
      await session.stop();
      const passes = detections.filter((d) => d.type === 'feature-pass');
      const fails = detections.filter((d) => d.type === 'feature-fail');
      expect(passes.length).toBe(3);
      expect(fails.length).toBe(1);
      expect(fails[0].payload?.featureId).toBe('heat');
    });

    it('stop() drops further events to the same session', async () => {
      const svc = factory();
      const transcripts: TranscriptEvent[] = [];
      const session = await svc.start({ screenId: 'greet', stepId: '1' });
      session.onTranscript((e) => transcripts.push(e));
      await session.stop();
      await jest.advanceTimersByTimeAsync(10000);
      expect(transcripts.length).toBe(0);
    });

    it('two concurrent sessions deliver events independently', async () => {
      // Mirrors the screen + modal case: a page-level audio session and a
      // sheet's audio session running on top of it must not cross-talk.
      const svc = factory();
      const greetTranscripts: string[] = [];
      const walkDetectionSteps: string[] = [];
      const greet = await svc.start({ screenId: 'greet', stepId: '1' });
      const walk = await svc.start({ screenId: 'walkaround', stepId: '4' });
      greet.onTranscript((e) => greetTranscripts.push(e.text));
      walk.onDetection((e) => walkDetectionSteps.push(e.stepId));

      await jest.advanceTimersByTimeAsync(6000);
      await greet.stop();
      await walk.stop();

      // Greet transcripts contain greet-only dialogue, never walkaround's.
      expect(greetTranscripts.some((t) => /coffee or water/.test(t))).toBe(true);
      expect(greetTranscripts.some((t) => /sticker/.test(t))).toBe(false);
      // Walkaround detections come from the walkaround script (4.1, 4.2),
      // never from greet's auto-confirm (1.2).
      expect(walkDetectionSteps).toEqual(expect.arrayContaining(['4.1', '4.2']));
      expect(walkDetectionSteps).not.toContain('1.2');
    });
  });
}

audioServiceContract('Scripted', () => new ScriptedAudioService());
// audioServiceContract('Real', () => new RealAudioService()); // when real provider lands
