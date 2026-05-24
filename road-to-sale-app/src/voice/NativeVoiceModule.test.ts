/**
 * Unit tests for NativeVoiceModule (JS bridge layer).
 *
 * react-native is fully mocked — no native code executed.
 */

// ── Mock react-native ─────────────────────────────────────────────────────────
//
// jest.mock() is hoisted before all imports, so outer-scope jest.fn() variables
// cannot be referenced inside the factory. Instead we access the mock methods
// via the imported NativeModules after module resolution.

// Capture event listeners registered during NativeEventEmitter construction
// so tests can fire synthetic native events.
type ListenerFn = (...args: unknown[]) => void;
const registeredListeners: Record<string, ListenerFn[]> = {};

jest.mock('react-native', () => {
  const mockModule = {
    requestPermissions: jest.fn().mockResolvedValue('granted'),
    startListening: jest.fn().mockResolvedValue(undefined),
    stopListening: jest.fn().mockResolvedValue(undefined),
    mute: jest.fn(),
    unmute: jest.fn(),
  };

  return {
    NativeModules: {
      RtsVoiceModule: mockModule,
    },
    NativeEventEmitter: jest.fn().mockImplementation(() => ({
      addListener: jest.fn((eventName: string, cb: ListenerFn) => {
        if (!registeredListeners[eventName]) registeredListeners[eventName] = [];
        registeredListeners[eventName].push(cb);
        return { remove: jest.fn() };
      }),
    })),
    Platform: { OS: 'ios' },
  };
});

// ── Helpers ───────────────────────────────────────────────────────────────────

function fireNativeEvent(name: string, payload: unknown) {
  (registeredListeners[name] ?? []).forEach(cb => cb(payload));
}

// ── Imports (after mocks) ─────────────────────────────────────────────────────

import { NativeModules } from 'react-native';
import { getVoiceEngine, setVoiceEngine } from './NativeVoiceModule';
import type { TranscriptEvent } from './types';
import type { VoiceEngineState } from './IVoiceEngine';

// Typed handle to the mock native module
const mockNative = NativeModules.RtsVoiceModule as {
  requestPermissions: jest.Mock;
  startListening: jest.Mock;
  stopListening: jest.Mock;
  mute: jest.Mock;
  unmute: jest.Mock;
};

// ── Lifecycle ─────────────────────────────────────────────────────────────────

beforeEach(() => {
  // Reset the singleton so each test gets a fresh instance
  setVoiceEngine(null as unknown as ReturnType<typeof getVoiceEngine>);
  // Clear captured listeners
  Object.keys(registeredListeners).forEach(k => delete registeredListeners[k]);
  jest.clearAllMocks();
  // Re-apply default mock implementations after clearAllMocks
  mockNative.requestPermissions.mockResolvedValue('granted');
  mockNative.startListening.mockResolvedValue(undefined);
  mockNative.stopListening.mockResolvedValue(undefined);
});

// ── Tests ─────────────────────────────────────────────────────────────────────

