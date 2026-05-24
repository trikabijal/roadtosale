import React, { useCallback, useEffect, useRef, useState } from 'react';
import {
  ActivityIndicator,
  Animated,
  Easing,
  FlatList,
  StyleSheet,
  Text,
  TouchableOpacity,
  View,
} from 'react-native';
import { useFocusEffect } from '@react-navigation/native';

import { getSmartComplyClient } from '../api/clientSingleton';
import { AuditProCrmProvider } from '../crm/AuditProCrmProvider';
import type { Appointment } from '../crm/types';
import type { AppointmentBrief } from '../navigation/types';
import type { HomeScreenProps } from '../navigation/types';
import { useTheme } from '../theme';
import { Typography } from '../theme';

// TODO: wire SQLite cache in task 9
// const repo: ISessionRepository = ...

// ── Component ─────────────────────────────────────────────────────────────────

export default function HomeScreen({ navigation }: HomeScreenProps) {
  const { colors } = useTheme();

  const [appointments, setAppointments] = useState<Appointment[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  // pendingSyncCount would be read from SQLite in task 9
  const [pendingSyncCount] = useState(0);

  // ── Header right badge ────────────────────────────────────────────────────
  useEffect(() => {
    navigation.setOptions({
      headerRight: () =>
        pendingSyncCount > 0 ? (
          <View style={[styles.badge, { backgroundColor: colors.error }]}>
            <Text style={styles.badgeText}>{pendingSyncCount}</Text>
          </View>
        ) : null,
    });
  }, [navigation, pendingSyncCount, colors.error]);

  // ── Fetch appointments ────────────────────────────────────────────────────
  const fetchAppointments = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const client = getSmartComplyClient();
      const provider = new AuditProCrmProvider(client);
      // TODO (task 9): pass real storeId from auth context
      const result = await provider.getTodayAppointments('');
      setAppointments(result);
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : 'Failed to load appointments.');
    } finally {
      setLoading(false);
    }
  }, []);

  // Fetch on mount and each time the screen comes into focus
  useFocusEffect(
    useCallback(() => {
      fetchAppointments();
    }, [fetchAppointments]),
  );

  // ── Navigate to SessionSetup ──────────────────────────────────────────────
  function handleAppointmentPress(appt: Appointment) {
    if (!appt.smartComply) return;
    const brief: AppointmentBrief = {
      assignmentId: appt.smartComply.assignmentId,
      auditId: appt.smartComply.auditId,
      auditName: appt.id,          // actual auditName not carried in CRM type; resolved in SessionSetup
      checksheetId: appt.smartComply.checksheetId,
      checksheetName: '',          // resolved in SessionSetup from checksheet detail
      locationLabel: appt.storeId,
      userChecksheetId: appt.smartComply.userChecksheetId,
      status: appt.smartComply.status as AppointmentBrief['status'],
      answeredQuestions: 0,
      totalQuestions: 0,
    };
    navigation.navigate('SessionSetup', { appointment: brief });
  }

  function handleNewWalkIn() {
    navigation.navigate('SessionSetup', {});
  }

  // ── Render states ─────────────────────────────────────────────────────────

  function renderContent() {
    if (loading) {
      return (
        <View style={styles.centered}>
          <ActivityIndicator size="large" color={colors.accent} />
        </View>
      );
    }

    if (error !== null) {
      return (
        <View style={styles.centered}>
          <Text style={[styles.stateTitle, { color: colors.textPrimary }]}>
            Could not load appointments
          </Text>
          <Text style={[styles.stateBody, { color: colors.textSecondary }]}>{error}</Text>
          <TouchableOpacity
            style={[styles.retryButton, { borderColor: colors.accent }]}
            onPress={fetchAppointments}
            activeOpacity={0.7}
            accessibilityRole="button"
            accessibilityLabel="Retry"
          >
            <Text style={[Typography.buttonMedium, { color: colors.accent }]}>Retry</Text>
          </TouchableOpacity>
        </View>
      );
    }

    if (appointments.length === 0) {
      return (
        <View style={styles.centered}>
          <Text style={[styles.stateTitle, { color: colors.textPrimary }]}>
            No appointments today
          </Text>
          <Text style={[styles.stateBody, { color: colors.textSecondary }]}>
            Start a new walk-in below.
          </Text>
        </View>
      );
    }

    return (
      <FlatList
        data={appointments}
        keyExtractor={(item) => item.id}
        contentContainerStyle={styles.listContent}
        renderItem={({ item }) => (
          <AppointmentCard
            appointment={item}
            onPress={() => handleAppointmentPress(item)}
            colors={colors}
          />
        )}
      />
    );
  }

  return (
    <View style={[styles.container, { backgroundColor: colors.background }]}>
      {renderContent()}

      {/* ── New Walk-In CTA — always visible ─────────────────────────────── */}
      <View style={[styles.footer, { borderTopColor: colors.border }]}>
        <TouchableOpacity
          style={[styles.walkInButton, { backgroundColor: colors.accent }]}
          onPress={handleNewWalkIn}
          activeOpacity={0.8}
          accessibilityRole="button"
          accessibilityLabel="New Walk-In"
        >
          <Text style={styles.walkInButtonText}>＋  New Walk-In</Text>
        </TouchableOpacity>
      </View>
    </View>
  );
}

