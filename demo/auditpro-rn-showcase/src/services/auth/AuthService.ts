export type AuthUser = {
  id: string;
  name: string;
  email?: string;
  role: 'rep' | 'manager' | 'fi' | 'admin';
};

export interface AuthService {
  getCurrentUser(): Promise<AuthUser | null>;
  signIn(): Promise<AuthUser>;
  signOut(): Promise<void>;
}
