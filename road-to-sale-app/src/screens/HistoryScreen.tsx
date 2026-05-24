import React, { useCallback, useState } from 'react';
import {
  FlatList,
  RefreshControl,
  StyleSheet,
  Text,
  TouchableOpacity,
  View,
} from 'react-native';
import { useFocusEffect } from '@react-navigation/native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

import type { HistoryScreenProps } from '../navigation/types';
import { useTheme } from '../theme';
import { Typography } from '../theme';
import { getSessionRepository } from '../db/repositorySingleton';
import type { Session } from '../session/types';

// ─── Helpers ──────────────────────────────────────────────────────────────────

const TOTAL_QUESTIONS = 16;   // NADA Road-to-Sale template total (Road to Sale v1)

function completionCount(session: Session): number {
  return session.checklist.steps
    .flatMap((s) => s.questions)
    .filter((q) => q.status === 'complete' || q.status === 'overridden')
    .length;
}

function formatDate(date: Date): string {
  const d = date instanceof Date ? date : new Date(date);
  const months = [
    'Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun',
    'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec',
  ];
  const month = months[d.getMonth()];
  const day = d.getDate();
  const hours = d.getHours();
  const minutes = d.getMinutes();
  const ampm = hours >= 12 ? 'PM' : 'AM';
  const displayHour = hours % 12 === 0 ? 12 : hours % 12;
  const displayMinutes = String(minutes).padStart(2, '0');
  return `${month} ${day} at ${displayHour}:${displayMinutes} ${ampm}`;
}

// DC68: Status bar color uses completion percentage bucketed into three states:
// ≥ 80% = stepComplete (green), 40–79% = stepPartial (amber), < 40% = stepPending (grey).
// Crashed sessions always use stepPending.
function statusBarColor(
  session: Session,
  colors: ReturnType<typeof import('../theme').useTheme>['colors'],
): string {
  if (session.status === 'crashed') return colors.stepPending;
  const count = completionCount(session);
  const pct = TOTAL_QUESTIONS > 0 ? count / TOTAL_QUESTIONS : 0;
  if (pct >= 0.8) return colors.stepComplete;
  if (pct >= 0.4) return colors.stepPartial;
  return colors.stepPending;
}

// ─── Component ────────────────────────────────────────────────────────────────

export default function HistoryScreen({ navigation }: HistoryScreenProps) {
  const { colors } = useTheme();
  const insets = useSafeAreaInsets();

  const [sessions, setSessions] = useState<Session[]>([]);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);

  // ── Load sessions ─────────────────────────────────────────────────────────

  const loadSessions = useCallback(async (isRefresh = false) => {
    if (isRefresh) {
      setRefreshing(true);
    } else {
      setLoading(true);
    }
    try {
      const repo = getSessionRepository();
      const result = await repo.getAllSessions(50);
      setSessions(result);
    } catch (e) {
      console.warn('[History] Failed to load sessions:', e);
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }, []);

  // Reload whenever the screen comes into focus (e.g. returning from SessionSummary)
  useFocusEffect(
    useCallback(() => {
      loadSessions(false);
    }, [loadSessions]),
  );

  // ── Navigate to session summary ───────────────────────────────────────────

  const handleSessionPress = useCallback(
    (session: Session) => {
      navigation.navigate('SessionSummary', { sessionId: session.id, readOnly: true });
    },
    [navigation],
  );

  // ── Empty state ───────────────────────────────────────────────────────────

  function renderEmptyState() {
    if (loading) return null;   // Show nothing while loading; FlatList ListEmptyComponent fires after data
    return (
      <View style={styles.emptyContainer}>
        <Text style={styles.emptyIcon}>📋</Text>
        <Text style={[Typography.h3, { color: colors.textPrimary, marginTop: 16, marginBottom: 8 }]}>
          No Sessions Yet
        </Text>
        <Text style={[Typography.body, { color: colors.textSecondary, textAlign: 'center' }]}>
          Completed sessions will appear here.
        </Text>
      </View>
    );
  }

  // ── Main render ───────────────────────────────────────────────────────────

  return (
    <View style={[styles.container, { backgroundColor: colors.background }]}>
      <FlatList
        data={sessions}
        keyExtractor={(item) => item.id}
        contentContainerStyle={[
          styles.listContent,
          { paddingBottom: insets.bottom + 16 },
          sessions.length === 0 ? styles.emptyListContent : undefined,
        ]}
        renderItem={({ item }) => (
          <SessionRow
            session={item}
            colors={colors}
            barColor={statusBarColor(item, colors)}
            onPress={() => handleSessionPress(item)}
          />
        )}
        ItemSeparatorComponent={() => (
          <View style={[styles.separator, { backgroundColor: colors.border }]} />
        )}
        ListEmptyComponent={renderEmptyState}
        refreshControl={
          <RefreshControl
            refreshing={refreshing}
            onRefresh={() => loadSessions(true)}
            tintColor={colors.accent}
            colors={[colors.accent]}
          />
        }
      />
    </View>
  );
}

