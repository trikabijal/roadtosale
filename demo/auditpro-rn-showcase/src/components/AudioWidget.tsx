import React from 'react';
import { View, Text, StyleSheet } from 'react-native';
import { colors, fontSize } from '../theme';
import { PulsingDot } from './PulsingDot';
import { Waveform } from './Waveform';

type Props = {
  label?: string;
  transcript: React.ReactNode;
  detection?: string | null;
};

export const AudioWidget: React.FC<Props> = ({
  label = 'Audio detection active',
  transcript,
  detection,
}) => (
  <View style={styles.aud}>
    <View style={styles.hdr}>
      <PulsingDot />
      <Text style={styles.label}>{label}</Text>
    </View>
    <View style={styles.waveWrap}>
      <Waveform />
    </View>
    <Text style={styles.tx}>{transcript}</Text>
    {detection ? (
      <View style={styles.det}>
        <Text style={styles.detIco}>✓</Text>
        <Text style={styles.detTx}>{detection}</Text>
      </View>
    ) : null}
  </View>
);

const styles = StyleSheet.create({
  aud: {
    backgroundColor: 'rgba(29,82,232,0.05)',
    borderWidth: 1,
    borderColor: 'rgba(29,82,232,0.18)',
    borderRadius: 12,
    paddingHorizontal: 13,
    paddingVertical: 10,
  },
  hdr: { flexDirection: 'row', alignItems: 'center', gap: 7, marginBottom: 6 },
  label: { fontSize: fontSize.labelSmall, fontWeight: '600', color: colors.primary, letterSpacing: 0.3 },
  waveWrap: { marginBottom: 6 },
  tx: {
    fontSize: fontSize.label,
    color: '#555',
    lineHeight: 16,
    fontStyle: 'italic',
    minHeight: 30,
  },
  det: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 5,
    marginTop: 5,
    paddingTop: 5,
    borderTopWidth: 0.5,
    borderTopColor: 'rgba(29,82,232,0.12)',
  },
  detIco: { fontSize: 10, color: colors.success },
  detTx: { fontSize: 10, color: 'rgba(21,163,84,0.85)', fontWeight: '600' },
});
