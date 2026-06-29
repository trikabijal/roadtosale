import { Platform } from 'react-native';
import { registerStrategy } from 'voice-engine';
import { AppleSpeechTranscriberStrategy } from 'voice-engine';
import { getVoiceEngine } from './NativeVoiceModule';

export function setupVoiceEngine(): void {
  if (Platform.OS === 'ios') {
    registerStrategy(new AppleSpeechTranscriberStrategy(getVoiceEngine()));
  }
}
