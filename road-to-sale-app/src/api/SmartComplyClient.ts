import * as SecureStore from 'expo-secure-store';
import type { ISmartComplyClient } from './ISmartComplyClient';
import type {
  LoginRequest,
  LoginResponse,
  AssignmentDTO,
  ChecksheetDTO,
  UserChecksheetCreateDTO,
  UserChecksheetDTO,
  UserChecksheetAnswerDTO,
  AddAssignmentRequest,
  TradePhotoSlot,
  TradePhotoDTO,
  ApiResponse,
} from './types';

const SECURE_KEY_ACCESS = 'rts_access_token';
const SECURE_KEY_REFRESH = 'rts_refresh_token';
const SECURE_KEY_USER = 'rts_user_id';

export class SmartComplyClient implements ISmartComplyClient {
  private baseUrl: string;
  private _isRefreshing = false;
  private _refreshQueue: Array<(token: string) => void> = [];

  constructor(baseUrl: string) {
    this.baseUrl = baseUrl.replace(/\/$/, '');
  }

  // ── Private helpers ──────────────────────────────────────────────────────

  private async getAccessToken(): Promise<string | null> {
    return SecureStore.getItemAsync(SECURE_KEY_ACCESS);
  }

  private async request<T>(
    method: string,
    path: string,
    body?: unknown,
    isRetry = false,
  ): Promise<T> {
    const token = await this.getAccessToken();
    const headers: Record<string, string> = {
      'Content-Type': 'application/json',
    };
    if (token) headers['Authorization'] = `Bearer ${token}`;

    const response = await fetch(`${this.baseUrl}/api${path}`, {
      method,
      headers,
      body: body !== undefined ? JSON.stringify(body) : undefined,
    });

    if (response.status === 401 && !isRetry) {
      // Attempt token refresh
      const newToken = await this._doRefresh();
      if (newToken) {
        return this.request<T>(method, path, body, true);
      }
      throw new AuthError('Session expired. Please sign in again.');
    }

    if (!response.ok) {
      const text = await response.text().catch(() => '');
      throw new SmartComplyApiError(response.status, text || response.statusText);
    }

    const json: ApiResponse<T> = await response.json();
    return json.data;
  }

