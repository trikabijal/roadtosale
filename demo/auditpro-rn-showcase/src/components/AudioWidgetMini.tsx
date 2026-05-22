import React from 'react';
import { View, Text, StyleSheet } from 'react-native';
import { PulsingDot } from './PulsingDot';
import { Waveform } from './Waveform';
import { AudioTranscript } from './AudioTranscript';
import { colors, fontSize } from '../theme';

type Props = {
  title: string;
  transcript: string;
};

export const AudioWidgetMini: React.FC<Props> = ({ title, transcript }) => (
  <View>
    <View style={styles.hdr}>
      <Text style={styles.title}>{title}</Text>
      <View style={styles.right}>
        <PulsingDot size={6} />
        <Waveform size="mini" />
      </View>
    </View>
    <AudioTranscript text={transcript} style={styles.tx} />
  </View>
);

const styles = StyleSheet.create({
  hdr: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
  },
  title: {
    fontSize: fontSize.labelSmall,
    fontWeight: '600',
    color: colors.textSubtle,
    textTransform: 'uppercase',
    letterSpacing: 0.6,
  },
  right: { flexDirection: 'row', alignItems: 'center', gap: 5 },
  tx: { fontSize: 10, color: '#555', paddingTop: 5, paddingBottom: 2, lineHeight: 14 },
});
