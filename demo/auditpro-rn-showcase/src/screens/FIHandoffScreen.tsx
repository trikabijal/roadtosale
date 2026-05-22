import React, { useEffect, useState } from 'react';
import { View, Text, StyleSheet } from 'react-native';
import { useNavigation } from '@react-navigation/native';
import { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { ScreenChrome } from '../components/ScreenChrome';
import { StepHeading } from '../components/StepHeading';
import { ScriptCard } from '../components/ScriptCard';
import { AudioWidget } from '../components/AudioWidget';
import { AudioTranscript } from '../components/AudioTranscript';
import { useAudioScript } from '../hooks/useAudioScript';
import { useDealStore } from '../store/deal';
import { colors, fonts } from '../theme';
import { RootStackParamList } from '../navigation/types';

const formatElapsed = (ms: number) => {
  const total = Math.floor(ms / 1000);
  const m = Math.floor(total / 60).toString().padStart(2, '0');
  const s = (total % 60).toString().padStart(2, '0');
  return `${m}:${s}`;
};

export const FIHandoffScreen: React.FC = () => {
  const navigation = useNavigation<NativeStackNavigationProp<RootStackParamList>>();
  const audio = useAudioScript('fi_handoff', '9');
  const deal = useDealStore((s) => s.deal);
  const setBoolean = useDealStore((s) => s.setBoolean);
  const setTimestamp = useDealStore((s) => s.setTimestamp);
  const [now, setNow] = useState(Date.now());

  useEffect(() => {
    const id = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(id);
  }, []);

  useEffect(() => {
    if (audio.detection?.stepId === '9.1' && audio.detection.type === 'auto-confirm') {
      if (!deal?.fiHandoff) {
        setBoolean('fiHandoff', true);
        setTimestamp('fiHandoff', Date.now());
      }
    }
  }, [audio.detection, deal?.fiHandoff, setBoolean, setTimestamp]);

  const customer = deal?.setup.customer ?? '—';
  const vehicle = deal?.setup.vehicle ?? '—';
  const boSigned = deal?.timestamps.boSigned ?? now;
  const elapsed = boSigned ? Math.max(0, now - boSigned) : 0;
  const withinWindow = elapsed < 20 * 60 * 1000;
  const handoffConfirmed = Boolean(deal?.fiHandoff);

  return (
    <ScreenChrome
      routeName="FIHandoff"
      onBack={() => navigation.navigate('BuyersOrder')}
      onNext={() => navigation.navigate('AuditComplete')}
      backLabel="← Back"
      nextLabel="Audit Complete →"
    >
      <StepHeading eyebrow="Step 9 · F&I Handover" title="Warm Hand-off" />

      <ScriptCard
        body={
          <Text>
            "{customer}, let me introduce you to David Chen, our Finance Manager. David, this is {customer} — taking home the {vehicle}."
          </Text>
        }
      />

      <AudioWidget
        label="Audio active · detecting three-way intro"
        transcript={<AudioTranscript text={audio.transcript} />}
        detection={
          audio.detection?.stepId === '9.1' ? 'Three-way intro confirmed — 9.1 logged' : null
        }
      />

      <View style={styles.timerCard}>
        <Text style={styles.timerLabel}>Time since buyer's order signed</Text>
        <Text style={styles.timerValue}>{formatElapsed(elapsed)}</Text>
        <Text style={[styles.timerOk, !withinWindow && { color: colors.error }]}>
          {withinWindow ? '✓ Within 20-minute window' : '! Past 20-minute window'}
        </Text>
      </View>

      <View style={styles.fiCard}>
        <View style={styles.fiTopRow}>
          <Text style={styles.fiName}>David Chen — F&I Manager</Text>
          <Text style={styles.fiTime}>
            {deal?.timestamps.boSigned
              ? new Date(deal.timestamps.boSigned).toLocaleTimeString([], {
                  hour: 'numeric',
                  minute: '2-digit',
                })
              : '—'}
          </Text>
        </View>
        <Text style={styles.fiSub}>Deal picked up · {customer}</Text>
        {handoffConfirmed ? (
          <View style={styles.fiBadge}>
            <Text style={styles.fiBadgeTx}>In F&I Office ✓</Text>
          </View>
        ) : null}
      </View>

      {handoffConfirmed ? (
        <View style={styles.banner}>
          <View style={styles.bannerIco}>
            <Text style={styles.bannerIcoTx}>✓</Text>
          </View>
          <Text style={styles.bannerTx}>
            Warm hand-off confirmed — both devices timestamped within 2 minutes
          </Text>
        </View>
      ) : null}
    </ScreenChrome>
  );
};

const styles = StyleSheet.create({
  timerCard: {
    backgroundColor: colors.cardBg,
    borderRadius: 12,
    paddingVertical: 11,
    paddingHorizontal: 11,
    alignItems: 'center',
    shadowColor: '#000',
    shadowOpacity: 0.07,
    shadowRadius: 5,
    shadowOffset: { width: 0, height: 1 },
    elevation: 1,
  },
  timerLabel: { fontSize: 10, color: colors.textSubtle, marginBottom: 2 },
  timerValue: {
    fontSize: 30,
    fontWeight: '700',
    color: colors.primary,
    fontFamily: fonts.display,
    letterSpacing: -0.3,
  },
  timerOk: { fontSize: 10, color: colors.success, fontWeight: '600' },
  fiCard: {
    backgroundColor: colors.primaryLight,
    borderRadius: 11,
    paddingVertical: 10,
    paddingHorizontal: 13,
    borderWidth: 1.5,
    borderColor: colors.primary,
  },
  fiTopRow: { flexDirection: 'row', justifyContent: 'space-between', marginBottom: 2 },
  fiName: { fontSize: 13, fontWeight: '600', color: '#000' },
  fiTime: { fontSize: 10, color: colors.textSubtle },
  fiSub: { fontSize: 11, color: colors.textMuted },
  fiBadge: {
    alignSelf: 'flex-start',
    marginTop: 4,
    backgroundColor: colors.passBg,
    paddingHorizontal: 7,
    paddingVertical: 2,
    borderRadius: 20,
  },
  fiBadgeTx: { fontSize: 10, color: colors.success, fontWeight: '600' },
  banner: {
    backgroundColor: colors.passBg,
    borderColor: colors.passBorder,
    borderWidth: 1,
    borderRadius: 11,
    paddingVertical: 10,
    paddingHorizontal: 12,
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
  },
  bannerIco: {
    width: 22,
    height: 22,
    borderRadius: 11,
    backgroundColor: colors.success,
    alignItems: 'center',
    justifyContent: 'center',
  },
  bannerIcoTx: { color: '#fff', fontSize: 10, fontWeight: '800' },
  bannerTx: {
    flex: 1,
    fontSize: 11,
    fontWeight: '500',
    color: colors.successDeep,
    lineHeight: 15,
  },
});
