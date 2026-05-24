import { SmartComplyClient } from './SmartComplyClient';
import type { ISmartComplyClient } from './ISmartComplyClient';
import Constants from 'expo-constants';

let _client: ISmartComplyClient | null = null;

export function getSmartComplyClient(): ISmartComplyClient {
  if (!_client) {
    const url: string =
      (Constants.expoConfig?.extra?.smartComplyApiUrl as string | undefined) ??
      process.env.SMARTCOMPLY_API_URL ??
      'http://localhost:8089';
    _client = new SmartComplyClient(url);
  }
  return _client;
}

/** Replace client in tests */
export function setSmartComplyClient(client: ISmartComplyClient): void {
  _client = client;
}
