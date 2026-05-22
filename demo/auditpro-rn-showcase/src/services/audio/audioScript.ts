import { DetectionEvent } from './AudioService';

export type ScriptLine = {
  t: number;
  text?: string;
  trigger?: string;
  detection?: DetectionEvent;
  // payload helpers parsed by ScriptedAudioService into detection events
  feature?: { id: string; result: 'pass' | 'fail' };
  useCase?: string;
  note?: string;
};

export type ScreenScript = {
  screenId: string;
  delay: number;
  lines: ScriptLine[];
};

// Showcase-mode dialogue and timings. The ScriptedAudioService replays this
// exact transcript with detection events so screen flows can be exercised
// without live audio. Swap this slot in composition.ts for the real
// (Deepgram/Whisper) provider once it ships — the screen contracts won't
// change.
export const audioScript: Record<string, ScreenScript> = {
  greet: {
    screenId: 'greet',
    delay: 2500,
    lines: [
      { t: 0, text: 'Listening...' },
      { t: 1200, text: '"Hi {{customer}}, welcome to {{dealership}}..."' },
      {
        t: 2400,
        text: '"...can I grab you a {{trig:coffee or water}} before we head out?"',
        trigger: 'coffee or water',
      },
      {
        t: 3400,
        detection: { stepId: '1.2', type: 'auto-confirm' },
      },
    ],
  },
  discovery: {
    screenId: 'discovery',
    delay: 2500,
    lines: [
      { t: 0, text: 'Listening for use cases...' },
      {
        t: 1400,
        text: '"...so mainly for the kids, {{trig:school runs}}..."',
        trigger: 'school runs',
        useCase: 'fam',
      },
      {
        t: 2800,
        text: '"...and I do a lot of {{trig:highway commuting}}..."',
        trigger: 'highway commuting',
        useCase: 'hw',
      },
      {
        t: 3800,
        detection: { stepId: '2.1', type: 'auto-confirm' },
      },
    ],
  },
  feature_match: {
    screenId: 'feature_match',
    delay: 2500,
    lines: [
      { t: 0, text: 'Listening...' },
      { t: 1200, text: '"...because you mentioned the kids..."' },
      {
        t: 2400,
        text: "\"...{{trig:that's why I picked this one}} — the rear seat...\"",
        trigger: "that's why I picked this one",
      },
      {
        t: 3400,
        text: '"...{{trig:since you need}} highway safety, Lane Assist is standard..."',
        trigger: 'since you need',
      },
      {
        t: 4200,
        detection: { stepId: '3.1', type: 'auto-confirm' },
      },
    ],
  },
  front_line_ready: {
    screenId: 'front_line_ready',
    delay: 2500,
    lines: [
      { t: 0, text: 'Timing key handover...' },
      {
        t: 1400,
        text: '"...let me just {{trig:grab the keys}}..." [47s gap measured]',
        trigger: 'grab the keys',
      },
      {
        t: 2800,
        text: "\"...here we go, she's {{trig:fueled up}} and staged out front...\"",
        trigger: 'fueled up',
      },
      {
        t: 3800,
        detection: { stepId: '3.2', type: 'auto-confirm', payload: { gapSeconds: 47 } },
      },
    ],
  },
  walkaround: {
    screenId: 'walkaround',
    delay: 2500,
    lines: [
      { t: 0, text: 'Listening for walkaround positions and features...' },
      {
        t: 1200,
        text: '"...starting here at the {{trig:sticker}}..."',
        trigger: 'sticker',
        feature: { id: 'cam', result: 'pass' },
      },
      {
        t: 2200,
        text: '"...now the {{trig:interior}}, look at the screen..."',
        trigger: 'interior',
        feature: { id: 'lane', result: 'pass' },
      },
      {
        t: 3200,
        text: "\"...and CarPlay, I'll connect your {{trig:phone}} right now...\"",
        trigger: 'phone',
        feature: { id: 'play', result: 'pass' },
      },
      { t: 4200, detection: { stepId: '4.1', type: 'auto-confirm' } },
      { t: 4500, feature: { id: 'heat', result: 'fail' } },
    ],
  },
  test_drive: {
    screenId: 'test_drive',
    delay: 2500,
    lines: [
      { t: 0, text: 'Listening...' },
      {
        t: 1400,
        text: "\"...notice how the car just nudged us back — that's {{trig:Lane Assist active right now}}...\"",
        trigger: 'Lane Assist active right now',
      },
      {
        t: 2800,
        text: '"...you can see the {{trig:blind spot warning right now}} on your mirror..."',
        trigger: 'blind spot warning right now',
      },
      {
        t: 3800,
        detection: { stepId: '5.2', type: 'auto-confirm' },
      },
    ],
  },
  trade_in: {
    screenId: 'trade_in',
    delay: 2500,
    lines: [
      { t: 0, text: 'Listening for condition mentions...' },
      {
        t: 1400,
        text: "\"...there's a {{trig:small dent}} on the rear quarter...\"",
        trigger: 'small dent',
        note: 'Small dent — rear quarter panel. ',
      },
      {
        t: 2800,
        text: '"...and the {{trig:front tyres}} are below 4mm threshold..."',
        trigger: 'front tyres',
        note: 'Small dent — rear quarter panel. Front tyres below 4mm — flagged for reconditioning.',
      },
      { t: 3800, detection: { stepId: '6.2', type: 'note-extracted' } },
    ],
  },
  manager_to: {
    screenId: 'manager_to',
    delay: 800,
    lines: [
      { t: 0, text: 'Listening for manager voice...' },
      {
        t: 1400,
        text: "\"...{{customerFirst}}, I'd like you to meet {{trig:James}}...\"",
        trigger: 'James',
      },
      {
        t: 2600,
        text: '"...{{trig:James}} — this is {{trig:{{customerFirst}}}}. [New voice detected]"',
        trigger: 'new voice',
      },
      {
        t: 3600,
        detection: { stepId: '8.1', type: 'voice-detected' },
      },
    ],
  },
  fi_handoff: {
    screenId: 'fi_handoff',
    delay: 2500,
    lines: [
      { t: 0, text: 'Listening...' },
      {
        t: 1200,
        text: '"...{{trig:{{customerFirst}}}}, let me introduce you to..."',
        trigger: 'customer',
      },
      {
        t: 2400,
        text: '"...this is {{trig:David Chen}}, our Finance Manager..."',
        trigger: 'David Chen',
      },
      {
        t: 3400,
        text: '"...{{trig:David}} — {{trig:{{customerFirst}}}} is taking home the {{vehicleShort}} today..."',
        trigger: 'three-way intro',
      },
      {
        t: 4300,
        detection: { stepId: '9.1', type: 'auto-confirm' },
      },
    ],
  },
};
