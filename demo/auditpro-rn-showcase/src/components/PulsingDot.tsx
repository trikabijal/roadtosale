import React, { useEffect } from 'react';
import Animated, {
  useSharedValue,
  useAnimatedStyle,
  withRepeat,
  withTiming,
  Easing,
} from 'react-native-reanimated';

type Props = {
  size?: number;
  color?: string;
};

export const PulsingDot: React.FC<Props> = ({ size = 7, color = '#E02525' }) => {
  const v = useSharedValue(1);
  useEffect(() => {
    v.value = withRepeat(
      withTiming(0.5, { duration: 600, easing: Easing.inOut(Easing.ease) }),
      -1,
      true
    );
  }, [v]);

  const style = useAnimatedStyle(() => ({
    opacity: v.value,
    transform: [{ scale: 0.5 + v.value * 0.5 }],
  }));

  return (
    <Animated.View
      style={[
        {
          width: size,
          height: size,
          borderRadius: size / 2,
          backgroundColor: color,
        },
        style,
      ]}
    />
  );
};
