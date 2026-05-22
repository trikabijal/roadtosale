import React from 'react';
import { View, Text, Pressable, StyleSheet } from 'react-native';
import { colors, fonts } from '../theme';

type Props = {
  onBack?: () => void;
  onNext?: () => void;
  backLabel?: string;
  nextLabel?: string;
  hideBack?: boolean;
  hideNext?: boolean;
};

export const BottomNav: React.FC<Props> = ({
  onBack,
  onNext,
  backLabel = '← Back',
  nextLabel = 'Next →',
  hideBack,
  hideNext,
}) => (
  <View style={styles.bnv}>
    {!hideBack ? (
      <Pressable style={[styles.btn, styles.back]} onPress={onBack}>
        <Text style={styles.backTx}>{backLabel}</Text>
      </Pressable>
    ) : null}
    {!hideNext ? (
      <Pressable style={[styles.btn, styles.next]} onPress={onNext}>
        <Text style={styles.nextTx}>{nextLabel}</Text>
      </Pressable>
    ) : null}
  </View>
);

const styles = StyleSheet.create({
  bnv: {
    backgroundColor: 'rgba(248,248,252,0.94)',
    borderTopWidth: 0.5,
    borderTopColor: 'rgba(0,0,0,0.08)',
    paddingHorizontal: 14,
    paddingTop: 8,
    paddingBottom: 16,
    flexDirection: 'row',
    gap: 6,
  },
  btn: {
    flex: 1,
    paddingVertical: 11,
    borderRadius: 11,
    alignItems: 'center',
    justifyContent: 'center',
  },
  back: { backgroundColor: '#f0f0f5' },
  backTx: {
    fontSize: 13,
    fontWeight: '700',
    color: colors.textMuted,
    fontFamily: fonts.display,
  },
  next: {
    backgroundColor: colors.primary,
    shadowColor: colors.primary,
    shadowOpacity: 0.28,
    shadowRadius: 10,
    shadowOffset: { width: 0, height: 3 },
    elevation: 3,
  },
  nextTx: { fontSize: 13, fontWeight: '700', color: '#fff', fontFamily: fonts.display },
});
