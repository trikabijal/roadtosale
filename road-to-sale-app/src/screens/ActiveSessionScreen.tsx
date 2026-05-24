/**
 * ActiveSessionScreen.tsx
 *
 * Core sales coaching screen — active while the rep works through the NADA
 * process with a customer. Drives a live checklist via SessionEngine, streams
 * voice transcripts from VoiceEngine, and provides manual override controls.
 *
 * Architecture notes:
 *  - SessionEngine owns all business state; this screen is purely reactive.
 *  - VoiceEngine emits TranscriptEvents; finals are forwarded as CueDetections
 *    with cue_id "workflow.transcript" (v1 no-op bridge — DC66).
 *  - All listeners are unsubscribed on unmount; voice engine is stopped cleanly.
 */

import React, {
  useCallback,
  useEffect,
  useLayoutEffect,
  useRef,
  useState,
} from 'react';
import {
  Alert,
  Animated,
  FlatList,
  Platform,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  TouchableOpacity,
  View,
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

import type { ActiveSessionScreenProps } from '../navigation/types';
import { getSessionEngine } from '../session/sessionEngineSingleton';
import { getVoiceEngine } from '../voice/NativeVoiceModule';
import { useTheme } from '../theme';
import { Typography } from '../theme';

import type {
  Session,
  StepState,
  QuestionState,
  DetectedCue,
} from '../session/types';
import type { VoiceEngineState } from '../voice/IVoiceEngine';
import type { TranscriptEvent, CueDetection } from '../voice/types';

// ─── Helpers ──────────────────────────────────────────────────────────────────

function formatDuration(totalSeconds: number): string {
  const h = Math.floor(totalSeconds / 3600);
  const m = Math.floor((totalSeconds % 3600) / 60);
  const s = totalSeconds % 60;
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${pad(h)}:${pad(m)}:${pad(s)}`;
}

/** v1 transcript → CueDetection bridge (DC66). */
function transcriptToCueDetection(event: TranscriptEvent): CueDetection {
  return {
    cue_id: 'workflow.transcript',
    matched_phrase: event.text,
    timestamp_ms: event.timestamp_ms,
    confidence: event.confidence,
    triggering_event: event,
  };
}

function questionCompletedCount(step: StepState): number {
  return step.questions.filter(
    (q) => q.status === 'complete' || q.status === 'overridden',
  ).length;
}

function stepBadgeColor(step: StepState, colors: ReturnType<typeof useTheme>['colors']): string {
  if (step.isComplete) return colors.stepComplete;
  const hasPartial = step.questions.some(
    (q) => q.status === 'partial' || q.status === 'complete' || q.status === 'overridden',
  );
  return hasPartial ? colors.stepPartial : colors.stepPending;
}

// ─── Header component (set via useLayoutEffect) ────────────────────────────────

interface HeaderTitleProps {
  customerName: string;
  vehicleName: string;
  elapsedSeconds: number;
  micState: VoiceEngineState;
  colors: ReturnType<typeof useTheme>['colors'];
}

function SessionHeaderTitle({
  customerName,
  vehicleName,
  elapsedSeconds,
  micState,
  colors,
}: HeaderTitleProps) {
  const micIcon = micState === 'listening' ? '🎙️' : micState === 'muted' ? '🔇' : '🎙️';
  const micColor =
    micState === 'listening'
      ? colors.micActive
      : micState === 'muted'
      ? colors.micMuted
      : colors.stepPending;

  return (
    <View style={headerStyles.container}>
      {/* Left: customer + vehicle */}
      <View style={headerStyles.left}>
        <Text style={[headerStyles.name, { color: colors.textPrimary }]} numberOfLines={1}>
          {customerName}
        </Text>
        <Text style={[headerStyles.vehicle, { color: colors.textSecondary }]} numberOfLines={1}>
          {vehicleName}
        </Text>
      </View>

      {/* Center: session timer */}
      <View style={headerStyles.center}>
        <Text style={[headerStyles.timer, { color: colors.textPrimary }]}>
          {formatDuration(elapsedSeconds)}
        </Text>
      </View>

      {/* Right: mic status */}
      <View style={headerStyles.right}>
        <Text style={[headerStyles.micIcon, { color: micColor }]}>{micIcon}</Text>
      </View>
    </View>
  );
}

const headerStyles = StyleSheet.create({
  container: {
    flexDirection: 'row',
    alignItems: 'center',
    flex: 1,
    paddingHorizontal: 4,
  },
  left: {
    flex: 1,
    alignItems: 'flex-start',
  },
  center: {
    flex: 1,
    alignItems: 'center',
  },
  right: {
    flex: 1,
    alignItems: 'flex-end',
  },
  name: {
    ...Typography.captionMedium,
  },
  vehicle: {
    ...Typography.caption,
  },
  timer: {
    fontFamily: Platform.OS === 'ios' ? 'Menlo' : 'monospace',
    fontSize: 16,
    fontWeight: '600',
  },
  micIcon: {
    fontSize: 20,
  },
});

// ─── QuestionRow ───────────────────────────────────────────────────────────────

interface QuestionRowProps {
  qs: QuestionState;
  sessionId: string;
  colors: ReturnType<typeof useTheme>['colors'];
}

function QuestionRow({ qs, sessionId, colors }: QuestionRowProps) {
  const { status, question, detectedCues } = qs;
  const engine = getSessionEngine();

  const statusLabel =
    status === 'complete'
      ? 'Complete'
      : status === 'overridden'
      ? 'Override'
      : status === 'partial'
      ? 'Partial'
      : 'Pending';

  const statusColor =
    status === 'complete'
      ? colors.stepComplete
      : status === 'overridden'
      ? colors.stepOverride
      : status === 'partial'
      ? colors.stepPartial
      : colors.stepPending;

  const isAlreadyDone = status === 'complete' || status === 'overridden';

  // First detected cue snippet for display
  const snippetCue: DetectedCue | undefined = detectedCues[0];

  function handleMarkComplete() {
    Alert.alert(
      'Mark Complete',
      `Confirm "${question.question}"?`,
      [
        { text: 'Cancel', style: 'cancel' },
        {
          text: 'Confirm',
          onPress: () => {
            engine.overrideQuestion(sessionId, question.id, 'manual override');
          },
        },
      ],
    );
  }

  return (
    <View
      style={[
        qRowStyles.container,
        { borderLeftColor: statusColor, backgroundColor: colors.surfaceElevated },
      ]}
    >
      {/* Question text */}
      <Text style={[qRowStyles.label, { color: colors.textPrimary }]} numberOfLines={3}>
        {question.question}
      </Text>

      {/* Heard snippet */}
      {snippetCue !== undefined && (
        <Text style={[qRowStyles.snippet, { color: colors.textMuted }]} numberOfLines={2}>
          heard: "{snippetCue.transcriptSnippet}"
        </Text>
      )}

      {/* Bottom row: status chip + mark-complete button */}
      <View style={qRowStyles.footer}>
        <View style={[qRowStyles.chip, { borderColor: statusColor }]}>
          <Text style={[qRowStyles.chipText, { color: statusColor }]}>{statusLabel}</Text>
        </View>

        <TouchableOpacity
          style={[
            qRowStyles.markBtn,
            {
              backgroundColor: colors.surface,
              borderColor: isAlreadyDone ? colors.stepComplete : colors.border,
              opacity: isAlreadyDone ? 0.6 : 1,
            },
          ]}
          onPress={handleMarkComplete}
          activeOpacity={0.75}
          disabled={isAlreadyDone}
          accessibilityRole="button"
          accessibilityLabel={`Mark ${question.question} complete`}
        >
          <Text
            style={[
              qRowStyles.markBtnText,
              { color: isAlreadyDone ? colors.stepComplete : colors.textSecondary },
            ]}
          >
            {isAlreadyDone ? '✓' : 'Mark Complete'}
          </Text>
        </TouchableOpacity>
      </View>
    </View>
  );
}

const qRowStyles = StyleSheet.create({
  container: {
    borderLeftWidth: 3,
    borderRadius: 8,
    padding: 12,
    marginBottom: 8,
    marginLeft: 4,
  },
  label: {
    ...Typography.body,
    marginBottom: 4,
  },
  snippet: {
    ...Typography.caption,
    fontStyle: 'italic',
    marginBottom: 6,
  },
  footer: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    marginTop: 4,
  },
  chip: {
    borderWidth: 1,
    borderRadius: 4,
    paddingVertical: 2,
    paddingHorizontal: 8,
  },
  chipText: {
    ...Typography.label,
  },
  markBtn: {
    borderWidth: 1,
    borderRadius: 6,
    paddingVertical: 6,
    paddingHorizontal: 12,
  },
  markBtnText: {
    ...Typography.captionMedium,
  },
});

// ─── StepCard ─────────────────────────────────────────────────────────────────

interface StepCardProps {
  step: StepState;
  sessionId: string;
  isExpanded: boolean;
  onToggle: () => void;
  colors: ReturnType<typeof useTheme>['colors'];
}

function StepCard({ step, sessionId, isExpanded, onToggle, colors }: StepCardProps) {
  const badgeColor = stepBadgeColor(step, colors);
  const completed = questionCompletedCount(step);
  const total = step.questions.length;

  const badgeSymbol = step.isComplete ? '✓' : step.questions.some(
    (q) => q.status === 'partial' || q.status === 'complete' || q.status === 'overridden',
  )
    ? '●'
    : '○';

  return (
    <View style={[stepStyles.card, { backgroundColor: colors.surface, borderColor: colors.border }]}>
      {/* Step header row */}
      <TouchableOpacity
        style={stepStyles.header}
        onPress={onToggle}
        activeOpacity={0.75}
        accessibilityRole="button"
        accessibilityLabel={`Step ${step.header.orderNo}: ${step.header.name}, ${completed} of ${total} complete`}
      >
        {/* Step name */}
        <Text style={[stepStyles.stepName, { color: colors.textPrimary }]} numberOfLines={1}>
          {step.header.orderNo}. {step.header.name}
        </Text>

        <View style={stepStyles.headerRight}>
          {/* Completion count */}
          <Text style={[stepStyles.countLabel, { color: colors.textSecondary }]}>
            {completed}/{total}
          </Text>

          {/* Completion badge */}
          <Text style={[stepStyles.badge, { color: badgeColor }]}>{badgeSymbol}</Text>

          {/* Chevron */}
          <Text style={[stepStyles.chevron, { color: colors.textMuted }]}>
            {isExpanded ? '▲' : '▼'}
          </Text>
        </View>
      </TouchableOpacity>

      {/* Questions (expanded) */}
      {isExpanded && (
        <View style={stepStyles.questions}>
          {step.questions.map((qs) => (
            <QuestionRow
              key={qs.question.id}
              qs={qs}
              sessionId={sessionId}
              colors={colors}
            />
          ))}
        </View>
      )}
    </View>
  );
}

const stepStyles = StyleSheet.create({
  card: {
    borderRadius: 12,
    borderWidth: StyleSheet.hairlineWidth,
    marginBottom: 12,
    overflow: 'hidden',
  },
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    padding: 14,
  },
  stepName: {
    ...Typography.bodyMedium,
    flex: 1,
    marginRight: 8,
  },
  headerRight: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
  },
  countLabel: {
    ...Typography.captionMedium,
  },
  badge: {
    fontSize: 16,
    fontWeight: '700',
  },
  chevron: {
    fontSize: 12,
    marginLeft: 2,
  },
  questions: {
    paddingHorizontal: 12,
    paddingBottom: 12,
  },
});

// ─── Feature Coverage Panel ────────────────────────────────────────────────────

interface FeatureCoveragePanelProps {
  allSteps: StepState[];
  colors: ReturnType<typeof useTheme>['colors'];
}

function FeatureCoveragePanel({ allSteps, colors }: FeatureCoveragePanelProps) {
  const featureCues: DetectedCue[] = [];
  for (const step of allSteps) {
    for (const qs of step.questions) {
      for (const cue of qs.detectedCues) {
        if (cue.cueSource === 'feature') {
          featureCues.push(cue);
        }
      }
    }
  }

  if (featureCues.length === 0) return null;

  return (
    <View style={fcStyles.container}>
      <Text style={[fcStyles.title, { color: colors.textSecondary }]}>Features Confirmed</Text>
      <ScrollView
        horizontal
        showsHorizontalScrollIndicator={false}
        contentContainerStyle={fcStyles.scrollContent}
      >
        {featureCues.map((cue, idx) => (
          <View
            key={`${cue.cueId}-${idx}`}
            style={[fcStyles.tag, { backgroundColor: colors.surfaceElevated, borderColor: colors.stepComplete }]}
          >
            <Text style={[fcStyles.tagText, { color: colors.stepComplete }]} numberOfLines={1}>
              {cue.transcriptSnippet.length > 30
                ? cue.transcriptSnippet.slice(0, 30) + '…'
                : cue.transcriptSnippet}
            </Text>
          </View>
        ))}
      </ScrollView>
    </View>
  );
}

const fcStyles = StyleSheet.create({
  container: {
    marginBottom: 16,
  },
  title: {
    ...Typography.label,
    marginBottom: 8,
    marginLeft: 4,
  },
  scrollContent: {
    paddingHorizontal: 4,
    gap: 8,
  },
  tag: {
    borderWidth: 1,
    borderRadius: 20,
    paddingVertical: 4,
    paddingHorizontal: 12,
  },
  tagText: {
    ...Typography.captionMedium,
  },
});

// ─── Mic Indicator ────────────────────────────────────────────────────────────

interface MicIndicatorProps {
  micState: VoiceEngineState;
  onPress: () => void;
  colors: ReturnType<typeof useTheme>['colors'];
}

function MicIndicator({ micState, onPress, colors }: MicIndicatorProps) {
  const pulseAnim = useRef(new Animated.Value(1)).current;
  const pulseRef = useRef<Animated.CompositeAnimation | null>(null);

  useEffect(() => {
    if (micState === 'listening') {
      pulseRef.current = Animated.loop(
        Animated.sequence([
          Animated.timing(pulseAnim, {
            toValue: 1.25,
            duration: 800,
            useNativeDriver: true,
          }),
          Animated.timing(pulseAnim, {
            toValue: 1.0,
            duration: 800,
            useNativeDriver: true,
          }),
        ]),
      );
      pulseRef.current.start();
    } else {
      if (pulseRef.current) {
        pulseRef.current.stop();
        pulseRef.current = null;
      }
      pulseAnim.setValue(1);
    }
    return () => {
      if (pulseRef.current) {
        pulseRef.current.stop();
        pulseRef.current = null;
      }
    };
  }, [micState, pulseAnim]);

  const bgColor =
    micState === 'listening'
      ? colors.micActive
      : micState === 'muted'
      ? colors.micMuted
      : colors.stepPending;

  const micLabel = micState === 'muted' ? '🔇' : '🎙️';

  return (
    <Animated.View
      style={[
        micStyles.wrapper,
        { transform: [{ scale: pulseAnim }] },
      ]}
    >
      <Pressable
        style={[micStyles.circle, { backgroundColor: bgColor }]}
        onPress={onPress}
        accessibilityRole="button"
        accessibilityLabel={micState === 'muted' ? 'Unmute microphone' : 'Mute microphone'}
      >
        <Text style={micStyles.icon}>{micLabel}</Text>
      </Pressable>
    </Animated.View>
  );
}

const micStyles = StyleSheet.create({
  wrapper: {
    width: 64,
    height: 64,
    borderRadius: 32,
  },
  circle: {
    width: 64,
    height: 64,
    borderRadius: 32,
    justifyContent: 'center',
    alignItems: 'center',
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 2 },
    shadowOpacity: 0.3,
    shadowRadius: 4,
    elevation: 6,
  },
  icon: {
    fontSize: 28,
  },
});

// ─── Main screen ───────────────────────────────────────────────────────────────

export default function ActiveSessionScreen({
  navigation,
  route,
}: ActiveSessionScreenProps) {
  const { sessionId } = route.params;
  const { colors } = useTheme();
  const insets = useSafeAreaInsets();

  const engine = getSessionEngine();
  const voice = getVoiceEngine();

  // ── State ────────────────────────────────────────────────────────────────────
  const [session, setSession] = useState<Session | null>(null);
  const [loading, setLoading] = useState(true);
  const [ending, setEnding] = useState(false);
  const [micState, setMicState] = useState<VoiceEngineState>(voice.state);
  const [elapsedSeconds, setElapsedSeconds] = useState(0);
  const [expandedSteps, setExpandedSteps] = useState<Set<number>>(new Set([0]));

  // ── Header — set once session is loaded ───────────────────────────────────────
  useLayoutEffect(() => {
    if (!session) return;

    const customerName =
      session.customer.firstName +
      (session.customer.lastName ? ` ${session.customer.lastName}` : '');
    const vehicleName = `${session.vehicle.year} ${session.vehicle.makeName} ${session.vehicle.trimName}`;

    navigation.setOptions({
      headerTitle: () => (
        <SessionHeaderTitle
          customerName={customerName}
          vehicleName={vehicleName}
          elapsedSeconds={elapsedSeconds}
          micState={micState}
          colors={colors}
        />
      ),
      headerTitleAlign: 'center' as const,
    });
  }, [session, elapsedSeconds, micState, colors, navigation]);

  // ── Mount: load session + start voice + subscribe ────────────────────────────
  useEffect(() => {
    let unsubSession: (() => void) | null = null;
    let unsubTranscript: (() => void) | null = null;
    let unsubState: (() => void) | null = null;
    let unsubError: (() => void) | null = null;
    let timerHandle: ReturnType<typeof setInterval> | null = null;
    let mounted = true;

    async function setup() {
      // 1. Load session
      const initialSession = engine.getSession(sessionId);
      if (!initialSession) {
        Alert.alert(
          'Session Not Found',
          'Could not load session data. Please go back and try again.',
          [{ text: 'Go Back', onPress: () => navigation.goBack() }],
        );
        setLoading(false);
        return;
      }

      if (mounted) {
        setSession(initialSession);
        setLoading(false);
      }

      // 2. Subscribe to session updates
      unsubSession = engine.onSessionUpdate((updated) => {
        if (updated.id === sessionId && mounted) {
          setSession({ ...updated });
        }
      });

      // 3. Start voice engine
      try {
        await voice.start({ language: 'en-US', customVocabulary: [] });
      } catch (err) {
        console.warn('[ActiveSessionScreen] Voice start failed:', err);
      }

      // 4. Subscribe to transcript events — forward finals to engine (DC66)
      unsubTranscript = voice.onTranscript((event: TranscriptEvent) => {
        if (event.stability === 'final') {
          const detection: CueDetection = transcriptToCueDetection(event);
          engine.processCueDetection(sessionId, detection);
        }
      });

      // 5. Subscribe to voice state changes
      unsubState = voice.onStateChange((state: VoiceEngineState) => {
        if (mounted) setMicState(state);
      });

      // 6. Subscribe to voice errors
      unsubError = voice.onError((err: Error) => {
        Alert.alert('Microphone Error', err.message);
      });

      // 7. Start session timer
      const startTime = Date.now();
      timerHandle = setInterval(() => {
        if (mounted) {
          setElapsedSeconds(Math.floor((Date.now() - startTime) / 1000));
        }
      }, 1000);
    }

    setup();

    return () => {
      mounted = false;
      if (unsubSession) unsubSession();
      if (unsubTranscript) unsubTranscript();
      if (unsubState) unsubState();
      if (unsubError) unsubError();
      if (timerHandle) clearInterval(timerHandle);
      voice.stop().catch((err) => {
        console.warn('[ActiveSessionScreen] Voice stop failed on cleanup:', err);
      });
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sessionId]);

  // ── Mic toggle ───────────────────────────────────────────────────────────────
  const handleMicToggle = useCallback(() => {
    if (micState === 'muted') {
      voice.unmute();
    } else if (micState === 'listening') {
      voice.mute();
    }
  }, [micState, voice]);

  // ── Step expand/collapse ─────────────────────────────────────────────────────
  const handleToggleStep = useCallback((index: number) => {
    setExpandedSteps((prev) => {
      const next = new Set(prev);
      if (next.has(index)) {
        next.delete(index);
      } else {
        next.add(index);
      }
      return next;
    });
  }, []);

  // ── End session ──────────────────────────────────────────────────────────────
  const handleEndSession = useCallback(() => {
    Alert.alert(
      'End Session?',
      'This will submit the session to SmartComply.',
      [
        { text: 'Cancel', style: 'cancel' },
        {
          text: 'End Session',
          style: 'destructive',
          onPress: async () => {
            setEnding(true);
            try {
              await engine.endSession(sessionId);
            } catch (err) {
              console.warn('[ActiveSessionScreen] endSession failed:', err);
            }
            navigation.replace('SessionSummary', { sessionId });
          },
        },
      ],
    );
  }, [sessionId, engine, navigation]);

  // ── Trade-In ─────────────────────────────────────────────────────────────────
  const handleTradeIn = useCallback(() => {
    navigation.navigate('TradeIn', { sessionId });
  }, [sessionId, navigation]);

  // ─── Render: loading / no session ──────────────────────────────────────────
  if (loading) {
    return (
      <View style={[styles.centered, { backgroundColor: colors.background }]}>
        <Text style={[Typography.body, { color: colors.textSecondary }]}>
          Loading session…
        </Text>
      </View>
    );
  }

  if (!session) {
    return (
      <View style={[styles.centered, { backgroundColor: colors.background }]}>
        <Text style={[Typography.h3, { color: colors.textPrimary, marginBottom: 8 }]}>
          Session unavailable
        </Text>
        <TouchableOpacity
          onPress={() => navigation.goBack()}
          accessibilityRole="button"
          accessibilityLabel="Go back"
        >
          <Text style={[Typography.buttonMedium, { color: colors.accent }]}>Go Back</Text>
        </TouchableOpacity>
      </View>
    );
  }

  const { checklist } = session;

  // ─── Render: active session ────────────────────────────────────────────────
  return (
    <View style={[styles.root, { backgroundColor: colors.background }]}>

      {/* ── Main scrollable content ─────────────────────────────────────── */}
      <FlatList
        data={checklist.steps}
        keyExtractor={(item) => String(item.header.id)}
        contentContainerStyle={[
          styles.listContent,
          // Reserve space for the fixed mic indicator + footer
          { paddingBottom: insets.bottom + 56 + 72 + 16 },
        ]}
        ListHeaderComponent={
          <>
            {/* Feature Coverage Panel */}
            <FeatureCoveragePanel allSteps={checklist.steps} colors={colors} />

            {/* Checklist progress summary */}
            <View style={styles.progressRow}>
              <Text style={[styles.progressText, { color: colors.textSecondary }]}>
                {checklist.completedCount} / {checklist.totalCount} questions complete
              </Text>
            </View>
          </>
        }
        renderItem={({ item, index }) => (
          <StepCard
            step={item}
            sessionId={sessionId}
            isExpanded={expandedSteps.has(index)}
            onToggle={() => handleToggleStep(index)}
            colors={colors}
          />
        )}
      />

      {/* ── Mic Indicator (floating, above footer) ──────────────────────── */}
      <View
        style={[
          styles.micContainer,
          { bottom: insets.bottom + 72 + 16 },
        ]}
        pointerEvents="box-none"
      >
        <MicIndicator
          micState={micState}
          onPress={handleMicToggle}
          colors={colors}
        />
      </View>

      {/* ── Footer ─────────────────────────────────────────────────────── */}
      <View
        style={[
          styles.footer,
          {
            backgroundColor: colors.surface,
            borderTopColor: colors.border,
            paddingBottom: insets.bottom > 0 ? insets.bottom : 12,
          },
        ]}
      >
        {/* Trade-In button */}
        <TouchableOpacity
          style={[
            styles.footerBtn,
            {
              backgroundColor: colors.surface,
              borderColor: colors.border,
            },
          ]}
          onPress={handleTradeIn}
          activeOpacity={0.75}
          disabled={ending}
          accessibilityRole="button"
          accessibilityLabel="Open Trade-In"
        >
          <Text style={[Typography.buttonMedium, { color: colors.textPrimary }]}>
            Trade-In
          </Text>
        </TouchableOpacity>

        {/* End Session button */}
        <TouchableOpacity
          style={[
            styles.footerBtn,
            {
              backgroundColor: colors.surface,
              borderColor: colors.error,
            },
          ]}
          onPress={handleEndSession}
          activeOpacity={0.75}
          disabled={ending}
          accessibilityRole="button"
          accessibilityLabel="End Session"
        >
          <Text
            style={[
              Typography.buttonMedium,
              { color: ending ? colors.textMuted : colors.error },
            ]}
          >
            {ending ? 'Ending…' : 'End Session'}
          </Text>
        </TouchableOpacity>
      </View>
    </View>
  );
}

// ─── Screen styles ─────────────────────────────────────────────────────────────

const styles = StyleSheet.create({
  root: {
    flex: 1,
  },
  centered: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
    padding: 32,
  },
  listContent: {
    padding: 16,
  },
  progressRow: {
    marginBottom: 12,
    alignItems: 'flex-end',
  },
  progressText: {
    ...Typography.captionMedium,
  },

  // Mic indicator
  micContainer: {
    position: 'absolute',
    alignSelf: 'center',
    left: 0,
    right: 0,
    alignItems: 'center',
    zIndex: 10,
    pointerEvents: 'box-none',
  },

  // Footer
  footer: {
    position: 'absolute',
    bottom: 0,
    left: 0,
    right: 0,
    flexDirection: 'row',
    borderTopWidth: StyleSheet.hairlineWidth,
    paddingHorizontal: 16,
    paddingTop: 12,
    gap: 12,
  },
  footerBtn: {
    flex: 1,
    height: 48,
    borderRadius: 10,
    borderWidth: 1,
    justifyContent: 'center',
    alignItems: 'center',
  },
});
