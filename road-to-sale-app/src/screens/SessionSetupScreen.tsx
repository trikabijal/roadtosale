import React from 'react';
import { View, Text, StyleSheet } from 'react-native';
import type { SessionSetupScreenProps } from '../navigation/types';
import { useTheme } from '../theme';

export default function SessionSetupScreen({ navigation, route }: SessionSetupScreenProps) {
  const { colors } = useTheme();
  return (
    <View style={[styles.container, { backgroundColor: colors.background }]}>
      <Text style={[styles.title, { color: colors.textPrimary }]}>Session Setup</Text>
      <Text style={[styles.subtitle, { color: colors.textSecondary }]}>
        Configure customer and vehicle
      </Text>
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, justifyContent: 'center', alignItems: 'center', padding: 24 },
  title: { fontSize: 28, fontWeight: '700', marginBottom: 8 },
  subtitle: { fontSize: 16 },
});
