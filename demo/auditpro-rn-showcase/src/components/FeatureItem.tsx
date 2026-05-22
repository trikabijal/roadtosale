import React from 'react';
import { View, Text, Pressable, StyleSheet } from 'react-native';
import { colors } from '../theme';

type Props = {
  icon: string;
  name: string;
  description: string;
  state: 'pass' | 'fail' | null;
  onToggle?: () => void;
  passive?: boolean;
};

export const FeatureItem: React.FC<Props> = ({
  icon,
  name,
  description,
  state,
  onToggle,
  passive,
}) => {
  const containerStyle = [
    styles.fi,
    state === 'pass' && styles.pass,
    state === 'fail' && styles.fail,
  ];
  const Outer = passive ? View : Pressable;
  return (
    <Outer style={containerStyle} onPress={!passive ? onToggle : undefined}>
      <View style={[styles.iconWrap, state === 'pass' && styles.iconWrapPass, state === 'fail' && styles.iconWrapFail]}>
        <Text style={styles.icon}>{icon}</Text>
      </View>
      <View style={styles.body}>
        <Text style={[styles.name, state === 'pass' && styles.namePass, state === 'fail' && styles.nameFail]}>
          {name}
        </Text>
        <Text style={styles.desc}>
          {state === 'fail' ? 'Not detected — confirm manually' : description}
        </Text>
      </View>
      {!passive ? (
        <View
          style={[
            styles.tick,
            state === 'pass' && styles.tickPass,
            state === 'fail' && styles.tickFail,
          ]}
        >
          <Text
            style={[
              styles.tickTx,
              state === 'pass' && styles.tickTxPass,
              state === 'fail' && styles.tickTxFail,
            ]}
          >
            {state === 'pass' ? '✓' : state === 'fail' ? '!' : ''}
          </Text>
        </View>
      ) : null}
    </Outer>
  );
};

const styles = StyleSheet.create({
  fi: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 9,
    backgroundColor: colors.cardBg,
    borderRadius: 10,
    paddingHorizontal: 12,
    paddingVertical: 9,
    shadowColor: '#000',
    shadowOpacity: 0.05,
    shadowRadius: 3,
    shadowOffset: { width: 0, height: 1 },
    elevation: 1,
    borderWidth: 1.5,
    borderColor: 'transparent',
  },
  pass: { borderColor: colors.success, backgroundColor: colors.successLight },
  fail: { borderColor: colors.error, backgroundColor: colors.errorLight },
  iconWrap: {
    width: 28,
    height: 28,
    borderRadius: 7,
    backgroundColor: colors.primaryLight,
    alignItems: 'center',
    justifyContent: 'center',
  },
  iconWrapPass: { backgroundColor: 'rgba(21,163,84,0.15)' },
  iconWrapFail: { backgroundColor: colors.errorLight },
  icon: { fontSize: 14 },
  body: { flex: 1 },
  name: { fontSize: 12, fontWeight: '600', color: '#000' },
  namePass: { color: colors.success },
  nameFail: { color: colors.error },
  desc: { fontSize: 10, color: colors.textSubtle },
  tick: {
    width: 19,
    height: 19,
    borderRadius: 19,
    borderWidth: 2,
    borderColor: '#d0d0dc',
    alignItems: 'center',
    justifyContent: 'center',
  },
  tickPass: { backgroundColor: colors.success, borderColor: colors.success },
  tickFail: { backgroundColor: colors.errorLight, borderColor: colors.error },
  tickTx: { fontSize: 10, fontWeight: '800', color: '#fff' },
  tickTxPass: { color: '#fff' },
  tickTxFail: { color: colors.error },
});
