import React, { useEffect } from 'react';
import { View, Text, Pressable, StyleSheet, Image } from 'react-native';
import Animated, {
  useSharedValue,
  useAnimatedStyle,
  withSequence,
  withTiming,
} from 'react-native-reanimated';
import { colors } from '../theme';

type Props = {
  label: string;
  photoUri: string | null;
  emoji?: string;
  onCapture: () => void;
  flashKey?: string | number;
};

export const PhotoSlot: React.FC<Props> = ({
  label,
  photoUri,
  emoji = '🌍',
  onCapture,
  flashKey,
}) => {
  const flash = useSharedValue(0);

  useEffect(() => {
    if (flashKey !== undefined && flashKey !== '' && flashKey !== 0) {
      flash.value = withSequence(
        withTiming(0.95, { duration: 100 }),
        withTiming(0, { duration: 250 })
      );
    }
  }, [flashKey, flash]);

  const flashStyle = useAnimatedStyle(() => ({ opacity: flash.value }));

  return (
    <Pressable style={[styles.pp, photoUri && styles.filled]} onPress={onCapture}>
      {photoUri ? (
        <View style={styles.fill}>
          {photoUri.startsWith('data:') ? (
            <Text style={styles.placeholderEmoji}>{emoji}</Text>
          ) : (
            <Image source={{ uri: photoUri }} style={StyleSheet.absoluteFill} />
          )}
          <Text style={styles.captured}>Captured ✓</Text>
        </View>
      ) : (
        <>
          <Text style={styles.icon}>📷</Text>
          <Text style={styles.label}>{label}</Text>
        </>
      )}
      <Animated.View style={[styles.flash, flashStyle]} pointerEvents="none" />
    </Pressable>
  );
};

const styles = StyleSheet.create({
  pp: {
    flex: 1,
    aspectRatio: 1,
    backgroundColor: colors.cardBg,
    borderRadius: 10,
    borderWidth: 1.5,
    borderColor: colors.borderDashed,
    borderStyle: 'dashed',
    alignItems: 'center',
    justifyContent: 'center',
    overflow: 'hidden',
    position: 'relative',
  },
  filled: { borderStyle: 'solid', borderColor: colors.border },
  icon: { fontSize: 20, marginBottom: 4 },
  label: { fontSize: 10, color: colors.textSubtle, textAlign: 'center' },
  fill: {
    ...StyleSheet.absoluteFillObject,
    backgroundColor: '#e8f0e8',
    alignItems: 'center',
    justifyContent: 'center',
  },
  placeholderEmoji: { fontSize: 28 },
  captured: { fontSize: 9, color: colors.success, fontWeight: '600', marginTop: 4 },
  flash: { ...StyleSheet.absoluteFillObject, backgroundColor: '#fff' },
});
