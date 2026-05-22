import React, { useEffect, useState } from 'react';
import { View, Text, TextInput, StyleSheet } from 'react-native';
import { useNavigation } from '@react-navigation/native';
import { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { ScreenChrome } from '../components/ScreenChrome';
import { StepHeading } from '../components/StepHeading';
import { AudioWidgetMini } from '../components/AudioWidgetMini';
import { CheckboxRow } from '../components/CheckboxRow';
import { useAudioScript } from '../hooks/useAudioScript';
import { useDealStore } from '../store/deal';
import { colors } from '../theme';
import { RootStackParamList } from '../navigation/types';

const USE_CASES = [
  { id: 'fam', label: 'Family / school runs' },
  { id: 'hw', label: 'Highway commuting' },
  { id: 'tow', label: 'Towing / hauling' },
  { id: 'cargo', label: 'Cargo / storage' },
];

export const DiscoveryScreen: React.FC = () => {
  const navigation = useNavigation<NativeStackNavigationProp<RootStackParamList>>();
  const audio = useAudioScript('discovery', '2');
  const deal = useDealStore((s) => s.deal);
  const setUseCase = useDealStore((s) => s.setUseCase);

  // Auto-tick checkboxes when audio detects use cases.
  useEffect(() => {
    if (audio.detection?.type === 'usecase-detected' && audio.detection.payload?.useCase) {
      const id = audio.detection.payload.useCase as string;
      if (!deal?.useCases?.[id]) {
        setUseCase(id, true);
      }
    }
  }, [audio.detection, deal?.useCases, setUseCase]);

  const [budget, setBudget] = useState('$45,000');
  const [trade, setTrade] = useState('2019 RAV4');
  const [timeline, setTimeline] = useState('This week');

  const customer = deal?.setup.customer ?? '—';

  return (
    <ScreenChrome
      routeName="Discovery"
      scroll
      onBack={() => navigation.navigate('Greet')}
      onNext={() => navigation.navigate('FeatureMatch')}
      backLabel="← Back"
      nextLabel="Feature Match →"
    >
      <StepHeading eyebrow="Step 2 · Needs Discovery" title="Discovery Sheet" />

      <View style={styles.fieldFull}>
        <Text style={styles.fieldLabel}>Customer</Text>
        <Text style={styles.fieldValue}>{customer}</Text>
      </View>

      <View style={styles.row}>
        <Field label="Budget" value={budget} onChange={setBudget} />
        <Field label="Trade-in" value={trade} onChange={setTrade} />
      </View>
      <View style={styles.row}>
        <Field label="Timeline" value={timeline} onChange={setTimeline} />
      </View>

      <AudioWidgetMini title="Use Cases" transcript={audio.transcript} />

      {USE_CASES.map((u) => {
        const checked = Boolean(deal?.useCases?.[u.id]);
        return (
          <CheckboxRow
            key={u.id}
            label={u.label}
            checked={checked}
            glowKey={checked ? `${u.id}-on` : `${u.id}-off`}
            onToggle={() => setUseCase(u.id, !checked)}
          />
        );
      })}
    </ScreenChrome>
  );
};

const Field: React.FC<{ label: string; value: string; onChange: (v: string) => void }> = ({
  label,
  value,
  onChange,
}) => (
  <View style={styles.field}>
    <Text style={styles.fieldLabel}>{label}</Text>
    <TextInput style={styles.input} value={value} onChangeText={onChange} />
  </View>
);

const styles = StyleSheet.create({
  row: { flexDirection: 'row', gap: 7 },
  field: {
    flex: 1,
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
  fieldFull: {
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
  fieldLabel: {
    fontSize: 9,
    color: colors.textSubtle,
    marginBottom: 2,
    textTransform: 'uppercase',
    letterSpacing: 0.6,
    fontWeight: '600',
  },
  fieldValue: { fontSize: 13, fontWeight: '500', color: '#000' },
  input: { fontSize: 13, fontWeight: '500', color: '#000', padding: 0 },
});