// ── AppointmentCard ───────────────────────────────────────────────────────────

interface CardProps {
  appointment: Appointment;
  onPress: () => void;
  colors: ReturnType<typeof import('../theme').useTheme>['colors'];
}

function AppointmentCard({ appointment, onPress, colors }: CardProps) {
  const sc = appointment.smartComply;
  const isInProgress = sc?.status === 'IN_PROGRESS';

  // Pulsing green dot animation
  const pulseAnim = useRef(new Animated.Value(1)).current;
  useEffect(() => {
    if (!isInProgress) return;
    const pulse = Animated.loop(
      Animated.sequence([
        Animated.timing(pulseAnim, {
          toValue: 0.3,
          duration: 700,
          easing: Easing.inOut(Easing.ease),
          useNativeDriver: true,
        }),
        Animated.timing(pulseAnim, {
          toValue: 1,
          duration: 700,
          easing: Easing.inOut(Easing.ease),
          useNativeDriver: true,
        }),
      ]),
    );
    pulse.start();
    return () => pulse.stop();
  }, [isInProgress, pulseAnim]);

  const statusLabel = isInProgress ? 'In Progress' : 'Scheduled';
  const statusColor = isInProgress ? colors.stepComplete : colors.accent;
  const customerName =
    appointment.customer.firstName +
    (appointment.customer.lastName ? ` ${appointment.customer.lastName}` : '');

  return (
    <TouchableOpacity
      style={[styles.card, { backgroundColor: colors.surface, borderColor: colors.border }]}
      onPress={onPress}
      activeOpacity={0.75}
      accessibilityRole="button"
      accessibilityLabel={`${customerName} — ${statusLabel}`}
    >
      {/* Customer name */}
      <Text style={[styles.cardName, { color: colors.textPrimary }]} numberOfLines={1}>
        {customerName}
      </Text>

      {/* Status chip */}
      <View style={styles.statusRow}>
        {isInProgress && (
          <Animated.View
            style={[styles.pulseDot, { backgroundColor: colors.micActive, opacity: pulseAnim }]}
          />
        )}
        <Text style={[styles.statusChip, { color: statusColor }]}>{statusLabel}</Text>
      </View>

      {/* Checksheet / audit name */}
      {sc && (
        <Text style={[styles.cardMeta, { color: colors.textSecondary }]} numberOfLines={1}>
          {sc.checksheetId ? `Template #${sc.checksheetId}` : 'Unknown template'}
        </Text>
      )}

      {/* Location label */}
      {appointment.storeId ? (
        <Text style={[styles.cardMeta, { color: colors.textMuted }]} numberOfLines={1}>
          {appointment.storeId}
        </Text>
      ) : null}
    </TouchableOpacity>
  );
}

// ── Styles ────────────────────────────────────────────────────────────────────

const styles = StyleSheet.create({
  container: {
    flex: 1,
  },
  centered: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
    padding: 32,
  },
  stateTitle: {
    ...Typography.h3,
    textAlign: 'center',
    marginBottom: 8,
  },
  stateBody: {
    ...Typography.body,
    textAlign: 'center',
    marginBottom: 24,
  },
  retryButton: {
    paddingVertical: 10,
    paddingHorizontal: 28,
    borderRadius: 8,
    borderWidth: 1,
  },

  // List
  listContent: {
    padding: 16,
    paddingBottom: 8,
  },

  // Card
  card: {
    borderRadius: 12,
    borderWidth: 1,
    padding: 16,
    marginBottom: 12,
  },
  cardName: {
    ...Typography.h3,
    marginBottom: 6,
  },
  statusRow: {
    flexDirection: 'row',
    alignItems: 'center',
    marginBottom: 6,
  },
  pulseDot: {
    width: 8,
    height: 8,
    borderRadius: 4,
    marginRight: 6,
  },
  statusChip: {
    ...Typography.captionMedium,
  },
  cardMeta: {
    ...Typography.caption,
    marginTop: 2,
  },

  // Footer
  footer: {
    borderTopWidth: StyleSheet.hairlineWidth,
    paddingHorizontal: 16,
    paddingVertical: 12,
    paddingBottom: 28,
  },
  walkInButton: {
    height: 52,
    borderRadius: 10,
    justifyContent: 'center',
    alignItems: 'center',
  },
  walkInButtonText: {
    ...Typography.buttonLarge,
    color: '#FFFFFF',
  },

  // Header badge
  badge: {
    minWidth: 20,
    height: 20,
    borderRadius: 10,
    justifyContent: 'center',
    alignItems: 'center',
    paddingHorizontal: 4,
    marginRight: 8,
  },
  badgeText: {
    color: '#FFFFFF',
    fontSize: 11,
    fontWeight: '700',
  },
});
