import React from 'react';
import { View, Text, StyleSheet } from 'react-native';
import type { SessionSummaryScreenProps } from '../navigation/types';
import { useTheme } from '../theme';

export default function SessionSummaryScreen({ navigation, route }: SessionSummaryScreenProps) {
  const { colors } = useTheme();
  const { sessionId, readOnly } = route.params;
  return (
    <View style={[styles.container, { backgroundColor: colors.background }]}>
      <Text style={[styles.title, { color: colors.textPrimary }]}>Session Summary</Text>
      <Text style={[styles.subtitle, { color: colors.textSecondary }]}>
        {readOnly ? 'Viewing' : 'Review'} session {sessionId}
      </Text>
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, justifyContent: 'center', alignItems: 'center', padding: 24 },
  title: { fontSize: 28, fontWeight: '700', marginBottom: 8 },
  subtitle: { fontSize: 16 },
});
