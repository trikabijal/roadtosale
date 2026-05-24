/**
 * SessionSummaryScreen
 *
 * Displays a full read-back of a completed session:
 *   - Hero card (customer, vehicle, completion stats + progress bar)
 *   - NADA Checklist breakdown (expandable steps → questions)
 *   - Trade-In summary (photo grid + typed note)
 *   - Pending writes banner
 *   - Footer: Sync to SmartComply + Share Report (disabled / hidden in readOnly mode)
 */

import React, { useCallback, useEffect, useLayoutEffect, useState } from 'react';
import {
  ActivityIndicator,
  Alert,
  Image,
  ScrollView,
  StyleSheet,
  Text,
  TouchableOpacity,
  View,
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { File, Paths } from 'expo-file-system';
import * as Sharing from 'expo-sharing';

import type { SessionSummaryScreenProps } from '../navigation/types';
import { useTheme } from '../theme';
import { Typography } from '../theme';
import { getSessionRepository } from '../db/repositorySingleton';
import { getSmartComplyClient } from '../api/clientSingleton';
import type { Session, QuestionState, StepState } from '../session/types';
import type { PendingWrite } from '../db/ISessionRepository';

// ─── Report generator ─────────────────────────────────────────────────────────

function generateReport(session: Session): string {
  const lines: string[] = [];
  lines.push('=== Road to Sale — Session Report ===');
  lines.push(
    `Customer: ${session.customer.firstName} ${session.customer.lastName ?? ''}`.trim(),
  );
  lines.push(
    `Vehicle: ${session.vehicle.year} ${session.vehicle.makeName} ${session.vehicle.modelName} ${session.vehicle.trimName}`,
  );
  lines.push(`Date: ${session.startedAt.toLocaleDateString()}`);
  lines.push(
    `Completed: ${session.checklist.completedCount} / ${session.checklist.totalCount} questions`,
  );
  lines.push('');
  lines.push('--- NADA Checklist ---');
  for (const step of session.checklist.steps) {
    const done = step.questions.filter(
      (q) => q.status === 'complete' || q.status === 'overridden',
    ).length;
    lines.push(
      `\n[${step.isComplete ? '✓' : ' '}] Step ${step.header.orderNo}: ${step.header.name} (${done}/${step.questions.length})`,
    );
    for (const q of step.questions) {
      const icon =
        q.status === 'complete' ? '✓' : q.status === 'overridden' ? 'M' : '○';
      lines.push(`  ${icon} ${q.question.question}`);
    }
  }
  return lines.join('\n');
}

// ─── Main Screen ──────────────────────────────────────────────────────────────

export default function SessionSummaryScreen({
  navigation,
  route,
}: SessionSummaryScreenProps) {
  const { colors } = useTheme();
  const insets = useSafeAreaInsets();
  const { sessionId, readOnly = false } = route.params;

  const [session, setSession] = useState<Session | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);

  const [pendingWrites, setPendingWrites] = useState<PendingWrite[]>([]);
  const [syncing, setSyncing] = useState(false);
  const [sharing, setSharing] = useState(false);

  // Tracks which step indices are expanded in the checklist
  const [expandedSteps, setExpandedSteps] = useState<Set<number>>(new Set());

  // ── Navigation header ─────────────────────────────────────────────────────
  useLayoutEffect(() => {
    navigation.setOptions({
      title: readOnly ? 'Session Summary (Read Only)' : 'Session Summary',
    });
  }, [navigation, readOnly]);

  // ── Load session ──────────────────────────────────────────────────────────
  const loadSession = useCallback(async () => {
    setLoading(true);
    setLoadError(null);
    try {
      const repo = getSessionRepository();
      const loaded = await repo.getSession(sessionId);
      if (!loaded) {
        setLoadError('Session not found.');
        return;
      }
      setSession(loaded);

      if (!readOnly) {
        const writes = await repo.getPendingWrites();
        // Filter only pending writes for this session
        setPendingWrites(writes.filter((w) => w.sessionId === sessionId));
      }
    } catch (err: unknown) {
      setLoadError(err instanceof Error ? err.message : 'Failed to load session.');
    } finally {
      setLoading(false);
    }
  }, [sessionId, readOnly]);

  useEffect(() => {
    loadSession();
  }, [loadSession]);

  // ── Sync to SmartComply ───────────────────────────────────────────────────
  async function handleSync() {
    if (syncing || pendingWrites.length === 0) return;
    setSyncing(true);
    const repo = getSessionRepository();
    const client = getSmartComplyClient();
    let failCount = 0;

    for (const write of pendingWrites) {
      try {
        const answers = JSON.parse(write.payload) as Parameters<
          typeof client.submitAnswers
        >[0];
        await client.submitAnswers(answers);
        await repo.markWriteSucceeded(write.id);
      } catch {
        await repo.incrementWriteRetry(write.id);
        failCount++;
      }
    }

    // Refresh pending writes list
    try {
      const updated = await repo.getPendingWrites();
      setPendingWrites(updated.filter((w) => w.sessionId === sessionId));
    } catch {
      // Non-fatal — stale count until next load
    }

    setSyncing(false);

    if (failCount === 0) {
      Alert.alert('Sync Complete', 'All answers synced to SmartComply successfully.');
    } else {
      Alert.alert(
        'Partial Sync',
        `${failCount} answer${failCount !== 1 ? 's' : ''} could not be synced. They will retry automatically.`,
      );
    }
  }

  // ── Share report ──────────────────────────────────────────────────────────
  async function handleShare() {
    if (!session || sharing) return;
    setSharing(true);
    try {
      const text = generateReport(session);
      const available = await Sharing.isAvailableAsync();
      if (available) {
        // expo-file-system v56: use new File + Paths API (cacheDirectory is not exported directly)
        const reportFile = new File(Paths.cache, 'rts-report.txt');
        reportFile.write(text);
        await Sharing.shareAsync(reportFile.uri, { mimeType: 'text/plain' });
      } else {
        Alert.alert('Share', text);
      }
    } catch (err: unknown) {
      Alert.alert('Error', err instanceof Error ? err.message : 'Could not generate report.');
    } finally {
      setSharing(false);
    }
  }

  // ── Step expand/collapse ──────────────────────────────────────────────────
  function toggleStep(index: number) {
    setExpandedSteps((prev) => {
      const next = new Set(prev);
      if (next.has(index)) {
        next.delete(index);
      } else {
        next.add(index);
      }
      return next;
    });
  }

  // ─────────────────────────────────────────────────────────────────────────
  // Render states
  // ─────────────────────────────────────────────────────────────────────────

  if (loading) {
    return (
      <View style={[styles.centered, { backgroundColor: colors.background }]}>
        <ActivityIndicator size="large" color={colors.accent} />
      </View>
    );
  }

  if (loadError || !session) {
    return (
      <View style={[styles.centered, { backgroundColor: colors.background }]}>
        <Text style={[Typography.h3, { color: colors.textPrimary, textAlign: 'center' }]}>
          Could not load session
        </Text>
        <Text
          style={[
            Typography.body,
            { color: colors.textSecondary, textAlign: 'center', marginTop: 8 },
          ]}
        >
          {loadError ?? 'Session not found.'}
        </Text>
        <TouchableOpacity
          style={[styles.retryBtn, { borderColor: colors.accent }]}
          onPress={loadSession}
          activeOpacity={0.7}
          accessibilityRole="button"
          accessibilityLabel="Retry"
        >
          <Text style={[Typography.buttonMedium, { color: colors.accent }]}>Retry</Text>
        </TouchableOpacity>
      </View>
    );
  }

  const { checklist, customer, vehicle, tradeIn } = session;
  const completionRatio =
    checklist.totalCount > 0 ? checklist.completedCount / checklist.totalCount : 0;
  const customerName =
    `${customer.firstName}${customer.lastName ? ' ' + customer.lastName : ''}`.trim();
  const vehicleLabel = `${vehicle.year} ${vehicle.makeName} ${vehicle.modelName} ${vehicle.trimName}`;
  const dateLabel = session.startedAt.toLocaleDateString(undefined, {
    weekday: 'short',
    year: 'numeric',
    month: 'short',
    day: 'numeric',
  });

  const footerHeight = readOnly ? 0 : 132;

  return (
    <View style={[styles.screen, { backgroundColor: colors.background }]}>
      <ScrollView
        contentContainerStyle={[
          styles.scrollContent,
          { paddingBottom: footerHeight + insets.bottom + 16 },
        ]}
        showsVerticalScrollIndicator={false}
      >
        {/* ── Hero Card ───────────────────────────────────────────────────── */}
        <View
          style={[
            styles.heroCard,
            { backgroundColor: colors.surface, borderColor: colors.border },
          ]}
        >
          <Text
            style={[Typography.h2, { color: colors.textPrimary }]}
            numberOfLines={1}
            accessibilityRole="header"
          >
            {customerName}
          </Text>
          <Text
            style={[Typography.body, { color: colors.textSecondary, marginTop: 4 }]}
            numberOfLines={2}
          >
            {vehicleLabel}
          </Text>
          <Text
            style={[Typography.caption, { color: colors.textMuted, marginTop: 4 }]}
          >
            {dateLabel}
          </Text>

          {/* Completion stat */}
          <View style={styles.statRow}>
            <Text style={[styles.statNumber, { color: colors.accent }]}>
              {checklist.completedCount}
              <Text style={[styles.statDivisor, { color: colors.textSecondary }]}>
                {' '}/ {checklist.totalCount}
              </Text>
            </Text>
            <Text
              style={[Typography.captionMedium, { color: colors.textSecondary, marginTop: 2 }]}
            >
              Questions Completed
            </Text>
          </View>

          {/* Progress bar */}
          <View
            style={[styles.progressTrack, { backgroundColor: colors.border }]}
            accessibilityRole="progressbar"
            accessibilityValue={{ min: 0, max: 100, now: Math.round(completionRatio * 100) }}
          >
            <View
              style={[
                styles.progressFill,
                {
                  backgroundColor: colors.accent,
                  width: `${Math.round(completionRatio * 100)}%`,
                },
              ]}
            />
          </View>
        </View>

        {/* ── Pending writes banner ────────────────────────────────────────── */}
        {!readOnly && pendingWrites.length > 0 && (
          <View
            style={[
              styles.pendingBanner,
              { backgroundColor: colors.warning + '26', borderColor: colors.warning },
            ]}
          >
            <Text style={[Typography.captionMedium, { color: colors.warning }]}>
              {`⚠️  ${pendingWrites.length} cue answer${pendingWrites.length !== 1 ? 's' : ''} pending sync`}
            </Text>
          </View>
        )}

        {/* ── NADA Checklist ───────────────────────────────────────────────── */}
        <SectionHeader title="NADA Checklist" colors={colors} />
        {checklist.steps.map((step, idx) => (
          <StepSummaryRow
            key={step.header.id}
            step={step}
            expanded={expandedSteps.has(idx)}
            onToggle={() => toggleStep(idx)}
            colors={colors}
          />
        ))}

        {/* ── Trade-In ─────────────────────────────────────────────────────── */}
        {tradeIn && (
          <>
            <SectionHeader title="Trade-In" colors={colors} />
            <TradeInSummary tradeIn={tradeIn} colors={colors} />
          </>
        )}
      </ScrollView>

      {/* ── Fixed Footer ────────────────────────────────────────────────────── */}
      {!readOnly && (
        <View
          style={[
            styles.footer,
            {
              backgroundColor: colors.background,
              borderTopColor: colors.border,
              paddingBottom: insets.bottom + 12,
            },
          ]}
        >
          {/* Sync button */}
          <TouchableOpacity
            style={[
              styles.footerBtn,
              styles.footerBtnPrimary,
              {
                backgroundColor:
                  syncing || pendingWrites.length === 0
                    ? colors.border
                    : colors.accent,
              },
            ]}
            onPress={handleSync}
            disabled={syncing || pendingWrites.length === 0}
            activeOpacity={0.8}
            accessibilityRole="button"
            accessibilityLabel="Sync to SmartComply"
            accessibilityState={{ disabled: syncing || pendingWrites.length === 0 }}
          >
            {syncing ? (
              <ActivityIndicator size="small" color="#FFFFFF" />
            ) : (
              <Text
                style={[
                  Typography.buttonLarge,
                  {
                    color:
                      pendingWrites.length === 0 ? colors.textMuted : '#FFFFFF',
                  },
                ]}
              >
                Sync to SmartComply
              </Text>
            )}
          </TouchableOpacity>

          {/* Share button */}
          <TouchableOpacity
            style={[
              styles.footerBtn,
              styles.footerBtnSecondary,
              { backgroundColor: colors.surface, borderColor: colors.border },
            ]}
            onPress={handleShare}
            disabled={sharing}
            activeOpacity={0.75}
            accessibilityRole="button"
            accessibilityLabel="Share Session Report"
            accessibilityState={{ disabled: sharing }}
          >
            {sharing ? (
              <ActivityIndicator size="small" color={colors.accent} />
            ) : (
              <Text style={[Typography.buttonLarge, { color: colors.accent }]}>
                Share Report
              </Text>
            )}
          </TouchableOpacity>
        </View>
      )}
    </View>
  );
}

