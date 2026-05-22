import React, { useEffect } from 'react';
import { View, StyleSheet } from 'react-native';
import Animated, {
  useSharedValue,
  useAnimatedStyle,
  withTiming,
  Easing,
} from 'react-native-reanimated';
import { colors } from '../theme';

type Props = { percent: number };

export const ProgressBar: React.FC<Props> = ({ percent }) => {
  const w = useSharedValue(0);
  useEffect(() => {
    w.value = withTiming(percent, { duration: 450, easing: Easing.bezier(0.4, 0, 0.2, 1) });
  }, [percent, w]);

  const style = useAnimatedStyle(() => ({ width: `${w.value}%` }));

  return (
    <View style={styles.bar}>
      <Animated.View style={[styles.fill, style]} />
    </View>
  );
};

const styles = StyleSheet.create({
  bar: { height: 2, backgroundColor: '#e5e5ea', borderRadius: 2, overflow: 'hidden' },
  fill: { height: '100%', backgroundColor: colors.primary, borderRadius: 2 },
});