// ─── SessionRow ───────────────────────────────────────────────────────────────

interface SessionRowProps {
  session: Session;
  barColor: string;
  colors: ReturnType<typeof import('../theme').useTheme>['colors'];
  onPress: () => void;
}

function SessionRow({ session, barColor, colors, onPress }: SessionRowProps) {
  const { customer, vehicle, startedAt } = session;
  const customerName =
    customer.firstName + (customer.lastName ? ` ${customer.lastName}` : '');
  const vehicleLabel = [vehicle.year, vehicle.makeName, vehicle.modelName]
    .filter(Boolean)
    .join(' ');
  const count = completionCount(session);

  return (
    <TouchableOpacity
      style={styles.rowContainer}
      onPress={onPress}
      activeOpacity={0.7}
      accessibilityRole="button"
      accessibilityLabel={`${customerName} — ${vehicleLabel}`}
    >
      {/* Colored status bar */}
      <View style={[styles.statusBar, { backgroundColor: barColor }]} />

      {/* Content */}
      <View style={styles.rowContent}>
        <View style={styles.rowMain}>
          <Text
            style={[Typography.h3, { color: colors.textPrimary }]}
            numberOfLines={1}
          >
            {customerName}
          </Text>
          <Text
            style={[Typography.caption, { color: colors.textSecondary, marginTop: 2 }]}
            numberOfLines={1}
          >
            {vehicleLabel}
          </Text>
          <Text style={[Typography.caption, { color: colors.textMuted, marginTop: 4 }]}>
            {formatDate(startedAt)}
          </Text>
        </View>

        {/* Completion chip */}
        <View style={[styles.completionChip, { backgroundColor: colors.surfaceElevated }]}>
          <Text style={[Typography.captionMedium, { color: colors.textMuted }]}>
            {count} / {TOTAL_QUESTIONS}
          </Text>
        </View>
      </View>
    </TouchableOpacity>
  );
}

// ─── Styles ───────────────────────────────────────────────────────────────────

const styles = StyleSheet.create({
  container: {
    flex: 1,
  },
  listContent: {
    paddingTop: 8,
  },
  emptyListContent: {
    flex: 1,
    justifyContent: 'center',
  },

  // Empty state
  emptyContainer: {
    alignItems: 'center',
    paddingHorizontal: 32,
    paddingVertical: 48,
  },
  emptyIcon: {
    fontSize: 48,
  },

  // Row
  rowContainer: {
    flexDirection: 'row',
    paddingVertical: 14,
    paddingRight: 16,
  },
  statusBar: {
    width: 4,
    borderRadius: 2,
    marginRight: 14,
    marginLeft: 16,
    minHeight: 64,
  },
  rowContent: {
    flex: 1,
    flexDirection: 'row',
    alignItems: 'center',
  },
  rowMain: {
    flex: 1,
    paddingRight: 12,
  },

  // Completion chip
  completionChip: {
    paddingVertical: 4,
    paddingHorizontal: 10,
    borderRadius: 8,
    alignSelf: 'center',
  },

  // Separator
  separator: {
    height: StyleSheet.hairlineWidth,
    marginLeft: 34,   // aligns with row text (16 margin + 4 bar + 14 gap)
  },
});
