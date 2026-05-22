import React from 'react';
import { View, Text, Pressable, StyleSheet } from 'react-native';
import { colors } from '../theme';

type Props = {
  question: string;
  value: 'yes' | 'no' | null;
  source?: 'manual' | 'auto';
  onChange: (v: 'yes' | 'no') => void;
  autoOverride?: string;
};

export const YNCard: React.FC<Props> = ({
  question,
  value,
  source,
  onChange,
  autoOverride,
}) => (
  <View style={styles.card}>
    <Text style={styles.q}>{question}</Text>
    <View style={styles.row}>
      <Pressable
        style={[styles.btn, value === 'yes' && styles.yesActive]}
        onPress={() => onChange('yes')}
      >
        <Text style={[styles.btnTx, value === 'yes' && styles.yesActiveTx]}>
          ✓ Yes
        </Text>
        {source === 'auto' && value === 'yes' ? (
          <View style={styles.autoTag}>
            <Text style={styles.autoTagTx}>Auto</Text>
          </View>
        ) : null}
      </Pressable>
      <Pressable
        style={[styles.btn, value === 'no' && styles.noActive]}
        onPress={() => onChange('no')}
      >
        <Text style={[styles.btnTx, value === 'no' && styles.noActiveTx]}>✗ No</Text>
      </Pressable>
    </View>
    {autoOverride ? <Text style={styles.override}>{autoOverride}</Text> : null}
  </View>
);

const styles = StyleSheet.create({
  card: {
    backgroundColor: colors.cardBg,
    borderRadius: 13,
    paddingHorizontal: 14,
    paddingVertical: 12,
    shadowColor: '#000',
    shadowOpacity: 0.07,
    shadowRadius: 6,
    shadowOffset: { width: 0, height: 1 },
    elevation: 1,
  },
  q: { fontSize: 13, fontWeight: '500', color: '#000', marginBottom: 10, lineHeight: 18 },
  row: { flexDirection: 'row', gap: 6 },
  btn: {
    flex: 1,
    paddingVertical: 10,
    borderRadius: 10,
    borderWidth: 1.5,
    borderColor: colors.border,
    backgroundColor: colors.cardBgSoft,
    alignItems: 'center',
    justifyContent: 'center',
    position: 'relative',
  },
  btnTx: { fontSize: 13, fontWeight: '600', color: colors.textMuted },
  yesActive: { backgroundColor: colors.passBg, borderColor: colors.success },
  yesActiveTx: { color: colors.success },
  noActive: { backgroundColor: colors.errorLight, borderColor: colors.error },
  noActiveTx: { color: colors.error },
  autoTag: {
    position: 'absolute',
    top: -8,
    backgroundColor: colors.success,
    paddingHorizontal: 6,
    paddingVertical: 1,
    borderRadius: 10,
  },
  autoTagTx: { color: '#fff', fontSize: 8, fontWeight: '700' },
  override: {
    fontSize: 9,
    color: 'rgba(21,163,84,0.7)',
    textAlign: 'center',
    marginTop: 3,
  },
});