// ─── Section Header ───────────────────────────────────────────────────────────

interface SectionHeaderProps {
  title: string;
  colors: ReturnType<typeof useTheme>['colors'];
}

function SectionHeader({ title, colors }: SectionHeaderProps) {
  return (
    <Text
      style={[
        Typography.label,
        styles.sectionHeader,
        { color: colors.textMuted },
      ]}
      accessibilityRole="header"
    >
      {title}
    </Text>
  );
}

// ─── Step Summary Row ─────────────────────────────────────────────────────────

interface StepSummaryRowProps {
  step: StepState;
  expanded: boolean;
  onToggle: () => void;
  colors: ReturnType<typeof useTheme>['colors'];
}

function StepSummaryRow({ step, expanded, onToggle, colors }: StepSummaryRowProps) {
  const completedCount = step.questions.filter(
    (q) => q.status === 'complete',
  ).length;
  const overriddenCount = step.questions.filter(
    (q) => q.status === 'overridden',
  ).length;
  const missedCount = step.questions.filter(
    (q) => q.status === 'pending' || q.status === 'partial',
  ).length;

  return (
    <View
      style={[
        styles.stepCard,
        { backgroundColor: colors.surface, borderColor: colors.border },
      ]}
    >
      <TouchableOpacity
        style={styles.stepHeader}
        onPress={onToggle}
        activeOpacity={0.7}
        accessibilityRole="button"
        accessibilityLabel={`Step ${step.header.orderNo}: ${step.header.name}. ${expanded ? 'Collapse' : 'Expand'}`}
        accessibilityState={{ expanded }}
      >
        {/* Step name */}
        <Text
          style={[styles.stepName, { color: colors.textPrimary }]}
          numberOfLines={2}
        >
          {`${step.header.orderNo}. ${step.header.name}`}
        </Text>

        {/* Status cluster */}
        <View style={styles.statusCluster}>
          {completedCount > 0 && (
            <StatusBadge
              label={`✓ ${completedCount}`}
              color={colors.stepComplete}
            />
          )}
          {overriddenCount > 0 && (
            <StatusBadge
              label={`M ${overriddenCount}`}
              color={colors.stepOverride}
            />
          )}
          {missedCount > 0 && (
            <StatusBadge
              label={`✕ ${missedCount}`}
              color={colors.stepPending}
            />
          )}
          {/* Chevron */}
          <Text
            style={[styles.chevron, { color: colors.textMuted }]}
          >
            {expanded ? '▲' : '▼'}
          </Text>
        </View>
      </TouchableOpacity>

      {/* Expanded question rows */}
      {expanded && (
        <View style={[styles.questionList, { borderTopColor: colors.border }]}>
          {step.questions.map((q) => (
            <QuestionSummaryRow key={q.question.id} question={q} colors={colors} />
          ))}
        </View>
      )}
    </View>
  );
}

