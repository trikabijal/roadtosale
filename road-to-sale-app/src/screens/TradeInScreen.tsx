import React, { useCallback, useEffect, useRef, useState } from 'react';
import {
  ActivityIndicator,
  Alert,
  Dimensions,
  FlatList,
  Image,
  Modal,
  Platform,
  StyleSheet,
  Text,
  TextInput,
  TouchableOpacity,
  View,
} from 'react-native';
import { CameraView, useCameraPermissions } from 'expo-camera';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

import type { TradeInScreenProps } from '../navigation/types';
import { useTheme } from '../theme';
import { Typography } from '../theme';
import { getSmartComplyClient } from '../api/clientSingleton';
import { getSessionEngine } from '../session/sessionEngineSingleton';
import { getVoiceEngine } from '../voice/NativeVoiceModule';
import { getSessionRepository } from '../db/repositorySingleton';
import type { TradePhotoSlot } from '../api/types';
import type { TranscriptEvent } from '../voice/types';
import type { DetectedCue } from '../session/types';

// ─── Slot definitions ─────────────────────────────────────────────────────────

interface PhotoSlot {
  id: string;
  label: string;
  apiSlot: TradePhotoSlot;
}

// DC68: Mapping from display slot IDs to TradePhotoSlot API enum values.
// API has 7 slots; display slots differ in naming. Mapping chosen by closest
// semantic match. 'engine' maps to 'interior' (no engine slot in v1 API).
const SLOTS: PhotoSlot[] = [
  { id: 'front',            label: 'Front',          apiSlot: 'front_left' },
  { id: 'rear',             label: 'Rear',           apiSlot: 'rear_left' },
  { id: 'driver_side',      label: 'Driver Side',    apiSlot: 'front_right' },
  { id: 'passenger_side',   label: 'Passenger Side', apiSlot: 'rear_right' },
  { id: 'engine',           label: 'Engine',         apiSlot: 'interior' },
  { id: 'odometer',         label: 'Odometer',       apiSlot: 'odometer' },
  { id: 'vin',              label: 'VIN Plate',      apiSlot: 'vin' },
];

const TOTAL_SLOTS = SLOTS.length;  // 7
const TOTAL_QUESTIONS = 16;  // used in completion chip in HistoryScreen; kept here for consistency
const NOTE_DEBOUNCE_MS = 500;

// ─── Component ────────────────────────────────────────────────────────────────

