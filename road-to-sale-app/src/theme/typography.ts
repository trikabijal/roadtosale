import { StyleSheet } from 'react-native';

export const Typography = StyleSheet.create({
  h1: { fontSize: 28, fontWeight: '700', lineHeight: 34 },
  h2: { fontSize: 22, fontWeight: '700', lineHeight: 28 },
  h3: { fontSize: 18, fontWeight: '600', lineHeight: 24 },
  body: { fontSize: 16, fontWeight: '400', lineHeight: 22 },
  bodyMedium: { fontSize: 16, fontWeight: '500', lineHeight: 22 },
  caption: { fontSize: 13, fontWeight: '400', lineHeight: 18 },
  captionMedium: { fontSize: 13, fontWeight: '500', lineHeight: 18 },
  label: { fontSize: 11, fontWeight: '600', lineHeight: 16, letterSpacing: 0.5, textTransform: 'uppercase' },
  // One-thumb target sizes — minimum 44pt tap target for showroom use
  buttonLarge: { fontSize: 17, fontWeight: '600', lineHeight: 22 },
  buttonMedium: { fontSize: 15, fontWeight: '600', lineHeight: 20 },
});
