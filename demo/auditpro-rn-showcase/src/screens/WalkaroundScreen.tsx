import React, { useEffect } from 'react';
import { useNavigation } from '@react-navigation/native';
import { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { ScreenChrome } from '../components/ScreenChrome';
import { StepHeading } from '../components/StepHeading';
import { AudioWidget } from '../components/AudioWidget';
import { AudioTranscript } from '../components/AudioTranscript';
import { FeatureItem } from '../components/FeatureItem';
import { YNCard } from '../components/YNCard';
import { useAudioScript } from '../hooks/useAudioScript';
import { useDealStore } from '../store/deal';
import { RootStackParamList } from '../navigation/types';

const FEATURES = [
  { id: 'cam', icon: '📷', name: '360° Camera', description: 'Show on infotainment screen' },
  { id: 'lane', icon: '🛡', name: 'Lane Departure Assist', description: 'Safety for family buyers' },
  { id: 'play', icon: '📱', name: 'Apple CarPlay', description: 'Connect customer phone live' },
  { id: 'heat', icon: '❄', name: 'Heated Seats', description: 'Tap to confirm if shown' },
];

export const WalkaroundScreen: React.FC = () => {
  const navigation = useNavigation<NativeStackNavigationProp<RootStackParamList>>();
  const audio = useAudioScript('walkaround', '4');
  const deal = useDealStore((s) => s.deal);
  const setFlag = useDealStore((s) => s.setFlag);
  const setFeature = useDealStore((s) => s.setFeature);

  useEffect(() => {
    if (!audio.detection) return;
    const det = audio.detection;
    if (det.type === 'feature-pass' && det.payload?.featureId) {
      const id = det.payload.featureId as string;
      if (deal?.features[id] !== 'pass') setFeature(id, 'pass');
    }
    if (det.type === 'feature-fail' && det.payload?.featureId) {
      const id = det.payload.featureId as string;
      if (deal?.features[id] !== 'fail') setFeature(id, 'fail');
    }
    if (det.stepId === '4.1' && det.type === 'auto-confirm') {
      const current = deal?.flags['w1'];
      if (!current || current.value !== 'no') setFlag('w1', 'yes', 'auto');
    }
  }, [audio.detection, deal?.features, deal?.flags, setFeature, setFlag]);

  const w1 = deal?.flags['w1']?.value ?? null;
  const w1Source = deal?.flags['w1']?.source;

  return (
    <ScreenChrome
      routeName="Walkaround"
      onBack={() => navigation.navigate('FrontLineReady')}
      onNext={() => navigation.navigate('TestDrive')}
      backLabel="← Back"
      nextLabel="Test Drive →"
    >
      <StepHeading eyebrow="Step 4 · The Walkaround" title="6-Position Walkaround" />

      <AudioWidget
        transcript={<AudioTranscript text={audio.transcript} />}
        detection={
          audio.detection?.stepId === '4.1'
            ? '3 of 4 positions detected — one feature missed'
            : null
        }
      />

      {FEATURES.map((f) => {
        const state = deal?.features[f.id] ?? null;
        return (
          <FeatureItem
            key={f.id}
            icon={f.icon}
            name={f.name}
            description={f.description}
            state={state}
            onToggle={() => {
              if (state === 'fail') return; // can't toggle a hard fail
              setFeature(f.id, state === 'pass' ? null : 'pass');
            }}
          />
        );
      })}

      <YNCard
        question="4.1 — Was the full 6-position walkaround completed?"
        value={w1}
        source={w1Source}
        onChange={(v) => setFlag('w1', v, 'manual')}
        autoOverride={w1 === 'yes' && w1Source === 'auto' ? 'Auto-detected · tap to override' : undefined}
      />
    </ScreenChrome>
  );
};
