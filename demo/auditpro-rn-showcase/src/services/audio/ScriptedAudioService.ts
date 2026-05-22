import {
  AudioService,
  AudioSession,
  AudioStartContext,
  DetectionEvent,
  TranscriptEvent,
  Unsubscribe,
} from './AudioService';
import { audioScript } from './audioScript';

type Subscriber<T> = (event: T) => void;

const SCREEN_TO_SCRIPT_KEY: Record<string, string> = {
  greet: 'greet',
  discovery: 'discovery',
  feature_match: 'feature_match',
  front_line_ready: 'front_line_ready',
  walkaround: 'walkaround',
  test_drive: 'test_drive',
  trade_in: 'trade_in',
  manager_to: 'manager_to',
  fi_handoff: 'fi_handoff',
};

export type ScriptedAudioOptions = {
  context?: { customer?: string; dealership?: string; rep?: string; vehicle?: string };
};

function interpolate(text: string, ctx: NonNullable<ScriptedAudioOptions['context']>) {
  const customer = ctx.customer ?? '';
  const dealership = ctx.dealership ?? '';
  const customerFirst = customer.split(' ')[0] ?? customer;
  const vehicleShort = (ctx.vehicle ?? '').split(' ').slice(-2).join(' ');
  return text
    .replace(/\{\{customer\}\}/g, customer)
    .replace(/\{\{customerFirst\}\}/g, customerFirst)
    .replace(/\{\{dealership\}\}/g, dealership)
    .replace(/\{\{rep\}\}/g, ctx.rep ?? '')
    .replace(/\{\{vehicle\}\}/g, ctx.vehicle ?? '')
    .replace(/\{\{vehicleShort\}\}/g, vehicleShort);
}

class ScriptedAudioSession implements AudioSession {
  private transcriptSubs = new Set<Subscriber<TranscriptEvent>>();
  private detectionSubs = new Set<Subscriber<DetectionEvent>>();
  private timers: ReturnType<typeof setTimeout>[] = [];
  private running = true;

  constructor(
    context: AudioStartContext,
    ctx: NonNullable<ScriptedAudioOptions['context']>
  ) {
    const scriptKey = SCREEN_TO_SCRIPT_KEY[context.screenId];
    const script = scriptKey ? audioScript[scriptKey] : undefined;
    if (!script) {
      // No script for this screen — emit a single "Listening..." line on the
      // next tick so subscribers attached after start() resolves still see it.
      const t = setTimeout(() => {
        if (this.running) this.emitTranscript({ text: 'Listening...' });
      }, 0);
      this.timers.push(t);
      return;
    }

    script.lines.forEach((line) => {
      const timer = setTimeout(() => {
        if (!this.running) return;
        if (line.text) {
          this.emitTranscript({
            text: interpolate(line.text, ctx),
            trigger: line.trigger,
          });
        }
        if (line.useCase) {
          this.emitDetection({
            stepId: '2.1',
            type: 'usecase-detected',
            payload: { useCase: line.useCase },
          });
        }
        if (line.note) {
          this.emitDetection({
            stepId: '6.2',
            type: 'note-extracted',
            payload: { note: line.note },
          });
        }
        if (line.feature) {
          this.emitDetection({
            stepId: '4.2',
            type: line.feature.result === 'pass' ? 'feature-pass' : 'feature-fail',
            payload: { featureId: line.feature.id },
          });
        }
        if (line.detection) {
          this.emitDetection(line.detection);
        }
      }, line.t);
      this.timers.push(timer);
    });
  }

  onTranscript(handler: (event: TranscriptEvent) => void): Unsubscribe {
    this.transcriptSubs.add(handler);
    return () => this.transcriptSubs.delete(handler);
  }

  onDetection(handler: (event: DetectionEvent) => void): Unsubscribe {
    this.detectionSubs.add(handler);
    return () => this.detectionSubs.delete(handler);
  }

  async stop(): Promise<void> {
    this.running = false;
    this.timers.forEach((t) => clearTimeout(t));
    this.timers = [];
    this.transcriptSubs.clear();
    this.detectionSubs.clear();
  }

  private emitTranscript(event: TranscriptEvent) {
    this.transcriptSubs.forEach((s) => s(event));
  }

  private emitDetection(event: DetectionEvent) {
    this.detectionSubs.forEach((s) => s(event));
  }
}

export class ScriptedAudioService implements AudioService {
  private opts: ScriptedAudioOptions;

  constructor(opts: ScriptedAudioOptions = {}) {
    this.opts = opts;
  }

  setContext(context: ScriptedAudioOptions['context']) {
    this.opts = {
      ...this.opts,
      context: { ...(this.opts.context ?? {}), ...(context ?? {}) },
    };
  }

  async start(context: AudioStartContext): Promise<AudioSession> {
    return new ScriptedAudioSession(context, this.opts.context ?? {});
  }
}
