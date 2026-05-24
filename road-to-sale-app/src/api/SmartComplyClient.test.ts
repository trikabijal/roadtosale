/**
 * Unit tests for SmartComplyClient.
 * fetch and expo-secure-store are mocked below.
 */

import { SmartComplyClient, AuthError } from './SmartComplyClient';
import type { ApiResponse, LoginResponse, AssignmentDTO, UserChecksheetCreateDTO } from './types';

// ── Mock expo-secure-store ────────────────────────────────────────────────────

const mockStore: Record<string, string> = {};

jest.mock('expo-secure-store', () => ({
  getItemAsync: jest.fn(async (key: string) => mockStore[key] ?? null),
  setItemAsync: jest.fn(async (key: string, value: string) => { mockStore[key] = value; }),
  deleteItemAsync: jest.fn(async (key: string) => { delete mockStore[key]; }),
}));

// ── Mock fetch ────────────────────────────────────────────────────────────────

const mockFetch = jest.fn();
global.fetch = mockFetch as typeof fetch;

// ── Helpers ───────────────────────────────────────────────────────────────────

function makeJsonResponse<T>(data: T, status = 200): Response {
  const envelope: ApiResponse<T> = { status, message: 'OK', data };
  return {
    ok: status >= 200 && status < 300,
    status,
    statusText: status === 200 ? 'OK' : 'Error',
    json: async () => envelope,
    text: async () => JSON.stringify(envelope),
  } as unknown as Response;
}

function makeErrorResponse(status: number, body = 'error'): Response {
  return {
    ok: false,
    status,
    statusText: 'Error',
    json: async () => ({ status, message: body, data: null }),
    text: async () => body,
  } as unknown as Response;
}

const LOGIN_RESPONSE: LoginResponse = {
  accessToken: 'access-abc',
  refreshToken: 'refresh-xyz',
  id: 42,
  username: 'rep1',
  name: 'Alice Rep',
  permissions: ['RTS_ACCESS'],
  allRoles: ['SALES_REP'],
};

const ASSIGNMENTS: AssignmentDTO[] = [
  {
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
  },
];

// ── Tests ─────────────────────────────────────────────────────────────────────

