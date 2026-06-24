import { Platform } from 'react-native';
import { registerStrategy } from 'voice-engine';
import { AppleSpeechTranscriberStrategy } from 'voice-engine';
import { getVoiceEngine } from './NativeVoiceModule';

/**
 * Call once at app startup (index.ts) before any session begins.
 *
 * On iOS: registers the Apple strategy backed by the real native module.
 * On other platforms: no-op — the mock strategy handles those environments.
 */
export function setupVoiceEngine(): void {
  if (Platform.OS === 'ios') {
    registerStrategy(new AppleSpeechTranscriberStrategy(getVoiceEngine()));
  }
}
