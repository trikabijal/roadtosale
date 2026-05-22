import React, { useEffect } from 'react';
import { View, Text, TextInput, StyleSheet } from 'react-native';
import { useNavigation } from '@react-navigation/native';
import { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { ScreenChrome } from '../components/ScreenChrome';
import { StepHeading } from '../components/StepHeading';
import { AudioWidget } from '../components/AudioWidget';
import { AudioTranscript } from '../components/AudioTranscript';
import { PhotoSlot } from '../components/PhotoSlot';
import { RealCameraSurface } from '../components/RealCameraSurface';
import { useAudioScript } from '../hooks/useAudioScript';
import { usePhotoCapture } from '../hooks/usePhotoCapture';
import { useDealStore } from '../store/deal';
import { colors } from '../theme';
import { RootStackParamList } from '../navigation/types';

const PHOTOS = [
  { id: 't1', label: 'Front', emoji: '🚗' },
  { id: 't2', label: 'Rear', emoji: '🚙' },
  { id: 't3', label: 'Driver side', emoji: '🤝' },
  { id: 't4', label: 'Pass. side', emoji: '🤜' },
];

export const TradeInScreen: React.FC = () => {
  const navigation = useNavigation<NativeStackNavigationProp<RootStackParamList>>();
  const audio = useAudioScript('trade_in', '6');
  const deal = useDealStore((s) => s.deal);
  const setNote = useDealStore((s) => s.setNote);
  const { capture, flashKeys } = usePhotoCapture();

  useEffect(() => {
    if (audio.detection?.type === 'note-extracted' && audio.detection.payload?.note) {
      setNote('trade', audio.detection.payload.note as string);
    }
  }, [audio.detection, setNote]);

  return (
    <ScreenChrome
      routeName="TradeIn"
      scroll
      onBack={() => navigation.navigate('TestDrive')}
      onNext={() => navigation.navigate('Pencil')}
      backLabel="← Back"
      nextLabel="The Proposal →"
    >
      <StepHeading eyebrow="Step 6 · Trade-In Appraisal" title="Trade-In Photos" />

      <AudioWidget
        label="Audio active · auto-filling condition notes"
        transcript={<AudioTranscript text={audio.transcript} />}
        detection={
          audio.detection?.type === 'note-extracted'
            ? 'Defects detected — notes auto-populated below'
            : null
        }
      />

      <RealCameraSurface />

      <View style={styles.row}>
        {PHOTOS.slice(0, 2).map((p) => (
          <PhotoSlot
            key={p.id}
            label={p.label}
            emoji={p.emoji}
            photoUri={deal?.photos[p.id] ?? null}
            onCapture={() => capture(p.id)}
            flashKey={flashKeys[p.id]}
          />
        ))}
      </View>
      <View style={styles.row}>
        {PHOTOS.slice(2).map((p) => (
          <PhotoSlot
            key={p.id}
            label={p.label}
            emoji={p.emoji}
            photoUri={deal?.photos[p.id] ?? null}
            onCapture={() => capture(p.id)}
            flashKey={flashKeys[p.id]}
          />
        ))}
      </View>

      <Text style={styles.label}>Condition Notes (auto-filled)</Text>
      <TextInput
        multiline
        value={deal?.notes.trade ?? ''}
        onChangeText={(t) => setNote('trade', t)}
        placeholder="Audio will auto-fill defects..."
        placeholderTextColor={colors.textGhost}
        style={styles.notes}
      />

      <View style={styles.appraisalCard}>
        <Text style={styles.appraisalLabel}>Appraisal Value (after 4 photos)</Text>
        <Text style={styles.appraisalValue}>$18,500</Text>
      </View>
    </ScreenChrome>
  );
};

const styles = StyleSheet.create({
  row: { flexDirection: 'row', gap: 7 },
  label: {
    fontSize: 10,
    fontWeight: '600',
    color: colors.textSubtle,
    textTransform: 'uppercase',
    letterSpacing: 0.6,
  },
  notes: {
    backgroundColor: colors.cardBg,
    borderWidth: 1.5,
    borderColor: colors.border,
    borderRadius: 10,
    padding: 10,
    fontSize: 12,
    color: '#000',
    minHeight: 58,
  },
  appraisalCard: {
    backgroundColor: colors.cardBg,
    borderRadius: 10,
    paddingHorizontal: 12,
    paddingVertical: 9,
    shadowColor: '#000',
    shadowOpacity: 0.05,
    shadowRadius: 3,
    shadowOffset: { width: 0, height: 1 },
    elevation: 1,
  },
  appraisalLabel: {
    fontSize: 9,
    color: colors.textSubtle,
    marginBottom: 2,
    textTransform: 'uppercase',
    letterSpacing: 0.6,
    fontWeight: '600',
  },
  appraisalValue: { fontSize: 13, fontWeight: '500', color: '#000' },
});