export default function TradeInScreen({ navigation, route }: TradeInScreenProps) {
  const { colors } = useTheme();
  const insets = useSafeAreaInsets();
  const { sessionId } = route.params;

  const [permission, requestPermission] = useCameraPermissions();

  // photos: slotId → local uri
  const [photos, setPhotos] = useState<Record<string, string>>({});
  const [conditionNote, setConditionNote] = useState('');

  // ── Task 7.4: voice snippet state ─────────────────────────────────────────
  // Rolling buffer of the last 3 final transcripts. Most recent first.
  const [voiceSnippets, setVoiceSnippets] = useState<DetectedCue[]>([]);

  // Camera modal state
  const [cameraVisible, setCameraVisible] = useState(false);
  const [activeSlotId, setActiveSlotId] = useState<string | null>(null);
  const [capturing, setCapturing] = useState(false);

  const cameraRef = useRef<CameraView>(null);

  // ── Task 7.7: refs for debounced note persist and latest state snapshots ──
  const noteDebounceRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  // Keep refs to current values so async persist callbacks always see fresh data
  const photosRef = useRef<Record<string, string>>(photos);
  const voiceSnippetsRef = useRef<DetectedCue[]>(voiceSnippets);
  const conditionNoteRef = useRef<string>(conditionNote);

  // Sync refs on every render
  photosRef.current = photos;
  voiceSnippetsRef.current = voiceSnippets;
  conditionNoteRef.current = conditionNote;

  const screenWidth = Dimensions.get('window').width;
  const cardSize = (screenWidth - 48) / 2;   // 16 padding each side + 16 gap

  // ── Task 7.6: derived capture count ──────────────────────────────────────
  const capturedCount = Object.keys(photos).length;
  const isComplete = capturedCount === TOTAL_SLOTS;

  // ── Task 7.7: persist helper ─────────────────────────────────────────────

  const persistTradeIn = useCallback(async (
    currentPhotos: Record<string, string>,
    currentSnippets: DetectedCue[],
    currentNote: string,
  ): Promise<void> => {
    try {
      const photosNullable: Record<string, string | null> = {};
      for (const slot of SLOTS) {
        photosNullable[slot.id] = currentPhotos[slot.id] ?? null;
      }
      await getSessionRepository().updateSession({
        id: sessionId,
        tradeIn: {
          photos: photosNullable,
          spokenNotes: currentSnippets,
          typedNote: currentNote,
        },
      });
    } catch (e) {
      console.warn('[TradeIn] Failed to persist trade-in state:', e);
    }
  }, [sessionId]);

  // ── Task 7.4 & 7.7: voice subscription on mount, unsubscribe on unmount ──

  useEffect(() => {
    const voice = getVoiceEngine();

    const unsub = voice.onTranscript((event: TranscriptEvent) => {
      // Only collect final stability events
      if (event.stability !== 'final') return;

      const cue: DetectedCue = {
        cueId: `trade_in_voice_${event.timestamp_ms}`,
        cueSource: 'feature',
        transcriptSnippet: event.text,
        confidence: event.confidence,
        detectedAtMs: event.timestamp_ms,
      };

      setVoiceSnippets((prev) => {
        // Rolling buffer: most recent first, max 3 entries
        const next = [cue, ...prev].slice(0, 3);
        // Persist async after state update (use ref snapshot for photos/note)
        persistTradeIn(photosRef.current, next, conditionNoteRef.current);
        return next;
      });
    });

    return () => {
      unsub();
    };
  }, [persistTradeIn]);

  // ── Task 7.7: debounced persist on condition note change ─────────────────

  const handleConditionNoteChange = useCallback((text: string) => {
    setConditionNote(text);
    if (noteDebounceRef.current !== null) {
      clearTimeout(noteDebounceRef.current);
    }
    noteDebounceRef.current = setTimeout(() => {
      persistTradeIn(photosRef.current, voiceSnippetsRef.current, text);
      noteDebounceRef.current = null;
    }, NOTE_DEBOUNCE_MS);
  }, [persistTradeIn]);

  // ── Task 7.7: final persist on unmount ───────────────────────────────────

  useEffect(() => {
    return () => {
      // Cancel any pending debounce and do a final flush
      if (noteDebounceRef.current !== null) {
        clearTimeout(noteDebounceRef.current);
        noteDebounceRef.current = null;
      }
      persistTradeIn(photosRef.current, voiceSnippetsRef.current, conditionNoteRef.current);
    };
  // eslint-disable-next-line react-hooks/exhaustive-deps — intentionally runs only on unmount
  }, []);

  // ── Open camera for a slot ────────────────────────────────────────────────

  const handleSlotPress = useCallback(async (slotId: string) => {
    if (!permission?.granted) {
      const result = await requestPermission();
      if (!result.granted) return;
    }
    setActiveSlotId(slotId);
    setCameraVisible(true);
  }, [permission, requestPermission]);

  // ── Capture photo ─────────────────────────────────────────────────────────

  const handleCapture = useCallback(async () => {
    if (!cameraRef.current || capturing || !activeSlotId) return;
    setCapturing(true);
    try {
      const photo = await cameraRef.current.takePictureAsync({ quality: 0.7, base64: false });
      if (!photo) return;

      const uri = photo.uri;
      const slotId = activeSlotId;

      setPhotos((prev) => {
        const next = { ...prev, [slotId]: uri };
        // Task 7.7: persist immediately after capture
        persistTradeIn(next, voiceSnippetsRef.current, conditionNoteRef.current);
        return next;
      });

      setCameraVisible(false);
      setActiveSlotId(null);

      // Background upload — do not await; silent failure
      const slot = SLOTS.find((s) => s.id === slotId);
      if (slot) {
        uploadPhotoInBackground(sessionId, slot.apiSlot, uri);
      }
    } catch (e) {
      console.warn('[TradeIn] Capture failed:', e);
      Alert.alert('Capture Failed', 'Could not take photo. Please try again.');
    } finally {
      setCapturing(false);
    }
  }, [capturing, activeSlotId, sessionId, persistTradeIn]);

  // ── Background upload ─────────────────────────────────────────────────────

  function uploadPhotoInBackground(
    sid: string,
    apiSlot: TradePhotoSlot,
    localUri: string,
  ): void {
    const session = getSessionEngine().getSession(sid);
    if (!session?.smartComplyUserChecksheetId) return;
    const ucId = session.smartComplyUserChecksheetId;
    getSmartComplyClient()
      .uploadTradePhoto(ucId, apiSlot, localUri, 'image/jpeg')
      .catch((e: unknown) => {
        console.warn('[TradeIn] Upload failed:', e);
      });
  }

  // ── Done ──────────────────────────────────────────────────────────────────

  const handleDone = useCallback(async () => {
    // Task 7.7: final persist before leaving the screen
    await persistTradeIn(photosRef.current, voiceSnippetsRef.current, conditionNoteRef.current);
    navigation.goBack();
  }, [navigation, persistTradeIn]);

  // ── Permission denied state ───────────────────────────────────────────────

  if (permission !== null && !permission.granted) {
    return (
      <View style={[styles.centeredFull, { backgroundColor: colors.background }]}>
        <Text style={[Typography.h3, { color: colors.textPrimary, textAlign: 'center', marginBottom: 12 }]}>
          Camera Access Required
        </Text>
        <Text style={[Typography.body, { color: colors.textSecondary, textAlign: 'center', marginBottom: 24 }]}>
          Allow camera access to capture trade-in photos.
        </Text>
        <TouchableOpacity
          style={[styles.permissionButton, { backgroundColor: colors.accent }]}
          onPress={requestPermission}
          activeOpacity={0.8}
          accessibilityRole="button"
          accessibilityLabel="Grant Camera Access"
        >
          <Text style={[Typography.buttonLarge, { color: '#FFFFFF' }]}>Grant Camera Access</Text>
        </TouchableOpacity>
      </View>
    );
  }

  // ── Main render ───────────────────────────────────────────────────────────

  return (
    <View style={[styles.container, { backgroundColor: colors.background }]}>

      {/* ── Task 7.6: Photo completeness banner ───────────────────────────── */}
      <View
        style={[
          styles.completionBanner,
          {
            backgroundColor: isComplete ? colors.success : colors.surface,
            borderColor: isComplete ? colors.success : colors.border,
          },
        ]}
        accessibilityLabel={isComplete ? 'Trade-In Complete' : `${capturedCount} of ${TOTAL_SLOTS} photos captured`}
      >
        <Text
          style={[
            Typography.label,
            {
              color: isComplete ? '#FFFFFF' : colors.textSecondary,
              textAlign: 'center',
            },
          ]}
        >
          {isComplete
            ? '✅ Trade-In Complete'
            : `${capturedCount} / ${TOTAL_SLOTS} photos captured`}
        </Text>
      </View>

      {/* ── Photo grid ────────────────────────────────────────────────────── */}
      <FlatList
        data={SLOTS}
        keyExtractor={(item) => item.id}
        numColumns={2}
        columnWrapperStyle={styles.columnWrapper}
        contentContainerStyle={[styles.gridContent, { paddingBottom: insets.bottom + 100 }]}
        renderItem={({ item }) => {
          const uri = photos[item.id] ?? null;
          return (
            <PhotoSlotCard
              slot={item}
              uri={uri}
              cardSize={cardSize}
              colors={colors}
              onPress={() => handleSlotPress(item.id)}
            />
          );
        }}
        ListFooterComponent={
          /* Condition notes — rendered inside the list so it scrolls with the grid */
          <View style={styles.footerContent}>

            {/* ── Task 7.4: Voice Notes panel ─────────────────────────────── */}
            {voiceSnippets.length > 0 && (
              <View
                style={[
                  styles.voicePanel,
                  { backgroundColor: colors.surface, borderColor: colors.border },
                ]}
                accessibilityLabel="Voice notes panel"
              >
                <Text style={[Typography.label, { color: colors.textSecondary, marginBottom: 6 }]}>
                  🎤 Voice Notes
                </Text>
                {voiceSnippets.map((snippet, index) => (
                  <Text
                    key={`${snippet.detectedAtMs}-${index}`}
                    style={[
                      Typography.body,
                      styles.voiceSnippetRow,
                      {
                        color: colors.textPrimary,
                        borderTopColor: index > 0 ? colors.border : 'transparent',
                      },
                    ]}
                    numberOfLines={2}
                  >
                    {snippet.transcriptSnippet}
                  </Text>
                ))}
              </View>
            )}

            {/* ── Condition notes input ────────────────────────────────────── */}
            <View style={styles.notesSection}>
              <Text style={[Typography.label, { color: colors.textSecondary, marginBottom: 8 }]}>
                Condition Notes
              </Text>
              <TextInput
                style={[
                  styles.notesInput,
                  {
                    backgroundColor: colors.surface,
                    borderColor: colors.border,
                    color: colors.textPrimary,
                  },
                ]}
                multiline
                numberOfLines={4}
                placeholder="Describe any damage, missing items, or special notes..."
                placeholderTextColor={colors.textMuted}
                value={conditionNote}
                onChangeText={handleConditionNoteChange}
                textAlignVertical="top"
                accessibilityLabel="Condition notes"
              />
            </View>
          </View>
        }
      />

      {/* ── Footer: Done button ───────────────────────────────────────────── */}
      <View
        style={[
          styles.footer,
          {
            borderTopColor: colors.border,
            paddingBottom: insets.bottom + 8,
            backgroundColor: colors.background,
          },
        ]}
      >
        <TouchableOpacity
          style={[styles.doneButton, { backgroundColor: colors.accent }]}
          onPress={handleDone}
          activeOpacity={0.8}
          accessibilityRole="button"
          accessibilityLabel="Done"
        >
          <Text style={[Typography.buttonLarge, { color: '#FFFFFF' }]}>Done</Text>
        </TouchableOpacity>
      </View>

      {/* ── Camera Modal ──────────────────────────────────────────────────── */}
      <Modal
        visible={cameraVisible}
        animationType="slide"
        statusBarTranslucent
        onRequestClose={() => {
          setCameraVisible(false);
          setActiveSlotId(null);
        }}
      >
        <View style={styles.cameraContainer}>
          <CameraView
            ref={cameraRef}
            style={StyleSheet.absoluteFill}
            facing="back"
          />

          {/* Cancel button */}
          <TouchableOpacity
            style={[styles.cancelButton, { top: insets.top + 12 }]}
            onPress={() => {
              setCameraVisible(false);
              setActiveSlotId(null);
            }}
            activeOpacity={0.8}
            accessibilityRole="button"
            accessibilityLabel="Cancel"
          >
            <Text style={styles.cancelButtonText}>✕</Text>
          </TouchableOpacity>

          {/* Slot label */}
          {activeSlotId !== null && (
            <View style={[styles.slotLabelBadge, { top: insets.top + 12 }]}>
              <Text style={styles.slotLabelText}>
                {SLOTS.find((s) => s.id === activeSlotId)?.label ?? ''}
              </Text>
            </View>
          )}

          {/* Shutter button */}
          <View style={[styles.shutterRow, { paddingBottom: insets.bottom + 32 }]}>
            <TouchableOpacity
              style={styles.shutterButton}
              onPress={handleCapture}
              disabled={capturing}
              activeOpacity={0.85}
              accessibilityRole="button"
              accessibilityLabel="Take photo"
            >
              {capturing ? (
                <ActivityIndicator color="#000000" size="small" />
              ) : (
                <View style={styles.shutterInner} />
              )}
            </TouchableOpacity>
          </View>
        </View>
      </Modal>
    </View>
  );
}

