import { describe, expect, it } from 'vitest';
import { CueMatcher } from '../src/matcher/cue-matcher.js';
import type { CueAtom, TranscriptEvent } from '../src/types/index.js';

function event(text: string, timestamp_ms = 0): TranscriptEvent {
  return {
    text,
    stability: 'final',
    timestamp_ms,
    latency_ms_from_audio_start: timestamp_ms + 50,
    confidence: 0.9,
    engine_metadata: {},
  };
}

const HONDA_SENSING: CueAtom = {
  id: 'honda.feature.honda_sensing_360plus',
  display_name: 'Honda Sensing 360+',
  source: 'feature',
  cue_phrases: ['Honda Sensing'],
  synonyms: ['safety suite', 'driver assist'],
  metadata: {},
};

const CARPLAY: CueAtom = {
  id: 'honda.feature.wireless_apple_carplay',
  display_name: 'Wireless Apple CarPlay',
  source: 'feature',
  cue_phrases: ['wireless apple carplay'],
  synonyms: ['CarPlay', 'apple carplay'],
  metadata: {},
};

const HOSPITALITY: CueAtom = {
  id: 'workflow.hospitality_offer',
  display_name: 'Hospitality Offer',
  source: 'workflow',
  cue_phrases: ['can I offer you', 'would you like a drink'],
  synonyms: ['coffee', 'water'],
  metadata: {},
};

describe('CueMatcher', () => {
  it('matches a direct phrase', () => {
    const matcher = new CueMatcher([HONDA_SENSING]);
    const detections = Array.from(matcher.match([event("Let's talk about Honda Sensing today")]));
    expect(detections).toHaveLength(1);
    expect(detections[0].cue_id).toBe('honda.feature.honda_sensing_360plus');
    expect(detections[0].matched_phrase).toBe('Honda Sensing');
  });

  it('matches a synonym', () => {
    const matcher = new CueMatcher([CARPLAY]);
    const detections = Array.from(matcher.match([event('Pair your phone with CarPlay')]));
    expect(detections).toHaveLength(1);
    expect(detections[0].matched_phrase).toBe('CarPlay');
  });

  it('is case-insensitive', () => {
    const matcher = new CueMatcher([CARPLAY]);
    const detections = Array.from(matcher.match([event('HONDA AND apple CARPLAY rules')]));
    expect(detections).toHaveLength(1);
    expect(detections[0].cue_id).toBe('honda.feature.wireless_apple_carplay');
  });

  it('emits nothing when no phrase or synonym matches', () => {
    const matcher = new CueMatcher([HONDA_SENSING, CARPLAY]);
    const detections = Array.from(matcher.match([event('the weather is nice')]));
    expect(detections).toEqual([]);
  });

  it('emits one detection per atom for multi-cue events', () => {
    const matcher = new CueMatcher([HONDA_SENSING, CARPLAY, HOSPITALITY]);
    const detections = Array.from(
      matcher.match([event('Honda Sensing and CarPlay — can I offer you a coffee?')]),
    );
    const ids = new Set(detections.map((d) => d.cue_id));
    expect(ids).toEqual(
      new Set([
        'honda.feature.honda_sensing_360plus',
        'honda.feature.wireless_apple_carplay',
        'workflow.hospitality_offer',
      ]),
    );
    expect(detections).toHaveLength(3);
  });

  it('normalizes punctuation', () => {
    const matcher = new CueMatcher([CARPLAY]);
    const detections = Array.from(matcher.match([event('Use, CarPlay!')]));
    expect(detections).toHaveLength(1);
  });

  it('attaches the triggering event to each detection', () => {
    const matcher = new CueMatcher([HONDA_SENSING]);
    const ev = event('Honda Sensing', 4242);
    const detections = Array.from(matcher.match([ev]));
    expect(detections[0].triggering_event).toBe(ev);
    expect(detections[0].timestamp_ms).toBe(4242);
  });
});
