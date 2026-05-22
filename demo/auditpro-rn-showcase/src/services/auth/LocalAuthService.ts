import { AuthService, AuthUser } from './AuthService';

// Single-user, on-device auth. Stands in for SSO until the real provider
// (Trika SSO) lands; the user identity is opaque to the rest of the app —
// every consumer reads it through getCurrentUser().
export class LocalAuthService implements AuthService {
  private user: AuthUser;

  constructor(opts: { user?: AuthUser } = {}) {
    this.user = opts.user ?? {
      id: 'local-rep',
      name: 'Sales Rep',
      role: 'rep',
    };
  }

  async getCurrentUser(): Promise<AuthUser | null> {
    return this.user;
  }

  async signIn(): Promise<AuthUser> {
    return this.user;
  }

  async signOut(): Promise<void> {
    // No persistent session to tear down; real SSO will replace this.
  }
}
