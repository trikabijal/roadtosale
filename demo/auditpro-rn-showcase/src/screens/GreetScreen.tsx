import React, { useEffect } from 'react';
import { Text } from 'react-native';
import { useNavigation } from '@react-navigation/native';
import { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { ScreenChrome } from '../components/ScreenChrome';
import { StepHeading } from '../components/StepHeading';
import { ScriptCard } from '../components/ScriptCard';
import { AudioWidget } from '../components/AudioWidget';
import { AudioTranscript } from '../components/AudioTranscript';
import { YNCard } from '../components/YNCard';
import { useAudioScript } from '../hooks/useAudioScript';
import { useDealStore } from '../store/deal';
import { RootStackParamList } from '../navigation/types';
import { colors } from '../theme';

export const GreetScreen: React.FC = () => {
  const navigation = useNavigation<NativeStackNavigationProp<RootStackParamList>>();
  const audio = useAudioScript('greet', '1');
  const deal = useDealStore((s) => s.deal);
  const setFlag = useDealStore((s) => s.setFlag);

  // When the auto-confirm detection fires for 1.2, set the flag if not already set.
  useEffect(() => {
    if (audio.detection?.stepId === '1.2' && audio.detection.type === 'auto-confirm') {
      const current = deal?.flags['g2'];
      if (!current || current.value !== 'no') {
        setFlag('g2', 'yes', 'auto');
      }
    }
  }, [audio.detection, deal?.flags, setFlag]);

  const customer = deal?.setup.customer ?? '—';
  const dealership = deal?.setup.dealership ?? '—';

  const g1 = deal?.flags['g1']?.value ?? null;
  const g2 = deal?.flags['g2']?.value ?? null;
  const g2Source = deal?.flags['g2']?.source;

  return (
    <ScreenChrome
      routeName="Greet"
      onBack={() => navigation.navigate('Dashboard')}
      onNext={() => navigation.navigate('Discovery')}
      backLabel="← Back"
      nextLabel="Discovery →"
    >
      <StepHeading eyebrow="Step 1 · Greet" title="Customer Arrival" />

      <ScriptCard
        body={
          <Text>
            "Hi <Text style={{ color: colors.primary, fontWeight: '600' }}>{customer}</Text>, welcome to{' '}
            <Text style={{ color: colors.primary, fontWeight: '600' }}>{dealership}</Text>! Can I grab you a water or coffee before we head out?"
          </Text>
        }
      />

      <AudioWidget
        transcript={<AudioTranscript text={audio.transcript} />}
        detection={
          audio.detection?.stepId === '1.2'
            ? 'Refreshment offer detected — 1.2 auto-confirmed'
            : null
        }
      />

      <YNCard
        question="1.1 — Was customer greeted within 30 seconds?"
        value={g1}
        onChange={(v) => setFlag('g1', v, 'manual')}
      />

      <YNCard
        question="1.2 — Was a refreshment offered?"
        value={g2}
        source={g2Source}
        onChange={(v) => setFlag('g2', v, 'manual')}
        autoOverride={
          g2 === 'yes' && g2Source === 'auto' ? 'Auto-detected via audio · tap to override' : undefined
        }
      />
    </ScreenChrome>
  );
};
