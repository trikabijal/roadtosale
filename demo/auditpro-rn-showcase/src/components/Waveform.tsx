import React, { useEffect } from 'react';
import { View, StyleSheet } from 'react-native';
import Animated, {
  useSharedValue,
  useAnimatedStyle,
  withRepeat,
  withTiming,
  withDelay,
  Easing,
  interpolate,
} from 'react-native-reanimated';
import { colors } from '../theme';

const HEIGHTS = [5, 13, 9, 17, 7, 14, 11, 5];
const DELAYS = [0, 100, 200, 150, 300, 50, 250, 350];

type Props = {
  size?: 'normal' | 'mini';
};

const Bar: React.FC<{ height: number; delay: number; mini?: boolean }> = ({
  height,
  delay,
  mini,
}) => {
  const v = useSharedValue(1);
  useEffect(() => {
    v.value = withDelay(
      delay,
      withRepeat(
        withTiming(2, { duration: 500, easing: Easing.inOut(Easing.ease) }),
        -1,
        true
      )
    );
  }, [delay, v]);

  const style = useAnimatedStyle(() => ({
    transform: [{ scaleY: v.value }],
    opacity: interpolate(v.value, [1, 2], [0.35, 0.9]),
  }));

  return (
    <Animated.View
      style={[
        {
          width: mini ? 2 : 3,
          height: mini ? Math.round(height * 0.7) : height,
          borderRadius: 2,
          backgroundColor: colors.primary,
          opacity: 0.5,
        },
        style,
      ]}
    />
  );
};

export const Waveform: React.FC<Props> = ({ size = 'normal' }) => {
  const mini = size === 'mini';
  const bars = mini ? HEIGHTS.slice(0, 4) : HEIGHTS;
  const delays = mini ? DELAYS.slice(0, 4) : DELAYS;
  return (
    <View style={[styles.row, mini ? styles.miniRow : styles.normalRow]}>
      {bars.map((h, i) => (
        <Bar key={i} height={h} delay={delays[i]} mini={mini} />
      ))}
    </View>
  );
};

const styles = StyleSheet.create({
  row: {
    flexDirection: 'row',
    alignItems: 'center',
  },
  normalRow: { gap: 2, height: 18 },
  miniRow: { gap: 1, height: 12 },
});