describe('SmartComplyClient', () => {
  let client: SmartComplyClient;

  beforeEach(() => {
    // Reset state between tests
    Object.keys(mockStore).forEach((k) => delete mockStore[k]);
    mockFetch.mockReset();
    client = new SmartComplyClient('http://localhost:8089');
  });

  // ── login() ──────────────────────────────────────────────────────────────

  describe('login()', () => {
    it('stores access and refresh tokens in SecureStore on success', async () => {
      mockFetch.mockResolvedValueOnce(makeJsonResponse(LOGIN_RESPONSE, 200));

      const result = await client.login({ username: 'rep1', password: 'pass', deviceType: 'APP' });

      expect(result.accessToken).toBe('access-abc');
      expect(mockStore['rts_access_token']).toBe('access-abc');
      expect(mockStore['rts_refresh_token']).toBe('refresh-xyz');
      expect(mockStore['rts_user_id']).toBe('42');
    });

    it('throws AuthError on 401', async () => {
      mockFetch.mockResolvedValueOnce(makeErrorResponse(401));

      await expect(
        client.login({ username: 'bad', password: 'wrong', deviceType: 'APP' }),
      ).rejects.toThrow(AuthError);
    });

    it('sends deviceType APP in the request body', async () => {
      mockFetch.mockResolvedValueOnce(makeJsonResponse(LOGIN_RESPONSE, 200));

      await client.login({ username: 'rep1', password: 'pass', deviceType: 'APP' });

      const callBody = JSON.parse(mockFetch.mock.calls[0][1]?.body as string);
      expect(callBody.deviceType).toBe('APP');
    });
  });

  // ── getMyAssignments() ───────────────────────────────────────────────────

  describe('getMyAssignments()', () => {
    it('sends Authorization: Bearer header when a token is stored', async () => {
      mockStore['rts_access_token'] = 'my-token';
      mockFetch.mockResolvedValueOnce(makeJsonResponse(ASSIGNMENTS));

      await client.getMyAssignments();

      const headers = mockFetch.mock.calls[0][1]?.headers as Record<string, string>;
      expect(headers['Authorization']).toBe('Bearer my-token');
    });

    it('triggers token refresh + retry on 401, then returns data', async () => {
      // Stored tokens
      mockStore['rts_access_token'] = 'expired-token';
      mockStore['rts_refresh_token'] = 'refresh-token';

      // First call → 401, refresh call → success, retry → data
      mockFetch
        .mockResolvedValueOnce(makeErrorResponse(401))          // original request 401
        .mockResolvedValueOnce(makeJsonResponse(               // refresh endpoint
          { accessToken: 'new-access', refreshToken: 'new-refresh' },
        ))
        .mockResolvedValueOnce(makeJsonResponse(ASSIGNMENTS)); // retry

      const result = await client.getMyAssignments();

      expect(result).toHaveLength(1);
      expect(result[0].assignmentId).toBe(1);
      // New token stored
      expect(mockStore['rts_access_token']).toBe('new-access');
      // Three fetch calls total
      expect(mockFetch).toHaveBeenCalledTimes(3);
    });

    it('throws AuthError when refresh also fails', async () => {
      mockStore['rts_access_token'] = 'expired';
      mockStore['rts_refresh_token'] = 'bad-refresh';

      mockFetch
        .mockResolvedValueOnce(makeErrorResponse(401))  // original 401
        .mockResolvedValueOnce(makeErrorResponse(401)); // refresh fails

      await expect(client.getMyAssignments()).rejects.toThrow(AuthError);
    });
  });

  // ── startOrResumeSession() ───────────────────────────────────────────────

  describe('startOrResumeSession()', () => {
    it('wraps the DTO in an array and returns the first element', async () => {
      mockStore['rts_access_token'] = 'tok';

      const sessionResponse = {
        id: 55,
        auditId: 10,
        auditeeLocationId: 7,
        checksheetId: 100,
        auditName: 'NADA Road to Sale',
        checksheetName: 'Road to Sale v1',
        status: 'IN_PROGRESS' as const,
        startedAt: '2026-05-24 09:00:00.000',
      };

      mockFetch.mockResolvedValueOnce(makeJsonResponse([sessionResponse]));

      const dto: UserChecksheetCreateDTO = {
        auditAssignmentId: 1,
        status: 'IN_PROGRESS',
        shift: 'MORNING',
        startedAt: '2026-05-24 09:00:00.000',
        submissionVersion: 1,
        frequencyOfFreqOfChkCnt: 1,
      };

      const result = await client.startOrResumeSession(dto);

      // Verify the body was wrapped in an array
      const callBody = JSON.parse(mockFetch.mock.calls[0][1]?.body as string);
      expect(Array.isArray(callBody)).toBe(true);
      expect(callBody[0].auditAssignmentId).toBe(1);

      // Verify unwrapped result
      expect(result.id).toBe(55);
      expect(result.status).toBe('IN_PROGRESS');
    });
  });

  // ── Static helpers ───────────────────────────────────────────────────────

  describe('SmartComplyClient static helpers', () => {
    it('hasValidToken() returns true when a token is stored', async () => {
      mockStore['rts_access_token'] = 'some-token';
      expect(await SmartComplyClient.hasValidToken()).toBe(true);
    });

    it('hasValidToken() returns false when no token is stored', async () => {
      expect(await SmartComplyClient.hasValidToken()).toBe(false);
    });

    it('getStoredUserId() returns parsed int when stored', async () => {
      mockStore['rts_user_id'] = '42';
      expect(await SmartComplyClient.getStoredUserId()).toBe(42);
    });

    it('getStoredUserId() returns null when not stored', async () => {
      expect(await SmartComplyClient.getStoredUserId()).toBeNull();
    });
  });
});
