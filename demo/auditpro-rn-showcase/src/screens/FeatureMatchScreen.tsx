import React, { useEffect } from 'react';
import { Text, StyleSheet } from 'react-native';
import { useNavigation } from '@react-navigation/native';
import { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { LinearGradient } from 'expo-linear-gradient';
import { ScreenChrome } from '../components/ScreenChrome';
import { StepHeading } from '../components/StepHeading';
import { AudioWidget } from '../components/AudioWidget';
import { AudioTranscript } from '../components/AudioTranscript';
import { FeatureItem } from '../components/FeatureItem';
import { YNCard } from '../components/YNCard';
import { useAudioScript } from '../hooks/useAudioScript';
import { useDealStore } from '../store/deal';
import { colors, fonts } from '../theme';
import { RootStackParamList } from '../navigation/types';

export const FeatureMatchScreen: React.FC = () => {
  const navigation = useNavigation<NativeStackNavigationProp<RootStackParamList>>();
  const audio = useAudioScript('feature_match', '3');
  const deal = useDealStore((s) => s.deal);
  const setFlag = useDealStore((s) => s.setFlag);

  useEffect(() => {
    if (audio.detection?.stepId === '3.1' && audio.detection.type === 'auto-confirm') {
      const current = deal?.flags['v1'];
      if (!current || current.value !== 'no') setFlag('v1', 'yes', 'auto');
    }
  }, [audio.detection, deal?.flags, setFlag]);

  const v1 = deal?.flags['v1']?.value ?? null;
  const v1Source = deal?.flags['v1']?.source;
  const customer = deal?.setup.customer ?? '—';
  const vehicle = deal?.setup.vehicle ?? '—';

  return (
    <ScreenChrome
      routeName="FeatureMatch"
      onBack={() => navigation.navigate('Discovery')}
      onNext={() => navigation.navigate('FrontLineReady')}
      backLabel="← Back"
      nextLabel="Front-Line Ready →"
    >
      <StepHeading eyebrow="Step 3 · Vehicle Match" title="Feature Reminder" />

      <LinearGradient
        colors={[colors.primary, '#3B72F8']}
        start={{ x: 0, y: 0 }}
        end={{ x: 1, y: 1 }}
        style={styles.fnc}
      >
        <Text style={styles.fnl}>Loaded from VIN · {vehicle}</Text>
        <Text style={styles.fnt}>Highlight for {customer}</Text>
        <Text style={styles.fns}>Family + highway profile — connect these to her needs</Text>
      </LinearGradient>

      <FeatureItem
        icon="📷"
        name="360° Surround Camera"
        description="Safety for family buyers"
        state={null}
        passive
      />
      <FeatureItem
        icon="🛡"
        name="Lane Departure Assist"
        description="Highway safety"
        state={null}
        passive
      />
      <FeatureItem
        icon="📱"
        name="Apple CarPlay"
        description="Connected family nav"
        state={null}
        passive
      />

      <AudioWidget
        transcript={<AudioTranscript text={audio.transcript} />}
        detection={
          audio.detection?.stepId === '3.1'
            ? 'Needs connection detected — 3.1 auto-confirmed'
            : null
        }
      />

      <YNCard
        question="3.1 — Did you connect this vehicle's features to her stated needs?"
        value={v1}
        source={v1Source}
        onChange={(v) => setFlag('v1', v, 'manual')}
        autoOverride={v1 === 'yes' && v1Source === 'auto' ? 'Auto-detected · tap to override' : undefined}
      />
    </ScreenChrome>
  );
};

const styles = StyleSheet.create({
  fnc: {
    borderRadius: 12,
    padding: 14,
  },
  fnl: {
    fontSize: 9,
    fontWeight: '600',
    color: 'rgba(255,255,255,0.6)',
    textTransform: 'uppercase',
    letterSpacing: 0.7,
    marginBottom: 4,
  },
  fnt: {
    fontFamily: fonts.display,
    fontSize: 14,
    fontWeight: '700',
    color: '#fff',
    marginBottom: 2,
  },
  fns: { fontSize: 11, color: 'rgba(255,255,255,0.7)', lineHeight: 15 },
});
