import { ChecklistEngine } from './ChecklistEngine';
import type { CuePackEntry } from './types';
import type { ChecksheetDTO } from '../api/types';
import type { CueDetection } from '../voice/types';

// ─── Test fixtures ────────────────────────────────────────────────────────────

function makeChecksheet(): ChecksheetDTO {
  return {
    id: 1,
    name: 'Honda Road to Sale',
    code: 'HONDA_RTS',
    status: 'APPROVED',
    headers: [
      {
        id: 10,
        name: 'Greet / Hospitality',
        orderNo: 1,
        questions: [
          {
            id: 101,
            question: 'Did the salesperson introduce themselves?',
            orderNo: 1,
            isMandatory: true,
            questionResultType: 'SUBJECTIVE_CONDITION',
            resultOptions: [
              { id: 1001, option: 'OK', orderNo: 1 },
              { id: 1002, option: 'Not OK', orderNo: 2 },
            ],
          },
          {
            id: 102,
            question: 'Was the customer offered hospitality?',
            orderNo: 2,
            isMandatory: false,
            questionResultType: 'SUBJECTIVE_CONDITION',
            resultOptions: [
              { id: 1003, option: 'OK', orderNo: 1 },
              { id: 1004, option: 'Not OK', orderNo: 2 },
            ],
          },
        ],
      },
      {
        id: 20,
        name: 'Discovery',
        orderNo: 2,
        questions: [
          {
            id: 201,
            question: 'Did the salesperson identify the budget?',
            orderNo: 1,
            isMandatory: true,
            questionResultType: 'SUBJECTIVE_CONDITION',
            resultOptions: [
              { id: 2001, option: 'OK', orderNo: 1 },
              { id: 2002, option: 'Not OK', orderNo: 2 },
            ],
          },
        ],
      },
    ],
  };
}

function makeCuePack(): CuePackEntry[] {
  return [
    // Question 101: requires workflow.self_introduction
    { cueId: 'workflow.self_introduction', templateQuestionId: 101, required: true },
    // Question 101: optional supporting cue
    { cueId: 'workflow.customer_name_use', templateQuestionId: 101, required: false },
    // Question 102: one optional cue (no required cues → any detection = complete)
    { cueId: 'workflow.hospitality_offer', templateQuestionId: 102, required: false },
    // Question 201: requires workflow.discovery_budget_signal
    { cueId: 'workflow.discovery_budget_signal', templateQuestionId: 201, required: true },
  ];
}

function makeCueDetection(cueId: string, phrase = 'test phrase'): CueDetection {
  return {
    cue_id: cueId,
    matched_phrase: phrase,
    timestamp_ms: 1000,
    confidence: 0.9,
    triggering_event: {
      text: phrase,
      stability: 'final',
      timestamp_ms: 1000,
      latency_ms_from_audio_start: 200,
      confidence: 0.9,
      engine_metadata: {},
    },
  };
}

// ─── Tests ────────────────────────────────────────────────────────────────────

