import { SqliteSessionRepository } from './SqliteSessionRepository';
import type { ISessionRepository } from './ISessionRepository';

let _repo: ISessionRepository | null = null;

export function getSessionRepository(): ISessionRepository {
  if (!_repo) _repo = new SqliteSessionRepository();
  return _repo;
}

export function setSessionRepository(repo: ISessionRepository): void {
  _repo = repo;
}
