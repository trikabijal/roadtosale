import React from 'react';
import { View, Text, StyleSheet } from 'react-native';
import { colors } from '../theme';

type Props = {
  label?: string;
  body: React.ReactNode;
  icon?: string;
};

export const ScriptCard: React.FC<Props> = ({ label = 'Script', body, icon = '💬' }) => (
  <View style={styles.script}>
    <Text style={styles.icon}>{icon}</Text>
    <View style={styles.bodyWrap}>
      <Text style={styles.label}>{label}</Text>
      <Text style={styles.body}>{body}</Text>
    </View>
  </View>
);

const styles = StyleSheet.create({
  script: {
    backgroundColor: colors.cardBgSoft,
    borderLeftWidth: 3,
    borderLeftColor: colors.primary,
    borderTopRightRadius: 10,
    borderBottomRightRadius: 10,
    paddingHorizontal: 12,
    paddingVertical: 9,
    flexDirection: 'row',
    alignItems: 'flex-start',
    gap: 8,
  },
  icon: { fontSize: 13, marginTop: 1 },
  bodyWrap: { flex: 1 },
  label: {
    fontSize: 9,
    fontWeight: '700',
    color: colors.primary,
    textTransform: 'uppercase',
    letterSpacing: 0.7,
    marginBottom: 3,
  },
  body: { fontSize: 11, color: '#3a3a4a', lineHeight: 16, fontStyle: 'italic' },
});