describe('ChecklistEngine', () => {
  describe('init()', () => {
    it('builds correct initial state — all questions pending, counts correct', () => {
      const engine = new ChecklistEngine();
      engine.init(makeChecksheet(), makeCuePack());
      const state = engine.getState();

      expect(state.steps).toHaveLength(2);
      expect(state.totalCount).toBe(3);
      expect(state.completedCount).toBe(0);

      // All questions start pending
      for (const step of state.steps) {
        for (const qs of step.questions) {
          expect(qs.status).toBe('pending');
          expect(qs.detectedCues).toHaveLength(0);
          expect(qs.voiceAutoCompleted).toBe(false);
        }
      }

      // No steps are complete yet
      for (const step of state.steps) {
        expect(step.isComplete).toBe(false);
      }
    });
  });

  describe('processCueDetection()', () => {
    it('marks question partial when only a supporting (non-required) cue fires', () => {
      const engine = new ChecklistEngine();
      engine.init(makeChecksheet(), makeCuePack());

      // workflow.customer_name_use is optional for question 101 — required cue is self_introduction
      const state = engine.processCueDetection(makeCueDetection('workflow.customer_name_use'));

      const step1 = state.steps[0];
      const q101 = step1.questions.find((q) => q.question.id === 101)!;
      expect(q101.status).toBe('partial');
      expect(q101.detectedCues).toHaveLength(1);
      expect(q101.detectedCues[0].cueId).toBe('workflow.customer_name_use');
      expect(step1.isComplete).toBe(false);
    });

    it('marks question complete when the required cue fires', () => {
      const engine = new ChecklistEngine();
      engine.init(makeChecksheet(), makeCuePack());

      const state = engine.processCueDetection(
        makeCueDetection('workflow.self_introduction', 'my name is Marcus'),
      );

      const q101 = state.steps[0].questions.find((q) => q.question.id === 101)!;
      expect(q101.status).toBe('complete');
      expect(q101.voiceAutoCompleted).toBe(true);
      expect(q101.detectedCues[0].transcriptSnippet).toBe('my name is Marcus');
    });

    it('does not add duplicate detectedCues for the same cue_id', () => {
      const engine = new ChecklistEngine();
      engine.init(makeChecksheet(), makeCuePack());

      engine.processCueDetection(makeCueDetection('workflow.self_introduction'));
      const state = engine.processCueDetection(makeCueDetection('workflow.self_introduction'));

      const q101 = state.steps[0].questions.find((q) => q.question.id === 101)!;
      expect(q101.detectedCues).toHaveLength(1);
      expect(q101.status).toBe('complete');
    });

    it('marks step complete when all mandatory questions in the step are complete', () => {
      const engine = new ChecklistEngine();
      engine.init(makeChecksheet(), makeCuePack());

      // question 101 is mandatory; question 102 is not mandatory
      const state = engine.processCueDetection(makeCueDetection('workflow.self_introduction'));

      const step1 = state.steps[0];
      expect(step1.isComplete).toBe(true);
    });

    it('updates completedCount when questions complete', () => {
      const engine = new ChecklistEngine();
      engine.init(makeChecksheet(), makeCuePack());

      let state = engine.processCueDetection(makeCueDetection('workflow.self_introduction'));
      expect(state.completedCount).toBe(1);

      state = engine.processCueDetection(makeCueDetection('workflow.discovery_budget_signal'));
      expect(state.completedCount).toBe(2);
    });

    it('completes a no-required-cue question when any cue fires', () => {
      const engine = new ChecklistEngine();
      engine.init(makeChecksheet(), makeCuePack());

      // question 102 has no required cue — any detection should complete it
      const state = engine.processCueDetection(makeCueDetection('workflow.hospitality_offer'));
      const q102 = state.steps[0].questions.find((q) => q.question.id === 102)!;
      expect(q102.status).toBe('complete');
    });

    it('is a no-op for a cue not in the pack', () => {
      const engine = new ChecklistEngine();
      engine.init(makeChecksheet(), makeCuePack());

      const before = engine.getState();
      const after = engine.processCueDetection(makeCueDetection('workflow.some_unknown_cue'));

      expect(after).toBe(before); // same reference — no mutation
    });
  });

  describe('overrideQuestion()', () => {
    it('sets status to overridden and records the note', () => {
      const engine = new ChecklistEngine();
      engine.init(makeChecksheet(), makeCuePack());

      const state = engine.overrideQuestion(101, 'Manager witnessed greeting');
      const q101 = state.steps[0].questions.find((q) => q.question.id === 101)!;

      expect(q101.status).toBe('overridden');
      expect(q101.overrideNote).toBe('Manager witnessed greeting');
    });

    it('recomputes step isComplete after override of the mandatory question', () => {
      const engine = new ChecklistEngine();
      engine.init(makeChecksheet(), makeCuePack());

      const state = engine.overrideQuestion(101);
      expect(state.steps[0].isComplete).toBe(true);
    });

    it('increments completedCount when a question is overridden', () => {
      const engine = new ChecklistEngine();
      engine.init(makeChecksheet(), makeCuePack());

      const state = engine.overrideQuestion(201);
      expect(state.completedCount).toBe(1);
    });

    it('does not downgrade an already-overridden question on cue detection', () => {
      const engine = new ChecklistEngine();
      engine.init(makeChecksheet(), makeCuePack());

      engine.overrideQuestion(101, 'witnessed');
      const state = engine.processCueDetection(makeCueDetection('workflow.self_introduction'));
      const q101 = state.steps[0].questions.find((q) => q.question.id === 101)!;

      expect(q101.status).toBe('overridden');
      expect(q101.overrideNote).toBe('witnessed');
    });
  });

  describe('onUpdate()', () => {
    it('fires listener on every processCueDetection call', () => {
      const engine = new ChecklistEngine();
      engine.init(makeChecksheet(), makeCuePack());

      const calls: number[] = [];
      engine.onUpdate(() => calls.push(1));

      engine.processCueDetection(makeCueDetection('workflow.self_introduction'));
      engine.processCueDetection(makeCueDetection('workflow.discovery_budget_signal'));

      expect(calls).toHaveLength(2);
    });

    it('fires listener on overrideQuestion call', () => {
      const engine = new ChecklistEngine();
      engine.init(makeChecksheet(), makeCuePack());

      let fired = 0;
      engine.onUpdate(() => fired++);
      engine.overrideQuestion(101);

      expect(fired).toBe(1);
    });

    it('returns an unsubscribe function that stops further listener calls', () => {
      const engine = new ChecklistEngine();
      engine.init(makeChecksheet(), makeCuePack());

      let count = 0;
      const unsub = engine.onUpdate(() => count++);

      engine.processCueDetection(makeCueDetection('workflow.self_introduction'));
      expect(count).toBe(1);

      unsub();
      engine.processCueDetection(makeCueDetection('workflow.discovery_budget_signal'));
      expect(count).toBe(1); // No further increment after unsubscribe
    });

    it('passes updated state to the listener', () => {
      const engine = new ChecklistEngine();
      engine.init(makeChecksheet(), makeCuePack());

      let lastState = engine.getState();
      engine.onUpdate((s) => { lastState = s; });

      engine.processCueDetection(makeCueDetection('workflow.self_introduction'));
      expect(lastState.completedCount).toBe(1);
    });
  });
});