// ─── PhotoSlotCard ────────────────────────────────────────────────────────────

interface PhotoSlotCardProps {
  slot: PhotoSlot;
  uri: string | null;
  cardSize: number;
  colors: ReturnType<typeof import('../theme').useTheme>['colors'];
  onPress: () => void;
}

function PhotoSlotCard({ slot, uri, cardSize, colors, onPress }: PhotoSlotCardProps) {
  const cardStyle = {
    width: cardSize,
    height: cardSize,
    backgroundColor: uri ? colors.photoCaptured : colors.photoNeeded,
  };

  return (
    <TouchableOpacity
      style={[styles.slotCard, cardStyle, { borderColor: colors.border }]}
      onPress={onPress}
      activeOpacity={0.75}
      accessibilityRole="button"
      accessibilityLabel={`Capture ${slot.label} photo${uri ? ' (captured)' : ''}`}
    >
      {uri ? (
        <Image
          source={{ uri }}
          style={[styles.slotImage, { width: cardSize, height: cardSize - 28 }]}
          resizeMode="cover"
        />
      ) : (
        <Text style={styles.cameraIcon}>🎥</Text>
      )}
      <Text
        style={[
          styles.slotLabel,
          {
            color: uri ? colors.textPrimary : colors.textSecondary,
          },
        ]}
        numberOfLines={1}
      >
        {slot.label}
      </Text>
    </TouchableOpacity>
  );
}

