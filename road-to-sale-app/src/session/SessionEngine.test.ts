import { SessionEngine } from './SessionEngine';
import { setSmartComplyClient } from '../api/clientSingleton';
import type { ISmartComplyClient } from '../api/ISmartComplyClient';
import type { ChecksheetDTO, UserChecksheetDTO } from '../api/types';
import type { SessionCustomer, SessionVehicle, CuePackEntry } from './types';
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
        name: 'Greet',
        orderNo: 1,
        questions: [
          {
            id: 101,
            question: 'Did the salesperson introduce themselves?',
            orderNo: 1,
            isMandatory: true,
            questionResultType: 'SUBJECTIVE_CONDITION',
            resultOptions: [{ id: 1001, option: 'OK', orderNo: 1 }],
          },
        ],
      },
    ],
  };
}

function makeCuePack(): CuePackEntry[] {
  return [
    { cueId: 'workflow.self_introduction', templateQuestionId: 101, required: true },
  ];
}

function makeCustomer(): SessionCustomer {
  return { firstName: 'Sarah', lastName: 'Jones', phone: '555-1234' };
}

function makeVehicle(): SessionVehicle {
  return {
    makeId: 'honda',
    makeName: 'Honda',
    modelId: 'cr-v',
    modelName: 'CR-V',
    trimId: 'sport',
    trimName: 'Sport',
    year: 2026,
  };
}

