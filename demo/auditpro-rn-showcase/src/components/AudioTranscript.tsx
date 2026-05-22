import React from 'react';
import { Text, StyleSheet, TextStyle } from 'react-native';
import { colors } from '../theme';

type Props = {
  text: string;
  style?: TextStyle;
};

// Renders text containing inline {{trig:phrase}} tokens with highlight styling.
export const AudioTranscript: React.FC<Props> = ({ text, style }) => {
  const parts: { text: string; trig: boolean }[] = [];
  const regex = /\{\{trig:(.*?)\}\}/g;
  let last = 0;
  let m: RegExpExecArray | null;
  while ((m = regex.exec(text))) {
    if (m.index > last) parts.push({ text: text.slice(last, m.index), trig: false });
    parts.push({ text: m[1], trig: true });
    last = m.index + m[0].length;
  }
  if (last < text.length) parts.push({ text: text.slice(last), trig: false });

  return (
    <Text style={[styles.body, style]}>
      {parts.map((p, i) =>
        p.trig ? (
          <Text key={i} style={styles.trig}>
            {p.text}
          </Text>
        ) : (
          <Text key={i}>{p.text}</Text>
        )
      )}
    </Text>
  );
};

const styles = StyleSheet.create({
  body: { fontSize: 11, color: '#555', fontStyle: 'italic', lineHeight: 16 },
  trig: {
    color: colors.primary,
    fontWeight: '600',
    fontStyle: 'normal',
    backgroundColor: 'rgba(29,82,232,0.1)',
  },
});