// ─── Question Summary Row ─────────────────────────────────────────────────────

interface QuestionSummaryRowProps {
  question: QuestionState;
  colors: ReturnType<typeof useTheme>['colors'];
}

function QuestionSummaryRow({ question, colors }: QuestionSummaryRowProps) {
  const { status, detectedCues, question: q } = question;
  const snippet = detectedCues[0]?.transcriptSnippet;

  let chipLabel: string;
  let chipColor: string;

  switch (status) {
    case 'complete':
      chipLabel = 'Complete';
      chipColor = colors.stepComplete;
      break;
    case 'overridden':
      chipLabel = 'Manual';
      chipColor = colors.stepOverride;
      break;
    default:
      chipLabel = 'Not done';
      chipColor = colors.stepPending;
  }

  return (
    <View style={styles.questionRow}>
      <View style={styles.questionTextCol}>
        <Text style={[Typography.caption, { color: colors.textPrimary }]} numberOfLines={3}>
          {q.question}
        </Text>
        {snippet ? (
          <Text
            style={[
              Typography.caption,
              { color: colors.textMuted, marginTop: 2, fontStyle: 'italic' },
            ]}
            numberOfLines={2}
          >
            {`via voice: "${snippet.length > 60 ? snippet.slice(0, 60) + '…' : snippet}"`}
          </Text>
        ) : null}
      </View>
      <View style={[styles.statusChip, { backgroundColor: chipColor + '22' }]}>
        <Text style={[Typography.captionMedium, { color: chipColor }]}>
          {chipLabel}
        </Text>
      </View>
    </View>
  );
}

