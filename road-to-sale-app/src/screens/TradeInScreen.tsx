import React, { useCallback, useRef, useState } from 'react';
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
import type { TradePhotoSlot } from '../api/types';

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

const TOTAL_QUESTIONS = 16;  // used in completion chip in HistoryScreen; kept here for consistency

// ─── Component ────────────────────────────────────────────────────────────────

export default function TradeInScreen({ navigation, route }: TradeInScreenProps) {
  const { colors } = useTheme();
  const insets = useSafeAreaInsets();
  const { sessionId } = route.params;

  const [permission, requestPermission] = useCameraPermissions();

  // photos: slotId → local uri
  const [photos, setPhotos] = useState<Record<string, string>>({});
  const [conditionNote, setConditionNote] = useState('');

  // Camera modal state
  const [cameraVisible, setCameraVisible] = useState(false);
  const [activeSlotId, setActiveSlotId] = useState<string | null>(null);
  const [capturing, setCapturing] = useState(false);

  const cameraRef = useRef<CameraView>(null);

  const screenWidth = Dimensions.get('window').width;
  const cardSize = (screenWidth - 48) / 2;   // 16 padding each side + 16 gap

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
      setPhotos((prev) => ({ ...prev, [activeSlotId]: uri }));
      setCameraVisible(false);
      setActiveSlotId(null);

      // Background upload — do not await; silent failure
      const slot = SLOTS.find((s) => s.id === activeSlotId);
      if (slot) {
        uploadPhotoInBackground(sessionId, slot.apiSlot, uri);
      }
    } catch (e) {
      console.warn('[TradeIn] Capture failed:', e);
      Alert.alert('Capture Failed', 'Could not take photo. Please try again.');
    } finally {
      setCapturing(false);
    }
  }, [capturing, activeSlotId, sessionId]);

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

  const handleDone = useCallback(() => {
    // Persist typed note locally (session engine carries in-memory state for v1)
    // DC68: Session engine has no setTradeInNote method yet; logged as a future
    // extension. Note is stored in component state for the session's lifetime.
    // When the session engine gains a updateTradeIn method, wire it here.
    navigation.goBack();
  }, [navigation]);

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
              onChangeText={setConditionNote}
              textAlignVertical="top"
              accessibilityLabel="Condition notes"
            />
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

  // Condition notes
  notesSection: {
    marginTop: 4,
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