function makeCueDetection(cueId: string, phrase = 'hello I am Sarah'): CueDetection {
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

function makeUserChecksheetDTO(id = 99): UserChecksheetDTO {
  return {
    id,
    auditId: 1,
    auditeeLocationId: 1,
    checksheetId: 1,
    auditName: 'Road to Sale',
    checksheetName: 'Honda Road to Sale',
    status: 'IN_PROGRESS',
    startedAt: '2026-05-24 09:00:00.000',
  };
}

function makeMockClient(overrides: Partial<ISmartComplyClient> = {}): ISmartComplyClient {
  return {
    login: jest.fn(),
    refreshToken: jest.fn(),
    logout: jest.fn(),
    getMyAssignments: jest.fn(),
    createWalkInAssignment: jest.fn(),
    getChecksheetDetail: jest.fn(),
    startOrResumeSession: jest.fn().mockResolvedValue(makeUserChecksheetDTO()),
    submitSession: jest.fn().mockResolvedValue(makeUserChecksheetDTO()),
    submitAnswer: jest.fn(),
    submitAnswers: jest.fn(),
    uploadTradePhoto: jest.fn(),
    getTradePhotos: jest.fn(),
    ...overrides,
  };
}

// Reset the singleton before each test
beforeEach(() => {
  setSmartComplyClient(makeMockClient());
});

// ─── Tests ────────────────────────────────────────────────────────────────────

describe('SessionEngine', () => {
  describe('startSession()', () => {
    it('calls startOrResumeSession with the correct DTO shape', async () => {
      const mockClient = makeMockClient();
      setSmartComplyClient(mockClient);

      const engine = new SessionEngine();
      await engine.startSession(42, makeCustomer(), makeVehicle(), makeChecksheet(), makeCuePack());

      expect(mockClient.startOrResumeSession).toHaveBeenCalledWith(
        expect.objectContaining({
          auditAssignmentId: 42,
          status: 'IN_PROGRESS',
          shift: 'First',
          submissionVersion: 0,
          frequencyOfFreqOfChkCnt: 1,
        }),
      );
      // startedAt must match "YYYY-MM-DD HH:mm:ss.000" format
      const callArg = (mockClient.startOrResumeSession as jest.Mock).mock.calls[0][0];
      expect(callArg.startedAt).toMatch(/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.000$/);
    });

    it('returns a session with status active', async () => {
      const engine = new SessionEngine();
      const session = await engine.startSession(
        1, makeCustomer(), makeVehicle(), makeChecksheet(), makeCuePack(),
      );

      expect(session.status).toBe('active');
      expect(session.smartComplyAssignmentId).toBe(1);
      expect(session.smartComplyUserChecksheetId).toBe(99);
      expect(session.customer).toEqual(makeCustomer());
      expect(session.vehicle).toEqual(makeVehicle());
      expect(session.id).toBeTruthy();
    });

    it('stores the session and returns it via getSession()', async () => {
      const engine = new SessionEngine();
      const session = await engine.startSession(
        1, makeCustomer(), makeVehicle(), makeChecksheet(), makeCuePack(),
      );

      const retrieved = engine.getSession(session.id);
      expect(retrieved).not.toBeNull();
      expect(retrieved!.id).toBe(session.id);
    });
  });

  describe('endSession()', () => {
    it('transitions status through ending → ended', async () => {
      const statusHistory: string[] = [];
      const engine = new SessionEngine();
      engine.onSessionUpdate((s) => statusHistory.push(s.status));

      const session = await engine.startSession(
        1, makeCustomer(), makeVehicle(), makeChecksheet(), makeCuePack(),
      );
      await engine.endSession(session.id);

      expect(statusHistory).toContain('ending');
      expect(statusHistory).toContain('ended');
      // ended must come after ending
      const endingIdx = statusHistory.lastIndexOf('ending');
      const endedIdx = statusHistory.lastIndexOf('ended');
      expect(endedIdx).toBeGreaterThan(endingIdx);
    });

    it('calls submitSession with the correct userChecksheetId', async () => {
      const mockClient = makeMockClient();
      setSmartComplyClient(mockClient);

      const engine = new SessionEngine();
      const session = await engine.startSession(
        1, makeCustomer(), makeVehicle(), makeChecksheet(), makeCuePack(),
      );
      await engine.endSession(session.id);

      expect(mockClient.submitSession).toHaveBeenCalledWith(99);
    });

    it('returns the ended session', async () => {
      const engine = new SessionEngine();
      const session = await engine.startSession(
        1, makeCustomer(), makeVehicle(), makeChecksheet(), makeCuePack(),
      );
      const ended = await engine.endSession(session.id);

      expect(ended.status).toBe('ended');
      expect(ended.endedAt).toBeInstanceOf(Date);
    });

    it('throws when sessionId does not exist', async () => {
      const engine = new SessionEngine();
      await expect(engine.endSession('nonexistent-id')).rejects.toThrow(
        'Session not found: nonexistent-id',
      );
    });

    it('does not throw if submitSession fails (queues for retry)', async () => {
      const mockClient = makeMockClient({
        submitSession: jest.fn().mockRejectedValue(new Error('network error')),
      });
      setSmartComplyClient(mockClient);

      const engine = new SessionEngine();
      const session = await engine.startSession(
        1, makeCustomer(), makeVehicle(), makeChecksheet(), makeCuePack(),
      );

      await expect(engine.endSession(session.id)).resolves.toBeDefined();
    });
  });

  describe('onSessionUpdate()', () => {
    it('fires on startSession', async () => {
      const engine = new SessionEngine();
      let fired = 0;
      engine.onSessionUpdate(() => fired++);

      await engine.startSession(1, makeCustomer(), makeVehicle(), makeChecksheet(), makeCuePack());
      expect(fired).toBeGreaterThanOrEqual(1);
    });

    it('fires on endSession', async () => {
      const engine = new SessionEngine();
      const events: string[] = [];
      engine.onSessionUpdate((s) => events.push(s.status));

      const session = await engine.startSession(
        1, makeCustomer(), makeVehicle(), makeChecksheet(), makeCuePack(),
      );
      await engine.endSession(session.id);

      expect(events).toContain('ended');
    });

    it('returns an unsubscribe function that stops further calls', async () => {
      const engine = new SessionEngine();
      let count = 0;
      const unsub = engine.onSessionUpdate(() => count++);

      const session = await engine.startSession(
        1, makeCustomer(), makeVehicle(), makeChecksheet(), makeCuePack(),
      );
      const countAfterStart = count;

      unsub();
      await engine.endSession(session.id);

      // After unsubscribe, count must not increase
      expect(count).toBe(countAfterStart);
    });
  });

  // ── processCueDetection() — C-SE-5 (direct) ────────────────────────────────

  describe('processCueDetection()', () => {
    it('forwards a detection to the checklist engine; the resulting state is observable via getSession()', async () => {
      const engine = new SessionEngine();
      const session = await engine.startSession(
        1, makeCustomer(), makeVehicle(), makeChecksheet(), makeCuePack(),
      );

      // Question 101 requires workflow.self_introduction → completing it.
      engine.processCueDetection(session.id, makeCueDetection('workflow.self_introduction'));

      const updated = engine.getSession(session.id)!;
      const q101 = updated.checklist.steps
        .flatMap((s) => s.questions)
        .find((q) => q.question.id === 101)!;
      expect(q101.status).toBe('complete');
      expect(q101.detectedCues.map((c) => c.cueId)).toContain('workflow.self_introduction');
    });

    it('emits a session update when a cue advances the checklist', async () => {
      const engine = new SessionEngine();
      const session = await engine.startSession(
        1, makeCustomer(), makeVehicle(), makeChecksheet(), makeCuePack(),
      );

      let emittedChecklistCompleted = -1;
      engine.onSessionUpdate((s) => { emittedChecklistCompleted = s.checklist.completedCount; });

      engine.processCueDetection(session.id, makeCueDetection('workflow.self_introduction'));

      expect(emittedChecklistCompleted).toBeGreaterThanOrEqual(1);
    });

    it('is a safe no-op for an unknown sessionId', () => {
      const engine = new SessionEngine();
      expect(() =>
        engine.processCueDetection('no-such-session', makeCueDetection('workflow.self_introduction')),
      ).not.toThrow();
    });
  });

  // ── overrideQuestion() — C-SE-6 (through the engine facade) ─────────────────

  describe('overrideQuestion()', () => {
    it('marks a question overridden through the SessionEngine facade', async () => {
      const engine = new SessionEngine();
      const session = await engine.startSession(
        1, makeCustomer(), makeVehicle(), makeChecksheet(), makeCuePack(),
      );

      const state = engine.overrideQuestion(session.id, 101, 'rep confirmed in person');

      expect(state).not.toBeNull();
      const q101 = state!.steps.flatMap((s) => s.questions).find((q) => q.question.id === 101)!;
      expect(q101.status).toBe('overridden');
      expect(q101.overrideNote).toBe('rep confirmed in person');
    });

    it('returns null for an unknown sessionId', () => {
      const engine = new SessionEngine();
      expect(engine.overrideQuestion('no-such-session', 101)).toBeNull();
    });
  });
});