// ─── Status Badge ─────────────────────────────────────────────────────────────

interface StatusBadgeProps {
  label: string;
  color: string;
}

function StatusBadge({ label, color }: StatusBadgeProps) {
  return (
    <View style={[styles.badge, { backgroundColor: color + '22' }]}>
      <Text style={[Typography.captionMedium, { color }]}>{label}</Text>
    </View>
  );
}

// ─── Trade-In Summary ─────────────────────────────────────────────────────────

interface TradeInSummaryProps {
  tradeIn: NonNullable<Session['tradeIn']>;
  colors: ReturnType<typeof useTheme>['colors'];
}

const PHOTO_SLOT_LABELS: Record<string, string> = {
  front_left: 'Front Left',
  front_right: 'Front Right',
  rear_left: 'Rear Left',
  rear_right: 'Rear Right',
  interior: 'Interior',
  odometer: 'Odometer',
  vin: 'VIN',
};

function TradeInSummary({ tradeIn, colors }: TradeInSummaryProps) {
  const slots = Object.entries(tradeIn.photos);

  return (
    <View>
      {/* Photo grid — 2 columns */}
      <View style={styles.photoGrid}>
        {slots.map(([slot, uri]) => (
          <View key={slot} style={styles.photoCell}>
            {uri ? (
              <Image
                source={{ uri }}
                style={[styles.photoThumb, { borderColor: colors.border }]}
                accessibilityLabel={`Trade-in photo: ${PHOTO_SLOT_LABELS[slot] ?? slot}`}
              />
            ) : (
              <View
                style={[
                  styles.photoThumb,
                  styles.photoPlaceholder,
                  { backgroundColor: colors.photoNeeded, borderColor: colors.border },
                ]}
                accessibilityLabel={`Missing trade-in photo: ${PHOTO_SLOT_LABELS[slot] ?? slot}`}
              >
                <Text style={[Typography.captionMedium, { color: colors.textMuted }]}>
                  {PHOTO_SLOT_LABELS[slot] ?? slot}
                </Text>
              </View>
            )}
          </View>
        ))}
      </View>

      {/* Typed note */}
      {tradeIn.typedNote ? (
        <View
          style={[
            styles.noteCard,
            { backgroundColor: colors.surface, borderColor: colors.border },
          ]}
        >
          <Text style={[Typography.captionMedium, { color: colors.textMuted, marginBottom: 4 }]}>
            Note
          </Text>
          <Text style={[Typography.body, { color: colors.textPrimary }]}>
            {tradeIn.typedNote}
          </Text>
        </View>
      ) : null}
    </View>
  );
}

