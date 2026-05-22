import React, { useEffect } from 'react';
import { Text, Pressable, StyleSheet } from 'react-native';
import Animated, {
  useSharedValue,
  useAnimatedStyle,
  withSequence,
  withTiming,
} from 'react-native-reanimated';
import { colors } from '../theme';

type Props = {
  label: string;
  checked: boolean;
  onToggle: () => void;
  glowKey?: string | number;
};

export const CheckboxRow: React.FC<Props> = ({ label, checked, onToggle, glowKey }) => {
  const glow = useSharedValue(0);

  useEffect(() => {
    if (checked && glowKey !== undefined) {
      glow.value = withSequence(withTiming(1, { duration: 50 }), withTiming(0, { duration: 450 }));
    }
  }, [checked, glowKey, glow]);

  const glowStyle = useAnimatedStyle(() => ({
    shadowOpacity: glow.value * 0.6,
    shadowRadius: 8 * glow.value,
  }));

  return (
    <Pressable style={styles.row} onPress={onToggle}>
      <Animated.View
        style={[
          styles.box,
          checked && styles.boxChecked,
          glowStyle,
          { shadowColor: colors.success, shadowOffset: { width: 0, height: 0 } },
        ]}
      >
        {checked ? <Text style={styles.tick}>✓</Text> : null}
      </Animated.View>
      <Text style={styles.label}>{label}</Text>
    </Pressable>
  );
};

const styles = StyleSheet.create({
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
    backgroundColor: colors.cardBg,
    borderRadius: 10,
    paddingHorizontal: 12,
    paddingVertical: 8,
    shadowColor: '#000',
    shadowOpacity: 0.05,
    shadowRadius: 3,
    shadowOffset: { width: 0, height: 1 },
    elevation: 1,
  },
  box: {
    width: 18,
    height: 18,
    borderRadius: 4,
    borderWidth: 2,
    borderColor: '#d0d0dc',
    alignItems: 'center',
    justifyContent: 'center',
  },
  boxChecked: { backgroundColor: colors.primary, borderColor: colors.primary },
  tick: { fontSize: 10, fontWeight: '800', color: '#fff' },
  label: { fontSize: 12, color: '#000' },
});
