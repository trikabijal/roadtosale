import Constants from 'expo-constants';
import { AudioService } from './audio/AudioService';
import { ScriptedAudioService } from './audio/ScriptedAudioService';
import { CameraService } from './camera/CameraService';
import { FakeCameraService } from './camera/FakeCameraService';
import { RealCameraService } from './camera/RealCameraService';
import { CVService } from './cv/CVService';
import { ScriptedCVService } from './cv/ScriptedCVService';
import { GPSService } from './gps/GPSService';
import { ScriptedGPSService } from './gps/ScriptedGPSService';
import { BackendService } from './backend/BackendService';
import { LocalBackendService } from './backend/LocalBackendService';
import { AuthService } from './auth/AuthService';
import { LocalAuthService } from './auth/LocalAuthService';
import { TelemetryService } from './telemetry/TelemetryService';
import { NoopTelemetryService } from './telemetry/NoopTelemetryService';

export type AppMode = 'showcase' | 'production' | 'hybrid';

export type Services = {
  audio: AudioService;
  camera: CameraService;
  cv: CVService;
  gps: GPSService;
  backend: BackendService;
  auth: AuthService;
  telemetry: TelemetryService;
};

function buildHybridServices(): Services {
  // Hybrid lets developers mix real and scripted implementations via env vars.
  // Each slot defaults to its scripted/local counterpart unless overridden.
  const realCamera = new RealCameraService();
  return {
    audio: new ScriptedAudioService(),
    camera: process.env.EXPO_PUBLIC_REAL_CAMERA === '1' ? realCamera : new FakeCameraService(),
    cv: new ScriptedCVService(),
    gps: new ScriptedGPSService(),
    backend: new LocalBackendService({ tenant: 'hybrid' }),
    auth: new LocalAuthService(),
    telemetry: new NoopTelemetryService(),
  };
}

const DEFAULT_MODE: AppMode = 'showcase';

export function getAppMode(): AppMode {
  // Default to showcase until every real provider has landed; once
  // audio/cv/gps/auth/telemetry have production implementations, flip the
  // default here.
  const fromEnv = process.env.EXPO_PUBLIC_APP_MODE;
  const fromExtra = Constants.expoConfig?.extra?.appMode as string | undefined;
  const raw = fromEnv ?? fromExtra;
  if (!raw) return DEFAULT_MODE;
  if (raw === 'showcase' || raw === 'production' || raw === 'hybrid') {
    return raw;
  }
  // Loud rather than silent — typo'd env vars otherwise look like the app
  // ignoring the override entirely.
   
  console.warn(
    `[AuditPro] Unknown app mode "${raw}" — falling back to "${DEFAULT_MODE}". ` +
      `Valid values: showcase, production, hybrid.`
  );
  return DEFAULT_MODE;
}

export function buildServices(mode: AppMode): Services {
  switch (mode) {
    case 'showcase':
      // Showcase mode: every integration runs through its scripted/local
      // counterpart so the app can be driven through a complete audit without
      // a backend, microphone, or camera permission prompt. This is the mode
      // used during sales pitches and feature walkthroughs.
      return {
        audio: new ScriptedAudioService(),
        // Showcase always uses the fake camera so showcase devices never get
        // a permission prompt mid-pitch — regardless of platform.
        camera: new FakeCameraService(),
        cv: new ScriptedCVService(),
        gps: new ScriptedGPSService(),
        backend: new LocalBackendService({ tenant: 'showcase' }),
        auth: new LocalAuthService(),
        telemetry: new NoopTelemetryService(),
      };
    case 'production':
      // TODO(real-providers): each line below must be a real implementation
      // before production mode can be the default.
      // - audio: Deepgram/Whisper streaming + keyword extraction
      // - cv:    Claude Vision / GPT-4V
      // - gps:   expo-location + dealership route matching
      // - auth:  Trika SSO
      // - telemetry: PostHog/Mixpanel
      // - backend: cloud REST/GraphQL
      throw new Error('Production mode not yet wired — real providers in progress');
    case 'hybrid':
      return buildHybridServices();
    default: {
      const exhaustive: never = mode;
      throw new Error(`Unhandled mode: ${exhaustive as string}`);
    }
  }
}
