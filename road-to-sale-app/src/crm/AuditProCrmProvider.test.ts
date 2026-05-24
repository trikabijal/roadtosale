/**
 * Unit tests for AuditProCrmProvider.
 * All tests use a mocked ISmartComplyClient — no real HTTP.
 */

import { AuditProCrmProvider } from './AuditProCrmProvider';
import type { ISmartComplyClient } from '../api/ISmartComplyClient';
import type { AssignmentDTO } from '../api/types';

// ── Fixtures ──────────────────────────────────────────────────────────────────

function makeAssignment(overrides: Partial<AssignmentDTO> = {}): AssignmentDTO {
  return {
    assignmentId: 1,
    auditId: 10,
    auditName: 'NADA Road to Sale',
    checksheetId: 100,
    checksheetName: 'Road to Sale v1',
    locationLabel: 'Honda of Springfield',
    userChecksheetId: null,
    status: 'ASSIGNED',
    answeredQuestions: 0,
    totalQuestions: 12,
    ...overrides,
  };
}

function makeMockClient(
  assignments: AssignmentDTO[] = [],
  createWalkInResult: { count: number } = { count: 1 },
): jest.Mocked<ISmartComplyClient> {
  return {
    login: jest.fn(),
    refreshToken: jest.fn(),
    logout: jest.fn(),
    getMyAssignments: jest.fn().mockResolvedValue(assignments),
    createWalkInAssignment: jest.fn().mockResolvedValue(createWalkInResult),
    getChecksheetDetail: jest.fn(),
    startOrResumeSession: jest.fn(),
    submitSession: jest.fn(),
    submitAnswer: jest.fn(),
    submitAnswers: jest.fn(),
    uploadTradePhoto: jest.fn(),
    getTradePhotos: jest.fn(),
  } as jest.Mocked<ISmartComplyClient>;
}

// ── Tests ─────────────────────────────────────────────────────────────────────

describe('AuditProCrmProvider', () => {

  // ── getTodayAppointments() ───────────────────────────────────────────────

  describe('getTodayAppointments()', () => {
    it('returns only ASSIGNED and IN_PROGRESS assignments, mapped to Appointments', async () => {
      const assignments = [
        makeAssignment({ assignmentId: 1, status: 'ASSIGNED' }),
        makeAssignment({ assignmentId: 2, status: 'IN_PROGRESS' }),
        makeAssignment({ assignmentId: 3, status: 'SUBMITTED' }),
        makeAssignment({ assignmentId: 4, status: 'VALIDATED' }),
        makeAssignment({ assignmentId: 5, status: 'APPROVED' }),
      ];
      const client = makeMockClient(assignments);
      const provider = new AuditProCrmProvider(client);

      const result = await provider.getTodayAppointments('store-1');

      expect(result).toHaveLength(2);
      expect(result.map((a) => a.id)).toEqual(['1', '2']);
    });

    it('maps assignment fields correctly onto Appointment', async () => {
      const assignment = makeAssignment({
        assignmentId: 77,
        auditId: 20,
        checksheetId: 200,
        userChecksheetId: 999,
        status: 'IN_PROGRESS',
        locationLabel: 'Test Location',
      });
      const client = makeMockClient([assignment]);
      const provider = new AuditProCrmProvider(client);

      const [appt] = await provider.getTodayAppointments('store-1');

      expect(appt.id).toBe('77');
      expect(appt.scheduledAt).toBeNull();
      expect(appt.source).toBe('auditpro');
      expect(appt.smartComply).toEqual({
        assignmentId: 77,
        auditId: 20,
        checksheetId: 200,
        userChecksheetId: 999,
        status: 'IN_PROGRESS',
      });
    });

    it('returns an empty array when there are no active assignments', async () => {
      const client = makeMockClient([
        makeAssignment({ status: 'SUBMITTED' }),
        makeAssignment({ status: 'APPROVED' }),
      ]);
      const provider = new AuditProCrmProvider(client);

      const result = await provider.getTodayAppointments('store-1');
      expect(result).toHaveLength(0);
    });
  });

  // ── getAppointment() ─────────────────────────────────────────────────────

  describe('getAppointment()', () => {
    it('finds the correct assignment by string id', async () => {
      const assignments = [
        makeAssignment({ assignmentId: 10 }),
        makeAssignment({ assignmentId: 20 }),
      ];
      const client = makeMockClient(assignments);
      const provider = new AuditProCrmProvider(client);

      const result = await provider.getAppointment('20');

      expect(result.id).toBe('20');
      expect(result.smartComply?.assignmentId).toBe(20);
    });

    it('throws if the assignment is not found', async () => {
      const client = makeMockClient([makeAssignment({ assignmentId: 1 })]);
      const provider = new AuditProCrmProvider(client);

      await expect(provider.getAppointment('999')).rejects.toThrow('Assignment not found: 999');
    });
  });

  // ── createWalkIn() ───────────────────────────────────────────────────────

  describe('createWalkIn()', () => {
    it('calls createWalkInAssignment with parsed repId and env locationId', async () => {
      const existingAssignment = makeAssignment({ assignmentId: 100, status: 'ASSIGNED' });
      const client = makeMockClient([existingAssignment]);
      const provider = new AuditProCrmProvider(client);

      process.env.SMARTCOMPLY_WALKIN_AUDIT_ID = '5';
      process.env.SMARTCOMPLY_LOCATION_ID = '99';

      await provider.createWalkIn('42', 'store-1');

      expect(client.createWalkInAssignment).toHaveBeenCalledWith({
        auditId: 5,
        assignments: [{ auditeeLocationId: 99, operatorUserId: 42 }],
      });
    });

    it('returns the newest ASSIGNED appointment after creation', async () => {
      // After creation, getMyAssignments returns two ASSIGNED assignments
      // The newest one has the highest assignmentId
      const olderAssignment = makeAssignment({ assignmentId: 50, status: 'ASSIGNED' });
      const newerAssignment = makeAssignment({ assignmentId: 51, status: 'ASSIGNED' });

      // First call (from createWalkIn getMyAssignments) returns both
      const client = makeMockClient([olderAssignment, newerAssignment]);
      const provider = new AuditProCrmProvider(client);

      const result = await provider.createWalkIn('42', 'store-1');

      expect(result.id).toBe('51');
    });

    it('throws if no ASSIGNED assignment exists after creation', async () => {
      // All assignments are in terminal states
      const client = makeMockClient([makeAssignment({ status: 'SUBMITTED' })]);
      const provider = new AuditProCrmProvider(client);

      await expect(provider.createWalkIn('42', 'store-1')).rejects.toThrow(
        'Walk-in assignment creation failed.',
      );
    });
  });
});