describe('NativeVoiceModule', () => {

  // ── requestPermissions() ────────────────────────────────────────────────────

  describe('requestPermissions()', () => {
    it('delegates to native RtsVoiceModule and returns "granted"', async () => {
      const engine = getVoiceEngine();
      const result = await engine.requestPermissions();
      expect(mockNative.requestPermissions).toHaveBeenCalledTimes(1);
      expect(result).toBe('granted');
    });
  });

  // ── start() ─────────────────────────────────────────────────────────────────

  describe('start()', () => {
    it('transitions state to "starting" synchronously before awaiting native call', async () => {
      const engine = getVoiceEngine();
      const states: VoiceEngineState[] = [];
      engine.onStateChange(s => states.push(s));

      const startPromise = engine.start({ language: 'en-US' });
      // "starting" must be recorded before the async boundary resolves
      expect(states).toContain('starting');

      await startPromise;
      expect(mockNative.startListening).toHaveBeenCalledWith('en-US', []);
    });

    it('passes customVocabulary to native startListening', async () => {
      const engine = getVoiceEngine();
      await engine.start({ language: 'en-US', customVocabulary: ['Honda', 'CR-V'] });
      expect(mockNative.startListening).toHaveBeenCalledWith('en-US', ['Honda', 'CR-V']);
    });

    it('transitions to "listening" when native fires onVoiceStateChange', async () => {
      const engine = getVoiceEngine();
      const states: VoiceEngineState[] = [];
      engine.onStateChange(s => states.push(s));

      await engine.start({ language: 'en-US' });
      fireNativeEvent('onVoiceStateChange', 'listening');

      expect(states).toContain('listening');
      expect(engine.state).toBe('listening');
    });
  });

  // ── stop() ──────────────────────────────────────────────────────────────────

  describe('stop()', () => {
    it('transitions state to "stopping" then calls native stopListening', async () => {
      const engine = getVoiceEngine();
      const states: VoiceEngineState[] = [];
      engine.onStateChange(s => states.push(s));

      const stopPromise = engine.stop();
      expect(states).toContain('stopping');

      await stopPromise;
      expect(mockNative.stopListening).toHaveBeenCalledTimes(1);
    });

    it('reflects "stopped" state when native fires onVoiceStateChange', async () => {
      const engine = getVoiceEngine();
      await engine.stop();
      fireNativeEvent('onVoiceStateChange', 'stopped');
      expect(engine.state).toBe('stopped');
    });
  });

  // ── mute() / unmute() ───────────────────────────────────────────────────────

  describe('mute() / unmute()', () => {
    it('mute() calls native mute and updates state to "muted"', () => {
      const engine = getVoiceEngine();
      const states: VoiceEngineState[] = [];
      engine.onStateChange(s => states.push(s));

      engine.mute();

      expect(mockNative.mute).toHaveBeenCalledTimes(1);
      expect(states).toContain('muted');
      expect(engine.state).toBe('muted');
    });

    it('unmute() calls native unmute and updates state to "listening"', () => {
      const engine = getVoiceEngine();
      const states: VoiceEngineState[] = [];
      engine.onStateChange(s => states.push(s));

      engine.unmute();

      expect(mockNative.unmute).toHaveBeenCalledTimes(1);
      expect(states).toContain('listening');
      expect(engine.state).toBe('listening');
    });
  });

  // ── onTranscript() ──────────────────────────────────────────────────────────

  describe('onTranscript()', () => {
    it('delivers TranscriptEvent to registered listeners', () => {
      const engine = getVoiceEngine();
      const received: TranscriptEvent[] = [];
      engine.onTranscript(e => received.push(e));

      const event: TranscriptEvent = {
        text: 'Hello there',
        stability: 'final',
        timestamp_ms: 1000,
        latency_ms_from_audio_start: 200,
        confidence: 0.9,
        engine_metadata: { engine: 'apple_speech_transcriber' },
      };
      fireNativeEvent('onTranscriptEvent', event);

      expect(received).toHaveLength(1);
      expect(received[0].text).toBe('Hello there');
    });

    it('parses JSON string payloads from native side', () => {
      const engine = getVoiceEngine();
      const received: TranscriptEvent[] = [];
      engine.onTranscript(e => received.push(e));

      const event: TranscriptEvent = {
        text: 'parsed from string',
        stability: 'partial',
        timestamp_ms: 2000,
        latency_ms_from_audio_start: 50,
        confidence: null,
        engine_metadata: { engine: 'test' },
      };
      fireNativeEvent('onTranscriptEvent', JSON.stringify(event));

      expect(received).toHaveLength(1);
      expect(received[0].text).toBe('parsed from string');
    });

    it('returns an unsubscribe function that stops delivery', () => {
      const engine = getVoiceEngine();
      const received: TranscriptEvent[] = [];
      const unsubscribe = engine.onTranscript(e => received.push(e));

      const event: TranscriptEvent = {
        text: 'before unsub',
        stability: 'final',
        timestamp_ms: 1000,
        latency_ms_from_audio_start: 100,
        confidence: 0.8,
        engine_metadata: {},
      };
      fireNativeEvent('onTranscriptEvent', event);
      expect(received).toHaveLength(1);

      unsubscribe();
      fireNativeEvent('onTranscriptEvent', { ...event, text: 'after unsub' });
      expect(received).toHaveLength(1); // still 1 — listener was removed
    });
  });

  // ── onError() ───────────────────────────────────────────────────────────────

  describe('onError()', () => {
    it('delivers Error objects when native fires onVoiceError', () => {
      const engine = getVoiceEngine();
      const errors: Error[] = [];
      engine.onError(e => errors.push(e));

      fireNativeEvent('onVoiceError', 'Speech recognizer unavailable');

      expect(errors).toHaveLength(1);
      expect(errors[0]).toBeInstanceOf(Error);
      expect(errors[0].message).toBe('Speech recognizer unavailable');
    });
  });

  // ── mock mode (no RtsVoiceModule) ───────────────────────────────────────────
  //
  // Tested via MockVoiceEngine directly — dynamic imports with jest.resetModules()
  // are unsupported in the jest-expo preset without --experimental-vm-modules.
  // The isMocked branch in NativeVoiceModuleImpl is a thin guard; MockVoiceEngine
  // covers the same contract exhaustively.

});
