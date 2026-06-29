import { registerRootComponent } from 'expo';
import { setupVoiceEngine } from './src/voice/voiceEngineSetup';
import App from './App';

setupVoiceEngine();
registerRootComponent(App);