// ─── Styles ───────────────────────────────────────────────────────────────────

const styles = StyleSheet.create({
  screen: {
    flex: 1,
  },
  centered: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
    padding: 32,
  },
  retryBtn: {
    marginTop: 20,
    paddingVertical: 10,
    paddingHorizontal: 28,
    borderRadius: 8,
    borderWidth: 1,
  },

  // Scroll
  scrollContent: {
    paddingHorizontal: 16,
    paddingTop: 16,
  },

  // Hero Card
  heroCard: {
    borderRadius: 12,
    borderWidth: StyleSheet.hairlineWidth,
    padding: 16,
    marginBottom: 12,
  },
  statRow: {
    marginTop: 16,
    marginBottom: 10,
  },
  statNumber: {
    fontSize: 36,
    fontWeight: '700',
    lineHeight: 44,
  },
  statDivisor: {
    fontSize: 20,
    fontWeight: '400',
  },
  progressTrack: {
    height: 6,
    borderRadius: 3,
    overflow: 'hidden',
  },
  progressFill: {
    height: 6,
    borderRadius: 3,
  },

  // Pending banner
  pendingBanner: {
    borderRadius: 8,
    borderWidth: 1,
    paddingVertical: 10,
    paddingHorizontal: 14,
    marginBottom: 12,
  },

  // Section header
  sectionHeader: {
    marginTop: 20,
    marginBottom: 8,
    paddingHorizontal: 4,
  },

  // Step card
  stepCard: {
    borderRadius: 10,
    borderWidth: StyleSheet.hairlineWidth,
    marginBottom: 8,
    overflow: 'hidden',
  },
  stepHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingVertical: 12,
    paddingHorizontal: 14,
  },
  stepName: {
    ...Typography.bodyMedium,
    flex: 1,
    marginRight: 8,
  },
  statusCluster: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
    flexShrink: 0,
  },
  chevron: {
    fontSize: 10,
    marginLeft: 4,
  },

  // Badge (status cluster items)
  badge: {
    borderRadius: 4,
    paddingHorizontal: 6,
    paddingVertical: 2,
  },

  // Question list (expanded)
  questionList: {
    borderTopWidth: StyleSheet.hairlineWidth,
  },
  questionRow: {
    flexDirection: 'row',
    alignItems: 'flex-start',
    paddingVertical: 10,
    paddingHorizontal: 14,
    gap: 8,
  },
  questionTextCol: {
    flex: 1,
  },
  statusChip: {
    borderRadius: 6,
    paddingHorizontal: 8,
    paddingVertical: 3,
    flexShrink: 0,
  },

  // Trade-in photo grid
  photoGrid: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 8,
    marginBottom: 8,
  },
  photoCell: {
    width: 80,
  },
  photoThumb: {
    width: 80,
    height: 80,
    borderRadius: 8,
    borderWidth: StyleSheet.hairlineWidth,
  },
  photoPlaceholder: {
    justifyContent: 'center',
    alignItems: 'center',
  },

  // Trade-in typed note
  noteCard: {
    borderRadius: 10,
    borderWidth: StyleSheet.hairlineWidth,
    padding: 12,
    marginTop: 4,
    marginBottom: 8,
  },

  // Footer
  footer: {
    position: 'absolute',
    bottom: 0,
    left: 0,
    right: 0,
    borderTopWidth: StyleSheet.hairlineWidth,
    paddingHorizontal: 16,
    paddingTop: 12,
    gap: 10,
  },
  footerBtn: {
    height: 52,
    borderRadius: 10,
    justifyContent: 'center',
    alignItems: 'center',
  },
  footerBtnPrimary: {
    // backgroundColor set inline
  },
  footerBtnSecondary: {
    borderWidth: 1,
  },
});