// ─── Styles ───────────────────────────────────────────────────────────────────

const styles = StyleSheet.create({
  container: {
    flex: 1,
  },
  centeredFull: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
    padding: 32,
  },
  permissionButton: {
    height: 52,
    paddingHorizontal: 32,
    borderRadius: 10,
    justifyContent: 'center',
    alignItems: 'center',
  },

  // Task 7.6: Completion banner
  completionBanner: {
    marginHorizontal: 16,
    marginTop: 12,
    marginBottom: 4,
    paddingVertical: 8,
    paddingHorizontal: 12,
    borderRadius: 8,
    borderWidth: 1,
  },

  // Grid
  gridContent: {
    padding: 16,
    gap: 0,
  },
  columnWrapper: {
    justifyContent: 'space-between',
    marginBottom: 16,
  },

  // Slot card
  slotCard: {
    borderRadius: 10,
    borderWidth: 1,
    overflow: 'hidden',
    alignItems: 'center',
    justifyContent: 'flex-end',
  },
  slotImage: {
    position: 'absolute',
    top: 0,
    left: 0,
    right: 0,
  },
  cameraIcon: {
    fontSize: 32,
    marginBottom: 4,
  },
  slotLabel: {
    ...Typography.captionMedium,
    paddingVertical: 6,
    paddingHorizontal: 4,
    textAlign: 'center',
    backgroundColor: 'rgba(0,0,0,0.35)',
    width: '100%',
  },

  // Footer content wrapper (scrolls with list)
  footerContent: {
    marginTop: 4,
  },

  // Task 7.4: Voice Notes panel
  voicePanel: {
    borderWidth: 1,
    borderRadius: 8,
    padding: 10,
    marginBottom: 12,
  },
  voiceSnippetRow: {
    paddingVertical: 5,
    borderTopWidth: StyleSheet.hairlineWidth,
  },

  // Condition notes
  notesSection: {
    marginTop: 0,
  },
  notesInput: {
    borderWidth: 1,
    borderRadius: 8,
    paddingHorizontal: 12,
    paddingTop: 10,
    paddingBottom: 10,
    minHeight: 96,
    ...Typography.body,
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
  },
  doneButton: {
    height: 52,
    borderRadius: 10,
    justifyContent: 'center',
    alignItems: 'center',
  },

  // Camera modal
  cameraContainer: {
    flex: 1,
    backgroundColor: '#000000',
  },
  cancelButton: {
    position: 'absolute',
    left: 16,
    width: 40,
    height: 40,
    borderRadius: 20,
    backgroundColor: 'rgba(0,0,0,0.55)',
    justifyContent: 'center',
    alignItems: 'center',
    zIndex: 10,
  },
  cancelButtonText: {
    color: '#FFFFFF',
    fontSize: 18,
    fontWeight: '600',
  },
  slotLabelBadge: {
    position: 'absolute',
    alignSelf: 'center',
    backgroundColor: 'rgba(0,0,0,0.55)',
    paddingVertical: 4,
    paddingHorizontal: 14,
    borderRadius: 12,
    zIndex: 10,
  },
  slotLabelText: {
    color: '#FFFFFF',
    ...Typography.captionMedium,
  },
  shutterRow: {
    position: 'absolute',
    bottom: 0,
    left: 0,
    right: 0,
    alignItems: 'center',
  },
  shutterButton: {
    width: 72,
    height: 72,
    borderRadius: 36,
    backgroundColor: '#FFFFFF',
    justifyContent: 'center',
    alignItems: 'center',
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 2 },
    shadowOpacity: 0.3,
    shadowRadius: 4,
    elevation: 6,
  },
  shutterInner: {
    width: 58,
    height: 58,
    borderRadius: 29,
    backgroundColor: '#FFFFFF',
    borderWidth: 2,
    borderColor: '#CCCCCC',
  },
});