  private async _doRefresh(): Promise<string | null> {
    if (this._isRefreshing) {
      return new Promise((resolve) => {
        this._refreshQueue.push(resolve);
      });
    }
    this._isRefreshing = true;
    try {
      const refreshToken = await SecureStore.getItemAsync(SECURE_KEY_REFRESH);
      if (!refreshToken) return null;

      const resp = await fetch(`${this.baseUrl}/api/user/refreshToken`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ refreshToken }),
      });
      if (!resp.ok) {
        await this._clearTokens();
        return null;
      }
      const data: ApiResponse<{ accessToken: string; refreshToken: string }> = await resp.json();
      await SecureStore.setItemAsync(SECURE_KEY_ACCESS, data.data.accessToken);
      await SecureStore.setItemAsync(SECURE_KEY_REFRESH, data.data.refreshToken);
      this._refreshQueue.forEach((cb) => cb(data.data.accessToken));
      return data.data.accessToken;
    } finally {
      this._isRefreshing = false;
      this._refreshQueue = [];
    }
  }

  private async _clearTokens() {
    await Promise.all([
      SecureStore.deleteItemAsync(SECURE_KEY_ACCESS),
      SecureStore.deleteItemAsync(SECURE_KEY_REFRESH),
      SecureStore.deleteItemAsync(SECURE_KEY_USER),
    ]);
  }

  // ── ISmartComplyClient ───────────────────────────────────────────────────

  async login(req: LoginRequest): Promise<LoginResponse> {
    const response = await fetch(`${this.baseUrl}/api/user/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ ...req, deviceType: 'APP' }),
    });
    if (!response.ok) {
      if (response.status === 401) throw new AuthError('Invalid username or password.');
      throw new SmartComplyApiError(response.status, await response.text());
    }
    const json: ApiResponse<LoginResponse> = await response.json();
    await SecureStore.setItemAsync(SECURE_KEY_ACCESS, json.data.accessToken);
    await SecureStore.setItemAsync(SECURE_KEY_REFRESH, json.data.refreshToken);
    await SecureStore.setItemAsync(SECURE_KEY_USER, String(json.data.id));
    return json.data;
  }

  async refreshToken(_token: string): Promise<{ accessToken: string; refreshToken: string }> {
    const result = await this._doRefresh();
    if (!result) throw new AuthError('Could not refresh session.');
    const access = await SecureStore.getItemAsync(SECURE_KEY_ACCESS);
    const refresh = await SecureStore.getItemAsync(SECURE_KEY_REFRESH);
    return { accessToken: access!, refreshToken: refresh! };
  }

  async logout(): Promise<void> {
    await this._clearTokens();
  }

  async getMyAssignments(): Promise<AssignmentDTO[]> {
    return this.request<AssignmentDTO[]>('GET', '/audit/myAssignments');
  }

  async createWalkInAssignment(req: AddAssignmentRequest): Promise<{ count: number }> {
    return this.request<{ count: number }>('POST', '/audit/addAuditAssignments', req);
  }

  async getChecksheetDetail(checksheetId: number): Promise<ChecksheetDTO> {
    return this.request<ChecksheetDTO>('POST', '/checksheet/getChecksheetDetail', { id: checksheetId });
  }

  async startOrResumeSession(dto: UserChecksheetCreateDTO): Promise<UserChecksheetDTO> {
    const result = await this.request<UserChecksheetDTO[]>('POST', '/userChecksheet/createOrUpdate', [dto]);
    return result[0];
  }

  async submitSession(userChecksheetId: number): Promise<UserChecksheetDTO> {
    const result = await this.request<UserChecksheetDTO[]>('POST', '/userChecksheet/createOrUpdate', [{
      id: userChecksheetId,
      status: 'SUBMITTED',
    }]);
    return result[0];
  }

  async submitAnswer(answer: UserChecksheetAnswerDTO): Promise<void> {
    await this.request<unknown>('POST', '/userChecksheet/createOrUpdateUserChksAns', [answer]);
  }

  async submitAnswers(answers: UserChecksheetAnswerDTO[]): Promise<void> {
    if (answers.length === 0) return;
    await this.request<unknown>('POST', '/userChecksheet/createOrUpdateUserChksAns', answers);
  }

  async uploadTradePhoto(
    userChecksheetId: number,
    slot: TradePhotoSlot,
    localUri: string,
    mimeType: string,
  ): Promise<TradePhotoDTO> {
    const formData = new FormData();
    formData.append('file', { uri: localUri, name: `${slot}.jpg`, type: mimeType } as unknown as Blob);
    formData.append('userChecksheetId', String(userChecksheetId));
    formData.append('slot', slot);

    const token = await this.getAccessToken();
    const response = await fetch(`${this.baseUrl}/api/rts/tradePhoto/upload`, {
      method: 'POST',
      headers: token ? { Authorization: `Bearer ${token}` } : {},
      body: formData,
    });
    if (!response.ok) throw new SmartComplyApiError(response.status, await response.text());
    const json: ApiResponse<TradePhotoDTO> = await response.json();
    return json.data;
  }

  async getTradePhotos(userChecksheetId: number): Promise<TradePhotoDTO[]> {
    return this.request<TradePhotoDTO[]>('GET', `/rts/tradePhoto?userChecksheetId=${userChecksheetId}`);
  }

  // ── Static helpers for token inspection ─────────────────────────────────

  static async getStoredUserId(): Promise<number | null> {
    const v = await SecureStore.getItemAsync(SECURE_KEY_USER);
    return v ? parseInt(v, 10) : null;
  }

  static async hasValidToken(): Promise<boolean> {
    const token = await SecureStore.getItemAsync(SECURE_KEY_ACCESS);
    return !!token;
  }
}

// ── Errors ───────────────────────────────────────────────────────────────────

export class AuthError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'AuthError';
  }
}

export class SmartComplyApiError extends Error {
  constructor(public readonly statusCode: number, message: string) {
    super(message);
    this.name = 'SmartComplyApiError';
  }
}
