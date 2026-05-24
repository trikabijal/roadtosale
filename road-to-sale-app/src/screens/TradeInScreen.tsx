import React from 'react';
import { View, Text, StyleSheet } from 'react-native';
import type { TradeInScreenProps } from '../navigation/types';
import { useTheme } from '../theme';

export default function TradeInScreen({ navigation, route }: TradeInScreenProps) {
  const { colors } = useTheme();
  const { sessionId } = route.params;
  return (
    <View style={[styles.container, { backgroundColor: colors.background }]}>
      <Text style={[styles.title, { color: colors.textPrimary }]}>Trade-In</Text>
      <Text style={[styles.subtitle, { color: colors.textSecondary }]}>
        Capture trade-in photos for session {sessionId}
      </Text>
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, justifyContent: 'center', alignItems: 'center', padding: 24 },
  title: { fontSize: 28, fontWeight: '700', marginBottom: 8 },
  subtitle: { fontSize: 16 },
});
