import React, { useCallback, useEffect, useRef, useState } from 'react';
import {
  ActivityIndicator,
  Alert,
  Animated,
  AppState,
  Easing,
  FlatList,
  StyleSheet,
  Text,
  TouchableOpacity,
  View,
} from 'react-native';
import { useFocusEffect } from '@react-navigation/native';

import { getSmartComplyClient } from '../api/clientSingleton';
import { getSessionRepository } from '../db/repositorySingleton';
import { AuditProCrmProvider } from '../crm/AuditProCrmProvider';
import type { Appointment } from '../crm/types';
import type { AppointmentBrief } from '../navigation/types';
import type { HomeScreenProps } from '../navigation/types';
import { useTheme } from '../theme';
import { Typography } from '../theme';

// ── Component ─────────────────────────────────────────────────────────────────

export default function HomeScreen({ navigation }: HomeScreenProps) {
  const { colors } = useTheme();

  const [appointments, setAppointments] = useState<Appointment[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  // Task 9.5: wired to SQLite pending writes queue
  const [pendingSyncCount, setPendingSyncCount] = useState(0);
  // Task 4.4: cache timestamp for stale-data badge
  const [cacheTimestamp, setCacheTimestamp] = useState<Date | null>(null);
  const [isOffline, setIsOffline] = useState(false);

  // ── Task 9.5: fetch pending writes count ──────────────────────────────────
  const refreshPendingCount = useCallback(async () => {
    try {
      const writes = await getSessionRepository().getPendingWrites();
      setPendingSyncCount(writes.length);
    } catch {
      // silently ignore — badge stays at previous value
    }
  }, []);

  // AppState listener: refresh count when app comes to foreground
  useEffect(() => {
    const sub = AppState.addEventListener('change', (state) => {
      if (state === 'active') {
        refreshPendingCount();
      }
    });
    return () => sub.remove();
  }, [refreshPendingCount]);

  // ── Task 2.6: logout handler ──────────────────────────────────────────────
  const handleLogout = useCallback(async () => {
    try {
      await getSmartComplyClient().logout();
      navigation.replace('Auth');
    } catch (err: unknown) {
      Alert.alert(
        'Logout Failed',
        err instanceof Error ? err.message : 'Unable to log out. Please try again.',
      );
    }
  }, [navigation]);

  // ── Header right: [pending badge | logout button] ─────────────────────────
  useEffect(() => {
    navigation.setOptions({
      headerRight: () => (
        <View style={styles.headerRight}>
          {pendingSyncCount > 0 && (
            <View style={[styles.badge, { backgroundColor: colors.error }]}>
              <Text style={styles.badgeText}>{pendingSyncCount}</Text>
            </View>
          )}
          <TouchableOpacity
            onPress={handleLogout}
            activeOpacity={0.7}
            accessibilityRole="button"
            accessibilityLabel="Logout"
            style={styles.logoutButton}
          >
            <Text style={[styles.logoutText, { color: colors.accent }]}>Logout</Text>
          </TouchableOpacity>
        </View>
      ),
    });
  }, [navigation, pendingSyncCount, colors.error, colors.accent, handleLogout]);

  // ── Task 4.4: fetch appointments with offline cache ───────────────────────
  const fetchAppointments = useCallback(async () => {
    const today = new Date().toISOString().split('T')[0];
    const repId = ''; // TODO: replace with real repId from auth context

    // Step 1: load from cache immediately
    try {
      const cached = await getSessionRepository().getCachedAppointments(today, repId);
      if (cached && cached.data.length > 0) {
        setAppointments(cached.data as Appointment[]);
        setCacheTimestamp(cached.fetchedAt);
        setLoading(false);
      }
    } catch {
      // cache miss or repo error — proceed to network fetch
    }

    // Step 2: fetch from network in background
    setError(null);
    try {
      const client = getSmartComplyClient();
      const provider = new AuditProCrmProvider(client);
      // TODO: pass real storeId from auth context
      const result = await provider.getTodayAppointments('');
      setAppointments(result);
      setIsOffline(false);
      const now = new Date();
      setCacheTimestamp(now);
      setLoading(false);
      // Persist fresh data to cache
      try {
        await getSessionRepository().cacheAppointments(today, repId, result as object[]);
      } catch {
        // cache write failure is non-fatal
      }
    } catch (err: unknown) {
      // Network failed — if we already have cached data, show offline badge instead of error
      if (appointments.length > 0) {
        setIsOffline(true);
      } else {
        setError(err instanceof Error ? err.message : 'Failed to load appointments.');
      }
      setLoading(false);
    }
  }, [appointments.length]);

  // Fetch on focus + refresh pending count
  useFocusEffect(
    useCallback(() => {
      fetchAppointments();
      refreshPendingCount();
    }, [fetchAppointments, refreshPendingCount]),
  );

  // ── Stale data / offline badge ────────────────────────────────────────────
  function renderCacheBadge() {
    if (isOffline && cacheTimestamp) {
      const hh = cacheTimestamp.getHours().toString().padStart(2, '0');
      const mm = cacheTimestamp.getMinutes().toString().padStart(2, '0');
      return (
        <Text style={[styles.staleBadge, { color: colors.textSecondary }]}>
          Offline — showing cached data as of {hh}:{mm}
        </Text>
      );
    }
    if (cacheTimestamp) {
      const ageMs = Date.now() - cacheTimestamp.getTime();
      if (ageMs > 15 * 60 * 1000) {
        const hh = cacheTimestamp.getHours().toString().padStart(2, '0');
        const mm = cacheTimestamp.getMinutes().toString().padStart(2, '0');
        return (
          <Text style={[styles.staleBadge, { color: colors.textSecondary }]}>
            Data as of {hh}:{mm}
          </Text>
        );
      }
    }
    return null;
  }

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
      <>
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
          ListFooterComponent={renderCacheBadge}
        />
      </>
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

  // Header right area
  headerRight: {
    flexDirection: 'row',
    alignItems: 'center',
    marginRight: 4,
  },
  logoutButton: {
    paddingHorizontal: 8,
    paddingVertical: 4,
  },
  logoutText: {
    fontSize: 15,
    fontWeight: '500',
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

  // Stale / offline data badge
  staleBadge: {
    ...Typography.caption,
    textAlign: 'center',
    paddingVertical: 6,
    paddingHorizontal: 16,
  },
});
