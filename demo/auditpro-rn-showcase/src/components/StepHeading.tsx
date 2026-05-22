import React from 'react';
import { View, Text, StyleSheet } from 'react-native';
import { colors } from '../theme';

type Props = {
  eyebrow: string;
  title: string;
  subtitle?: string;
};

export const StepHeading: React.FC<Props> = ({ eyebrow, title, subtitle }) => (
  <View>
    <View style={styles.eyebrowRow}>
      <View style={styles.dash} />
      <Text style={styles.eyebrow}>{eyebrow}</Text>
    </View>
    <Text style={styles.title}>{title}</Text>
    {subtitle ? <Text style={styles.subtitle}>{subtitle}</Text> : null}
  </View>
);

const styles = StyleSheet.create({
  eyebrowRow: { flexDirection: 'row', alignItems: 'center', gap: 5 },
  dash: { width: 14, height: 2, backgroundColor: colors.primary, borderRadius: 1 },
  eyebrow: {
    fontSize: 9,
    fontWeight: '700',
    color: colors.primary,
    letterSpacing: 0.9,
    textTransform: 'uppercase',
  },
  title: { fontSize: 18, fontWeight: '700', color: '#000', lineHeight: 22, marginTop: 4 },
  subtitle: { fontSize: 11, color: colors.textMuted, marginTop: 4, lineHeight: 15 },
});
