/**
 * Unit tests for SmartComplyClient.
 * fetch and expo-secure-store are mocked below.
 */

import { SmartComplyClient, AuthError, SmartComplyApiError } from './SmartComplyClient';
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

  // ── submitAnswers([]) short-circuit (C-API-8) ──────────────────────────────

  describe('submitAnswers()', () => {
    it('short-circuits on an empty array — no network call', async () => {
      mockStore['rts_access_token'] = 'tok';

      await client.submitAnswers([]);

      expect(mockFetch).not.toHaveBeenCalled();
    });

    it('POSTs the answers when the array is non-empty', async () => {
      mockStore['rts_access_token'] = 'tok';
      mockFetch.mockResolvedValueOnce(makeJsonResponse(null));

      await client.submitAnswers([
        { userChecksheetId: 1, chksQuestionId: 101 },
      ]);

      expect(mockFetch).toHaveBeenCalledTimes(1);
      const [url, init] = mockFetch.mock.calls[0];
      expect(url).toContain('/userChecksheet/createOrUpdateUserChksAns');
      const body = JSON.parse(init?.body as string);
      expect(Array.isArray(body)).toBe(true);
      expect(body[0].chksQuestionId).toBe(101);
    });
  });

  // ── logout() (C-API-10) ────────────────────────────────────────────────────

  describe('logout()', () => {
    it('deletes the three SecureStore keys and makes no HTTP call', async () => {
      mockStore['rts_access_token'] = 'a';
      mockStore['rts_refresh_token'] = 'r';
      mockStore['rts_user_id'] = '42';

      await client.logout();

      expect(mockStore['rts_access_token']).toBeUndefined();
      expect(mockStore['rts_refresh_token']).toBeUndefined();
      expect(mockStore['rts_user_id']).toBeUndefined();
      expect(mockFetch).not.toHaveBeenCalled();
    });
  });

  // ── uploadTradePhoto() multipart (C-API-11) ────────────────────────────────

  describe('uploadTradePhoto()', () => {
    it('builds a multipart FormData (file, slot, userChecksheetId) and attaches the bearer', async () => {
      mockStore['rts_access_token'] = 'photo-token';
      mockFetch.mockResolvedValueOnce(
        makeJsonResponse({
          id: 5,
          inspectionId: 99,
          slot: 'front_left',
          fileUrl: 'https://cdn/x.jpg',
          uploadedAt: '2026-05-24 10:00:00.000',
        }),
      );

      const result = await client.uploadTradePhoto(99, 'front_left', 'file:///tmp/x.jpg', 'image/jpeg');

      expect(result.id).toBe(5);
      const [url, init] = mockFetch.mock.calls[0];
      expect(url).toContain('/rts/tradePhoto/upload');
      expect(init?.method).toBe('POST');
      // Bearer attached directly (not via the JSON request() path)
      const headers = init?.headers as Record<string, string>;
      expect(headers['Authorization']).toBe('Bearer photo-token');
      // Body is a FormData carrying file + slot + userChecksheetId
      const form = init?.body as FormData;
      expect(form).toBeInstanceOf(FormData);
      expect(form.get('slot')).toBe('front_left');
      expect(form.get('userChecksheetId')).toBe('99');
      expect(form.get('file')).not.toBeNull();
    });
  });

  // ── SmartComplyApiError on non-2xx (C-API-12) ──────────────────────────────

  describe('non-2xx (non-401) responses', () => {
    it('throws SmartComplyApiError carrying the status and response text on 403', async () => {
      mockStore['rts_access_token'] = 'tok';
      mockFetch.mockResolvedValue(makeErrorResponse(403, 'Forbidden: no access'));

      const err = await client.getMyAssignments().catch((e: unknown) => e);
      expect(err).toBeInstanceOf(SmartComplyApiError);
      expect((err as SmartComplyApiError).statusCode).toBe(403);
      expect((err as SmartComplyApiError).message).toBe('Forbidden: no access');
    });
  });

  // ── Concurrent-401 refresh dedup (C-API-6) ─────────────────────────────────

  describe('concurrent 401s', () => {
    it('triggers exactly one refreshToken; all concurrent callers replay with the new token', async () => {
      mockStore['rts_access_token'] = 'expired';
      mockStore['rts_refresh_token'] = 'refresh-token';

      // A controllable refresh response so both 401s land while refresh is in flight.
      let releaseRefresh!: () => void;
      const refreshGate = new Promise<void>((resolve) => { releaseRefresh = resolve; });

      let refreshCallCount = 0;
      mockFetch.mockImplementation((url: string, init?: { headers?: Record<string, string> }) => {
        if (typeof url === 'string' && url.includes('/user/refreshToken')) {
          refreshCallCount += 1;
          return refreshGate.then(() =>
            makeJsonResponse({ accessToken: 'new-access', refreshToken: 'new-refresh' }),
          );
        }
        // Calls carrying the expired token → 401; the retry (new token) → data.
        const tok = init?.headers?.['Authorization'];
        if (tok === 'Bearer expired') return Promise.resolve(makeErrorResponse(401));
        return Promise.resolve(makeJsonResponse(ASSIGNMENTS));
      });

      // Two assignments calls fire concurrently; both 401, both await one refresh.
      const p1 = client.getMyAssignments();
      const p2 = client.getMyAssignments();

      // Let both originals 401 and enqueue on the in-flight refresh.
      await Promise.resolve();
      await Promise.resolve();
      releaseRefresh();

      const [r1, r2] = await Promise.all([p1, p2]);

      expect(r1).toHaveLength(1);
      expect(r2).toHaveLength(1);
      // Exactly one refresh despite two concurrent 401s.
      expect(refreshCallCount).toBe(1);
      expect(mockStore['rts_access_token']).toBe('new-access');
    });
  });
});
