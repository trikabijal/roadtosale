import React from 'react';
import { View, Text, StyleSheet } from 'react-native';
import type { HomeScreenProps } from '../navigation/types';
import { useTheme } from '../theme';

export default function HomeScreen({ navigation }: HomeScreenProps) {
  const { colors } = useTheme();
  return (
    <View style={[styles.container, { backgroundColor: colors.background }]}>
      <Text style={[styles.title, { color: colors.textPrimary }]}>Home</Text>
      <Text style={[styles.subtitle, { color: colors.textSecondary }]}>Today's appointments</Text>
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, justifyContent: 'center', alignItems: 'center', padding: 24 },
  title: { fontSize: 28, fontWeight: '700', marginBottom: 8 },
  subtitle: { fontSize: 16 },
});
