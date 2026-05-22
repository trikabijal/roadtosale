import React from 'react';
import { View, Text, StyleSheet } from 'react-native';
import { ProgressBar } from './ProgressBar';
import { colors, fontSize, fonts } from '../theme';

type Props = {
  dealership: string;
  stepLabel: string;
  percent: number;
};

const Logo = () => (
  <View style={styles.logoMark}>
    <Text style={styles.logoTick}>✓</Text>
  </View>
);

export const TopHeader: React.FC<Props> = ({ dealership, stepLabel, percent }) => (
  <View style={styles.header}>
    <View style={styles.row}>
      <View style={styles.brand}>
        <Logo />
        <Text style={styles.brandTx}>AuditPro</Text>
      </View>
      <Text style={styles.dealership}>{dealership || '—'}</Text>
    </View>
    <View style={styles.progressRow}>
      <Text style={styles.label}>{stepLabel}</Text>
      <Text style={styles.percent}>{percent}%</Text>
    </View>
    <ProgressBar percent={percent} />
  </View>
);

const styles = StyleSheet.create({
  header: {
    backgroundColor: '#fff',
    paddingHorizontal: 16,
    paddingTop: 5,
    paddingBottom: 8,
    borderBottomWidth: 0.5,
    borderBottomColor: colors.hairline,
  },
  row: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', marginBottom: 5 },
  brand: { flexDirection: 'row', alignItems: 'center', gap: 5 },
  logoMark: {
    width: 20,
    height: 20,
    backgroundColor: colors.primary,
    borderRadius: 5,
    alignItems: 'center',
    justifyContent: 'center',
  },
  logoTick: { color: '#fff', fontWeight: '800', fontSize: 12 },
  brandTx: {
    fontFamily: fonts.displayHeavy,
    fontSize: fontSize.brand,
    fontWeight: '800',
    color: '#000',
  },
  dealership: { fontSize: 10, color: colors.textSubtle },
  progressRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    marginTop: 4,
    marginBottom: 3,
  },
  label: { fontSize: 10, color: colors.textSubtle },
  percent: { fontSize: 10, fontWeight: '600', color: colors.primary },
});
