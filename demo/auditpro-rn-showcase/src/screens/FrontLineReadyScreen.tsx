import React, { useEffect } from 'react';
import { View, Text, StyleSheet } from 'react-native';
import { useNavigation } from '@react-navigation/native';
import { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { ScreenChrome } from '../components/ScreenChrome';
import { StepHeading } from '../components/StepHeading';
import { AudioWidget } from '../components/AudioWidget';
import { AudioTranscript } from '../components/AudioTranscript';
import { YNCard } from '../components/YNCard';
import { PhotoSlot } from '../components/PhotoSlot';
import { RealCameraSurface } from '../components/RealCameraSurface';
import { useAudioScript } from '../hooks/useAudioScript';
import { usePhotoCapture } from '../hooks/usePhotoCapture';
import { useDealStore } from '../store/deal';
import { colors } from '../theme';
import { RootStackParamList } from '../navigation/types';

export const FrontLineReadyScreen: React.FC = () => {
  const navigation = useNavigation<NativeStackNavigationProp<RootStackParamList>>();
  const audio = useAudioScript('front_line_ready', '3.2');
  const deal = useDealStore((s) => s.deal);
  const setFlag = useDealStore((s) => s.setFlag);
  const { capture, flashKeys } = usePhotoCapture();

  useEffect(() => {
    if (audio.detection?.stepId === '3.2' && audio.detection.type === 'auto-confirm') {
      const current = deal?.flags['v2'];
      if (!current || current.value !== 'no') setFlag('v2', 'yes', 'auto');
    }
  }, [audio.detection, deal?.flags, setFlag]);

  const v2 = deal?.flags['v2']?.value ?? null;
  const v2Source = deal?.flags['v2']?.source;

  return (
    <ScreenChrome
      routeName="FrontLineReady"
      onBack={() => navigation.navigate('FeatureMatch')}
      onNext={() => navigation.navigate('Walkaround')}
      backLabel="← Back"
      nextLabel="Walkaround →"
    >
      <StepHeading eyebrow="Step 3.2 · Vehicle Readiness" title="Front-Line Ready?" />

      <AudioWidget
        label="Audio active · timing key handover"
        transcript={<AudioTranscript text={audio.transcript} />}
        detection={
          audio.detection?.stepId === '3.2'
            ? 'Key handover detected · 47s gap — vehicle was ready'
            : null
        }
      />

      <YNCard
        question="3.2 — Is the car Front-Line Ready? (clean, fueled, staged)"
        value={v2}
        source={v2Source}
        onChange={(v) => setFlag('v2', v, 'manual')}
        autoOverride={v2 === 'yes' && v2Source === 'auto' ? 'Timing detected via audio · tap to override' : undefined}
      />

      <RealCameraSurface />

      <Text style={styles.label}>Vehicle Photos</Text>
      <View style={styles.photoRow}>
        <PhotoSlot
          label="Exterior"
          photoUri={deal?.photos['ext'] ?? null}
          emoji="🌍"
          onCapture={() => capture('ext')}
          flashKey={flashKeys['ext']}
        />
        <PhotoSlot
          label="Interior"
          photoUri={deal?.photos['int'] ?? null}
          emoji="🚙"
          onCapture={() => capture('int')}
          flashKey={flashKeys['int']}
        />
      </View>
    </ScreenChrome>
  );
};

const styles = StyleSheet.create({
  label: {
    fontSize: 10,
    fontWeight: '600',
    color: colors.textSubtle,
    textTransform: 'uppercase',
    letterSpacing: 0.6,
  },
  photoRow: { flexDirection: 'row', gap: 7 },
});
