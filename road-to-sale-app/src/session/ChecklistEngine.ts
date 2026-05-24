import type {
  IChecklistEngine,
  ChecklistState,
  StepState,
  QuestionState,
  QuestionStatus,
  DetectedCue,
  CuePackEntry,
} from './types';
import type { ChecksheetDTO } from '../api/types';
import type { CueDetection } from '../voice/types';

export class ChecklistEngine implements IChecklistEngine {
  private state: ChecklistState = { steps: [], completedCount: 0, totalCount: 0, lastUpdatedMs: 0 };
  /** cue_id → list of templateQuestionIds it answers */
  private cueToQuestions: Map<string, number[]> = new Map();
  /** templateQuestionId → Set of required cue_ids */
  private questionRequiredCues: Map<number, Set<string>> = new Map();
  private listeners: Array<(state: ChecklistState) => void> = [];

  // ─── Public API ───────────────────────────────────────────────────────────────

  init(checksheet: ChecksheetDTO, cuePackEntries: CuePackEntry[]): void {
    // Build lookup maps from the cue pack
    this.cueToQuestions = new Map();
    this.questionRequiredCues = new Map();

    for (const entry of cuePackEntries) {
      // cue → questions
      const existing = this.cueToQuestions.get(entry.cueId) ?? [];
      if (!existing.includes(entry.templateQuestionId)) {
        this.cueToQuestions.set(entry.cueId, [...existing, entry.templateQuestionId]);
      }

      // question → required cues
      if (entry.required) {
        const requiredSet = this.questionRequiredCues.get(entry.templateQuestionId) ?? new Set<string>();
        requiredSet.add(entry.cueId);
        this.questionRequiredCues.set(entry.templateQuestionId, requiredSet);
      }
    }

    // Build initial ChecklistState from the checksheet — all questions pending
    let totalCount = 0;
    const steps: StepState[] = checksheet.headers.map((header) => {
      const questions: QuestionState[] = header.questions.map((q) => {
        totalCount++;
        return {
          question: q,
          status: 'pending' as QuestionStatus,
          detectedCues: [],
          voiceAutoCompleted: false,
        };
      });
      return {
        header,
        questions,
        isComplete: false,
      };
    });

    this.state = {
      steps,
      completedCount: 0,
      totalCount,
      lastUpdatedMs: Date.now(),
    };
  }

  processCueDetection(detection: CueDetection): ChecklistState {
    const questionIds = this.cueToQuestions.get(detection.cue_id);
    if (!questionIds || questionIds.length === 0) {
      // Cue not bound to any question — no-op
      return this.state;
    }

    let newState = this.state;

    for (const qId of questionIds) {
      newState = this.applyDetectionToQuestion(newState, qId, detection);
    }

    newState = this.recomputeChecklist(newState);
    this.state = newState;
    this.emit();
    return this.state;
  }

  overrideQuestion(questionId: number, note?: string): ChecklistState {
    let newState = this.state;

    const steps = newState.steps.map((step) => {
      const questions = step.questions.map((qs) => {
        if (qs.question.id !== questionId) return qs;
        return {
          ...qs,
          status: 'overridden' as QuestionStatus,
          overrideNote: note,
        };
      });
      const updatedStep: StepState = { ...step, questions };
      return this.recomputeStep(updatedStep);
    });

    newState = { ...newState, steps };
    newState = this.recomputeChecklist(newState);
    this.state = newState;
    this.emit();
    return this.state;
  }

  getState(): ChecklistState {
    return this.state;
  }

  onUpdate(listener: (state: ChecklistState) => void): () => void {
    this.listeners.push(listener);
    return () => {
      this.listeners = this.listeners.filter((l) => l !== listener);
    };
  }

  // ─── Private helpers ──────────────────────────────────────────────────────────

  private applyDetectionToQuestion(
    state: ChecklistState,
    questionId: number,
    detection: CueDetection,
  ): ChecklistState {
    const cueSource = detection.cue_id.startsWith('workflow.') ? 'workflow' : 'feature';

    const detectedCue: DetectedCue = {
      cueId: detection.cue_id,
      cueSource,
      transcriptSnippet: detection.matched_phrase,
      confidence: detection.confidence,
      detectedAtMs: detection.timestamp_ms,
    };

    const steps = state.steps.map((step) => {
      const questions = step.questions.map((qs) => {
        if (qs.question.id !== questionId) return qs;

        // Deduplicate by cue_id — don't add the same cue twice
        const alreadyDetected = qs.detectedCues.some((d) => d.cueId === detection.cue_id);
        const newDetectedCues = alreadyDetected
          ? qs.detectedCues
          : [...qs.detectedCues, detectedCue];

        const newStatus = this.computeQuestionStatus(questionId, newDetectedCues, qs.status);
        const voiceAutoCompleted = newStatus === 'complete' ? true : qs.voiceAutoCompleted;

        return {
          ...qs,
          detectedCues: newDetectedCues,
          status: newStatus,
          voiceAutoCompleted,
        };
      });

      const updatedStep: StepState = { ...step, questions };
      return this.recomputeStep(updatedStep);
    });

    return { ...state, steps };
  }

  /**
   * Determine question status based on detected cues.
   * A question is 'complete' when all required cues (per the pack) are present.
   * It is 'partial' when at least one cue (required or not) has fired.
   * It stays 'pending' if no cues have fired yet.
   * An 'overridden' question is never downgraded.
   */
  private computeQuestionStatus(
    questionId: number,
    detectedCues: DetectedCue[],
    currentStatus: QuestionStatus,
  ): QuestionStatus {
    // Never downgrade an override
    if (currentStatus === 'overridden') return 'overridden';

    const detectedIds = new Set(detectedCues.map((d) => d.cueId));
    const requiredCues = this.questionRequiredCues.get(questionId) ?? new Set<string>();

    if (requiredCues.size === 0) {
      // No required cues configured — any detection counts as complete
      return detectedCues.length > 0 ? 'complete' : 'pending';
    }

    const allRequiredMet = [...requiredCues].every((cueId) => detectedIds.has(cueId));

    if (allRequiredMet) return 'complete';
    if (detectedCues.length > 0) return 'partial';
    return 'pending';
  }

  /** A step is complete when all mandatory questions within it are complete or overridden. */
  private recomputeStep(step: StepState): StepState {
    const mandatoryQuestions = step.questions.filter((qs) => qs.question.isMandatory);
    const isComplete =
      mandatoryQuestions.length > 0 &&
      mandatoryQuestions.every(
        (qs) => qs.status === 'complete' || qs.status === 'overridden',
      );
    return { ...step, isComplete };
  }

  /** Recompute completedCount and lastUpdatedMs across the entire checklist. */
  private recomputeChecklist(state: ChecklistState): ChecklistState {
    let completedCount = 0;
    for (const step of state.steps) {
      for (const qs of step.questions) {
        if (qs.status === 'complete' || qs.status === 'overridden') {
          completedCount++;
        }
      }
    }
    return { ...state, completedCount, lastUpdatedMs: Date.now() };
  }

  private emit(): void {
    for (const listener of this.listeners) {
      listener(this.state);
    }
  }
}
