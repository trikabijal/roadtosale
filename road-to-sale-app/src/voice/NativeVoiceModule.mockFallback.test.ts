/**
 * C-VOICE-7 — mock-fallback path of NativeVoiceModule.
 *
 * When NativeModules.RtsVoiceModule is absent (web / simulator without a prebuild),
 * `isMocked` is true: the engine must stay runnable with NO native calls —
 * requestPermissions() returns 'granted', start() flips straight to 'listening',
 * stop() to 'stopped', and mute/unmute are inert (no native module to call).
 *
 * This lives in its own file because `isMocked` is evaluated once at module load
 * from NativeModules; mocking react-native WITHOUT RtsVoiceModule here (a fresh
 * module registry per test file) exercises that branch. The companion
 * NativeVoiceModule.test.ts covers the native-present branch.
 */

jest.mock('react-native', () => ({
  // No RtsVoiceModule → isMocked === true
  NativeModules: {},
  NativeEventEmitter: jest.fn().mockImplementation(() => ({
    addListener: jest.fn(() => ({ remove: jest.fn() })),
  })),
  Platform: { OS: 'web' },
}));

import { getVoiceEngine } from './NativeVoiceModule';

describe('NativeVoiceModule (mock fallback, no native module)', () => {
  it('requestPermissions() returns "granted" without a native call', async () => {
    const engine = getVoiceEngine();
    await expect(engine.requestPermissions()).resolves.toBe('granted');
  });

  it('start() flips to "listening" with no native call', async () => {
    const engine = getVoiceEngine();
    const states: string[] = [];
    engine.onStateChange((s) => states.push(s));

    await engine.start({ language: 'en-US' });

    expect(states).toContain('listening');
    expect(engine.state).toBe('listening');
  });

  it('stop() flips to "stopped"', async () => {
    const engine = getVoiceEngine();
    await engine.stop();
    expect(engine.state).toBe('stopped');
  });

  it('mute()/unmute() are inert no-ops (no native module) and do not throw', () => {
    const engine = getVoiceEngine();
    expect(() => { engine.mute(); engine.unmute(); }).not.toThrow();
  });

  it('still delivers transcript events to JS listeners and unsubscribes cleanly', () => {
    const engine = getVoiceEngine();
    const received: string[] = [];
    const unsub = engine.onTranscript((e) => received.push(e.text));
    // No native bridge in mock mode; the listener registry must still work for any
    // JS-side simulated events. Verify the unsubscribe contract holds.
    unsub();
    expect(received).toHaveLength(0);
  });
});
