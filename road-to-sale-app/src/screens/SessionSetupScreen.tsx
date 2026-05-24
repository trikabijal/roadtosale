/**
 * SessionSetupScreen.tsx
 *
 * Configures a new Road to Sale session before it goes live.
 * Accepts an optional AppointmentBrief from navigation params (pre-scheduled).
 * Walk-ins arrive with no appointment param.
 *
 * Flow:
 *   1. Customer info (firstName required)
 *   2. Vehicle picker — Make → Model → Year → Trim (cascading, catalog-driven)
 *   3. Trim Highlights panel (top 6 standard features for selected trim)
 *   4. "Start Session" footer — fetches checksheet, loads cue pack, starts engine, navigates
 */

import React, {
  useState,
  useEffect,
  useMemo,
  useCallback,
} from 'react';
import {
  View,
  Text,
  TextInput,
  TouchableOpacity,
  ScrollView,
  StyleSheet,
  KeyboardAvoidingView,
  Platform,
  Alert,
  ActivityIndicator,
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

import { useTheme } from '../theme';
import { Typography } from '../theme';
import type { SessionSetupScreenProps } from '../navigation/types';
import { catalogLoader } from '../catalog/loader';
import type { Make, Model, Trim, Feature } from '../catalog/loader';
import { getSmartComplyClient } from '../api/clientSingleton';
import { loadRtsV1CuePack } from '../cue-packs/loader';
import { getSessionEngine } from '../session/sessionEngineSingleton';

// ─── Constants ────────────────────────────────────────────────────────────────

/**
 * DC65: DEFAULT_CHECKSHEET_ID = 2001.
 * The Honda RTS checksheet is seeded at id 2001 in the SmartComply dev instance.
 * Walk-in sessions without an appointment.checksheetId fall back to this value.
 */
const DEFAULT_CHECKSHEET_ID = 2001;

/**
 * DC65: WALKIN_ASSIGNMENT_ID = 1.
 * The seeded bootstrap inspection id for the Road to Sale walk-in campaign.
 * Used when no appointment.assignmentId is available.
 */
const WALKIN_ASSIGNMENT_ID = 1;

/** Max features to show in the Trim Highlights panel. */
const MAX_HIGHLIGHT_FEATURES = 6;

// ─── Component ────────────────────────────────────────────────────────────────

export default function SessionSetupScreen({
  navigation,
  route,
}: SessionSetupScreenProps) {
  const { colors } = useTheme();
  const insets = useSafeAreaInsets();
  const appointment = route.params?.appointment;

  // ── Customer info ──────────────────────────────────────────────────────────
  const [firstName, setFirstName] = useState('');
  const [lastName, setLastName] = useState('');
  const [phone, setPhone] = useState('');

  // ── Vehicle picker state ───────────────────────────────────────────────────
  const [selectedMakeId, setSelectedMakeId] = useState<string | null>(null);
  const [selectedModelId, setSelectedModelId] = useState<string | null>(null);
  const [selectedYear, setSelectedYear] = useState<number | null>(null);
  const [selectedTrimId, setSelectedTrimId] = useState<string | null>(null);

  // ── Loading / error ────────────────────────────────────────────────────────
  const [loading, setLoading] = useState(false);

  // ─── Set header title based on appointment presence ───────────────────────
  useEffect(() => {
    navigation.setOptions({
      title: appointment ? 'New Session' : 'Walk-In Session',
    });
  }, [navigation, appointment]);

  // ─── Catalog data (synchronous — catalog loaded at module init) ────────────

  const makes: Make[] = useMemo(() => catalogLoader.list_makes(), []);

  const modelsForMake: Model[] = useMemo(() => {
    if (!selectedMakeId) return [];
    return catalogLoader.list_models(selectedMakeId);
  }, [selectedMakeId]);

  /**
   * DC65: Year comes from Model.year (not Trim.year — Trim has no year field in
   * the current catalog schema). We group models by their year and present unique
   * years sorted descending. Selecting a year then narrows the model list to
   * models matching that year.
   *
   * Because the V1 catalog is all 2026 models, the year picker shows a single
   * chip "2026". This is still correct behaviour for future catalogs with multiple
   * model years (e.g., 2025 certified pre-owned alongside 2026 new).
   */
  const availableYears: number[] = useMemo(() => {
    const yearSet = new Set<number>(modelsForMake.map((m) => m.year));
    return Array.from(yearSet).sort((a, b) => b - a);
  }, [modelsForMake]);

  /**
   * Models visible in the model picker: filtered to the selected year once a
   * year is chosen, otherwise all models for the make.
   */
  const filteredModels: Model[] = useMemo(() => {
    if (!selectedYear) return modelsForMake;
    return modelsForMake.filter((m) => m.year === selectedYear);
  }, [modelsForMake, selectedYear]);

  const trimsForModel: Trim[] = useMemo(() => {
    if (!selectedModelId) return [];
    return catalogLoader.list_trims(selectedModelId);
  }, [selectedModelId]);

  const highlightFeatures: Feature[] = useMemo(() => {
    if (!selectedTrimId) return [];
    return catalogLoader
      .list_features_for_trim(selectedTrimId, ['standard'])
      .slice(0, MAX_HIGHLIGHT_FEATURES);
  }, [selectedTrimId]);

  // ─── Picker reset cascade ─────────────────────────────────────────────────

  const handleMakeSelect = useCallback((makeId: string) => {
    setSelectedMakeId(makeId);
    setSelectedModelId(null);
    setSelectedYear(null);
    setSelectedTrimId(null);
  }, []);

  const handleYearSelect = useCallback((year: number) => {
    setSelectedYear(year);
    // If the previously-selected model doesn't belong to this year, clear it
    setSelectedModelId((prev) => {
      if (!prev) return null;
      try {
        const model = catalogLoader.get_model(prev);
        return model.year === year ? prev : null;
      } catch {
        return null;
      }
    });
    setSelectedTrimId(null);
  }, []);

  const handleModelSelect = useCallback((modelId: string) => {
    setSelectedModelId(modelId);
    setSelectedTrimId(null);
  }, []);

  const handleTrimSelect = useCallback((trimId: string) => {
    setSelectedTrimId(trimId);
  }, []);

  // ─── Form validity ────────────────────────────────────────────────────────

  const canStart =
    firstName.trim().length > 0 && selectedTrimId !== null && !loading;

  // ─── Start Session ────────────────────────────────────────────────────────

  const handleStartSession = useCallback(async () => {
    if (!canStart || !selectedMakeId || !selectedModelId || !selectedTrimId) return;

    setLoading(true);
    try {
      const client = getSmartComplyClient();

      const checksheetId = appointment?.checksheetId ?? DEFAULT_CHECKSHEET_ID;
      const assignmentId = appointment?.assignmentId ?? WALKIN_ASSIGNMENT_ID;

      // Step 1: Fetch the SmartComply checksheet (always live — not cached here)
      const checksheet = await client.getChecksheetDetail(checksheetId);

      // Step 2: Load the Road to Sale V1 cue pack
      const cuePack = loadRtsV1CuePack();

      // Step 3: Resolve make / model / trim display names
      const make = catalogLoader.get_make(selectedMakeId);
      const model = catalogLoader.get_model(selectedModelId);
      const trim = catalogLoader.get_trim(selectedTrimId);

      // Step 4: Start session via engine
      const engine = getSessionEngine();
      const session = await engine.startSession(
        assignmentId,
        {
          firstName: firstName.trim(),
          lastName: lastName.trim() || undefined,
          phone: phone.trim() || undefined,
        },
        {
          makeId: make.id,
          makeName: make.name,
          modelId: model.id,
          modelName: model.name,
          trimId: trim.id,
          trimName: trim.name,
          year: model.year,
        },
        checksheet,
        cuePack,
      );

      // Step 5: Navigate — replace so Back doesn't return here mid-session
      navigation.replace('ActiveSession', { sessionId: session.id });
    } catch (err: unknown) {
      const message =
        err instanceof Error ? err.message : 'An unexpected error occurred.';
      Alert.alert('Error', message);
    } finally {
      setLoading(false);
    }
  }, [
    canStart,
    selectedMakeId,
    selectedModelId,
    selectedTrimId,
    appointment,
    firstName,
    lastName,
    phone,
    navigation,
  ]);

  // ─── Appointment status chip label ────────────────────────────────────────

  function statusLabel(status: NonNullable<typeof appointment>['status']): string {
    switch (status) {
      case 'ASSIGNED':    return 'Scheduled';
      case 'IN_PROGRESS': return 'In Progress';
      case 'SUBMITTED':   return 'Submitted';
      case 'VALIDATED':   return 'Validated';
      case 'APPROVED':    return 'Approved';
    }
  }

  function statusChipColor(status: NonNullable<typeof appointment>['status']): string {
    switch (status) {
      case 'IN_PROGRESS': return colors.success;
      case 'SUBMITTED':
      case 'VALIDATED':
      case 'APPROVED':    return colors.accent;
      default:            return colors.textSecondary;
    }
  }

  // ─── Render ───────────────────────────────────────────────────────────────

  return (
    <KeyboardAvoidingView
      style={[styles.root, { backgroundColor: colors.background }]}
      behavior={Platform.OS === 'ios' ? 'padding' : 'height'}
      keyboardVerticalOffset={Platform.OS === 'ios' ? 90 : 0}
    >
      {/* ── Scrollable body ─────────────────────────────────────────────── */}
      <ScrollView
        style={styles.scroll}
        contentContainerStyle={styles.scrollContent}
        keyboardShouldPersistTaps="handled"
        showsVerticalScrollIndicator={false}
      >

        {/* ── Appointment card (pre-scheduled only) ─────────────────────── */}
        {appointment && (
          <View
            style={[
              styles.appointmentCard,
              { backgroundColor: colors.surface, borderColor: colors.border },
            ]}
          >
            <Text
              style={[styles.appointmentAuditName, { color: colors.textPrimary }]}
              numberOfLines={2}
            >
              {appointment.auditName || appointment.checksheetName || 'Scheduled Session'}
            </Text>
            <View style={styles.appointmentRow}>
              <Text style={[styles.appointmentMeta, { color: colors.textSecondary }]}>
                {appointment.locationLabel}
              </Text>
              <View
                style={[
                  styles.statusChip,
                  { backgroundColor: statusChipColor(appointment.status) + '20' },
                ]}
              >
                <Text
                  style={[
                    styles.statusChipText,
                    { color: statusChipColor(appointment.status) },
                  ]}
                >
                  {statusLabel(appointment.status)}
                </Text>
              </View>
            </View>
            {appointment.totalQuestions > 0 && (
              <Text style={[styles.appointmentMeta, { color: colors.textMuted }]}>
                {appointment.answeredQuestions}/{appointment.totalQuestions} questions answered
              </Text>
            )}
          </View>
        )}

        {/* ── Section 1: Customer Info ───────────────────────────────────── */}
        <Text style={[styles.sectionTitle, { color: colors.textSecondary }]}>
          CUSTOMER
        </Text>

        <TextInput
          style={[
            styles.textInput,
            {
              backgroundColor: colors.surface,
              borderColor: colors.border,
              color: colors.textPrimary,
            },
          ]}
          placeholder="First Name *"
          placeholderTextColor={colors.textMuted}
          value={firstName}
          onChangeText={setFirstName}
          autoFocus
          autoCapitalize="words"
          autoCorrect={false}
          returnKeyType="next"
          accessibilityLabel="First Name"
        />

        <TextInput
          style={[
            styles.textInput,
            {
              backgroundColor: colors.surface,
              borderColor: colors.border,
              color: colors.textPrimary,
            },
          ]}
          placeholder="Last Name"
          placeholderTextColor={colors.textMuted}
          value={lastName}
          onChangeText={setLastName}
          autoCapitalize="words"
          autoCorrect={false}
          returnKeyType="next"
          accessibilityLabel="Last Name"
        />

        <TextInput
          style={[
            styles.textInput,
            {
              backgroundColor: colors.surface,
              borderColor: colors.border,
              color: colors.textPrimary,
            },
          ]}
          placeholder="Phone Number"
          placeholderTextColor={colors.textMuted}
          value={phone}
          onChangeText={setPhone}
          keyboardType="phone-pad"
          returnKeyType="done"
          accessibilityLabel="Phone Number"
        />

        {/* ── Section 2: Vehicle ────────────────────────────────────────── */}
        <Text style={[styles.sectionTitle, { color: colors.textSecondary }]}>
          VEHICLE
        </Text>

        {/* Make picker */}
        {makes.length === 0 ? (
          <View style={[styles.emptyPicker, { backgroundColor: colors.surface, borderColor: colors.border }]}>
            <Text style={[Typography.caption, { color: colors.textMuted }]}>
              No makes available — catalog not built
            </Text>
          </View>
        ) : (
          <>
            <Text style={[styles.pickerLabel, { color: colors.textMuted }]}>Make</Text>
            <ScrollView
              horizontal
              showsHorizontalScrollIndicator={false}
              style={styles.chipRow}
              contentContainerStyle={styles.chipRowContent}
            >
              {makes.map((make) => (
                <MakeChip
                  key={make.id}
                  label={make.name}
                  selected={selectedMakeId === make.id}
                  onPress={() => handleMakeSelect(make.id)}
                  colors={colors}
                />
              ))}
            </ScrollView>
          </>
        )}

        {/* Model picker — shown after make selected */}
        {selectedMakeId !== null && (
          <>
            <Text style={[styles.pickerLabel, { color: colors.textMuted }]}>Model</Text>
            <ScrollView
              horizontal
              showsHorizontalScrollIndicator={false}
              style={styles.chipRow}
              contentContainerStyle={styles.chipRowContent}
            >
              {filteredModels.map((model) => (
                <MakeChip
                  key={model.id}
                  label={model.name}
                  selected={selectedModelId === model.id}
                  onPress={() => handleModelSelect(model.id)}
                  colors={colors}
                />
              ))}
            </ScrollView>
          </>
        )}

        {/* Year picker — shown after make selected */}
        {selectedMakeId !== null && availableYears.length > 0 && (
          <>
            <Text style={[styles.pickerLabel, { color: colors.textMuted }]}>Year</Text>
            <ScrollView
              horizontal
              showsHorizontalScrollIndicator={false}
              style={styles.chipRow}
              contentContainerStyle={styles.chipRowContent}
            >
              {availableYears.map((year) => (
                <MakeChip
                  key={String(year)}
                  label={String(year)}
                  selected={selectedYear === year}
                  onPress={() => handleYearSelect(year)}
                  colors={colors}
                />
              ))}
            </ScrollView>
          </>
        )}

        {/* Trim picker — shown after model selected */}
        {selectedModelId !== null && trimsForModel.length > 0 && (
          <>
            <Text style={[styles.pickerLabel, { color: colors.textMuted }]}>Trim</Text>
            <ScrollView
              horizontal
              showsHorizontalScrollIndicator={false}
              style={styles.chipRow}
              contentContainerStyle={styles.chipRowContent}
            >
              {trimsForModel.map((trim) => (
                <MakeChip
                  key={trim.id}
                  label={trim.name}
                  selected={selectedTrimId === trim.id}
                  onPress={() => handleTrimSelect(trim.id)}
                  colors={colors}
                />
              ))}
            </ScrollView>
          </>
        )}

        {/* ── Section 3: Trim Highlights ────────────────────────────────── */}
        {selectedTrimId !== null && highlightFeatures.length > 0 && (
          <>
            <Text style={[styles.sectionTitle, { color: colors.textSecondary }]}>
              TRIM HIGHLIGHTS
            </Text>
            <View style={styles.featurePills}>
              {highlightFeatures.map((feature) => (
                <View
                  key={feature.id}
                  style={[styles.featurePill, { backgroundColor: colors.accent }]}
                >
                  <Text style={styles.featurePillText} numberOfLines={1}>
                    {feature.display_name}
                  </Text>
                </View>
              ))}
            </View>
          </>
        )}

        {/* Bottom spacer so last content isn't hidden behind footer */}
        <View style={{ height: 24 }} />
      </ScrollView>

      {/* ── Fixed footer: Start Session button ──────────────────────────── */}
      <View
        style={[
          styles.footer,
          {
            borderTopColor: colors.border,
            paddingBottom: Math.max(insets.bottom, 16),
          },
        ]}
      >
        <TouchableOpacity
          style={[
            styles.startButton,
            {
              backgroundColor: canStart ? colors.accent : colors.border,
            },
          ]}
          onPress={handleStartSession}
          disabled={!canStart}
          activeOpacity={0.8}
          accessibilityRole="button"
          accessibilityLabel="Start Session"
          accessibilityState={{ disabled: !canStart }}
        >
          {loading ? (
            <ActivityIndicator color="#FFFFFF" size="small" />
          ) : (
            <Text
              style={[
                styles.startButtonText,
                { color: canStart ? '#FFFFFF' : colors.textMuted },
              ]}
            >
              Start Session
            </Text>
          )}
        </TouchableOpacity>
      </View>
    </KeyboardAvoidingView>
  );
}

// ─── Chip sub-component ───────────────────────────────────────────────────────

interface ChipProps {
  label: string;
  selected: boolean;
  onPress: () => void;
  colors: ReturnType<typeof import('../theme').useTheme>['colors'];
}

function MakeChip({ label, selected, onPress, colors }: ChipProps) {
  return (
    <TouchableOpacity
      style={[
        styles.chip,
        {
          backgroundColor: selected ? colors.accent : colors.surface,
          borderColor: selected ? colors.accent : colors.border,
        },
      ]}
      onPress={onPress}
      activeOpacity={0.7}
      accessibilityRole="button"
      accessibilityState={{ selected }}
      accessibilityLabel={label}
    >
      <Text
        style={[
          styles.chipText,
          { color: selected ? '#FFFFFF' : colors.textSecondary },
        ]}
        numberOfLines={1}
      >
        {label}
      </Text>
    </TouchableOpacity>
  );
}

// ─── Styles ───────────────────────────────────────────────────────────────────

const styles = StyleSheet.create({
  root: {
    flex: 1,
  },
  scroll: {
    flex: 1,
  },
  scrollContent: {
    paddingHorizontal: 16,
    paddingTop: 16,
  },

  // Appointment card
  appointmentCard: {
    borderRadius: 12,
    borderWidth: 1,
    padding: 16,
    marginBottom: 24,
  },
  appointmentAuditName: {
    ...Typography.h3,
    marginBottom: 8,
  },
  appointmentRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    marginBottom: 4,
  },
  appointmentMeta: {
    ...Typography.caption,
  },
  statusChip: {
    borderRadius: 6,
    paddingHorizontal: 8,
    paddingVertical: 3,
  },
  statusChipText: {
    ...Typography.captionMedium,
  },

  // Section headers
  sectionTitle: {
    ...Typography.label,
    marginBottom: 12,
    marginTop: 8,
  },

  // Text inputs
  textInput: {
    height: 52,
    borderWidth: 1,
    borderRadius: 8,
    paddingHorizontal: 12,
    marginBottom: 12,
    ...Typography.body,
  },

  // Picker
  pickerLabel: {
    ...Typography.caption,
    marginBottom: 6,
  },
  chipRow: {
    marginBottom: 16,
  },
  chipRowContent: {
    paddingRight: 16,
  },
  chip: {
    height: 44,
    borderRadius: 22,
    borderWidth: 1,
    paddingHorizontal: 16,
    justifyContent: 'center',
    alignItems: 'center',
    marginRight: 8,
  },
  chipText: {
    ...Typography.bodyMedium,
  },
  emptyPicker: {
    borderRadius: 8,
    borderWidth: 1,
    padding: 16,
    marginBottom: 16,
    alignItems: 'center',
  },

  // Feature pills
  featurePills: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    marginBottom: 8,
  },
  featurePill: {
    borderRadius: 12,
    paddingHorizontal: 8,
    paddingVertical: 4,
    marginRight: 6,
    marginBottom: 6,
  },
  featurePillText: {
    ...Typography.caption,
    color: '#FFFFFF',
    fontWeight: '500',
  },

  // Footer
  footer: {
    borderTopWidth: StyleSheet.hairlineWidth,
    paddingHorizontal: 24,
    paddingTop: 12,
  },
  startButton: {
    height: 52,
    borderRadius: 10,
    justifyContent: 'center',
    alignItems: 'center',
  },
  startButtonText: {
    ...Typography.buttonLarge,
    fontWeight: '700',
  },
});
