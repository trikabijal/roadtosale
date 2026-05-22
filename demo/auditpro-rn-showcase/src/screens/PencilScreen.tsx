import React, { useEffect, useState } from 'react';
import { View, Text, StyleSheet, Pressable, Modal } from 'react-native';
import { useNavigation } from '@react-navigation/native';
import { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { LinearGradient } from 'expo-linear-gradient';
import { ScreenChrome } from '../components/ScreenChrome';
import { StepHeading } from '../components/StepHeading';
import { ScriptCard } from '../components/ScriptCard';
import { AudioWidget } from '../components/AudioWidget';
import { AudioTranscript } from '../components/AudioTranscript';
import { useDealStore } from '../store/deal';
import { useServices } from '../services/context';
import { ScriptedAudioService } from '../services/audio/ScriptedAudioService';
import { colors, fonts } from '../theme';
import { RootStackParamList } from '../navigation/types';
import {
  AudioSession,
  TranscriptEvent,
  DetectionEvent,
} from '../services/audio/AudioService';

const QUOTE_LINES: { label: string; value: string; tone?: 'pos' | 'muted' }[] = [
  { label: 'Vehicle Price (MSRP)', value: '$41,250' },
  { label: 'Trade-in Allowance', value: '−$18,500', tone: 'pos' },
  { label: 'Sales Tax (8.5%)', value: '$1,934', tone: 'muted' },
  { label: 'Doc Fee', value: '$599', tone: 'muted' },
  { label: 'Registration', value: '$385', tone: 'muted' },
];

const formatTime = (ts: number) =>
  new Date(ts).toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' });

export const PencilScreen: React.FC = () => {
  const navigation = useNavigation<NativeStackNavigationProp<RootStackParamList>>();
  const deal = useDealStore((s) => s.deal);
  const setBoolean = useDealStore((s) => s.setBoolean);
  const setTimestamp = useDealStore((s) => s.setTimestamp);

  // No audio on this screen — Manager T.O. sheet starts audio when opened.

  const customer = deal?.setup.customer ?? '—';
  const pencilTs = deal?.timestamps.pencil ?? null;

  const markPencil = async () => {
    const now = Date.now();
    await setBoolean('pencilMarked', true);
    await setTimestamp('pencil', now);
  };

  return (
    <ScreenChrome
      routeName="Pencil"
      onBack={() => navigation.navigate('TradeIn')}
      onNext={() => navigation.navigate('BuyersOrder')}
      backLabel="← Back"
      nextLabel="Buyer's Order →"
    >
      <StepHeading eyebrow="Step 7 · The Proposal" title="First Pencil" />

      <View style={styles.quote}>
        <View style={styles.quoteHeader}>
          <Text style={styles.quoteHeaderTx}>Quote · {customer}</Text>
        </View>
        {QUOTE_LINES.map((q) => (
          <View key={q.label} style={styles.quoteRow}>
            <Text style={[styles.quoteLabel, q.tone === 'muted' && { color: colors.textMuted, fontSize: 11 }]}>
              {q.label}
            </Text>
            <Text style={[styles.quoteValue, q.tone === 'pos' && { color: colors.success }]}>
              {q.value}
            </Text>
          </View>
        ))}
        <View style={[styles.quoteRow, styles.quoteTotal]}>
          <Text style={[styles.quoteLabel, { fontWeight: '700' }]}>Out-the-Door Total</Text>
          <Text style={styles.quoteTotalValue}>$25,668</Text>
        </View>
      </View>

      {!deal?.pencilMarked ? (
        <Pressable style={styles.markBtn} onPress={markPencil}>
          <Text style={styles.markBtnTx}>✎ Mark First Pencil Presented</Text>
        </Pressable>
      ) : (
        <LinearGradient colors={['#DFF7EC', '#C8F2DC']} style={styles.confirmCard}>
          <View style={styles.confirmIco}>
            <Text style={styles.confirmIcoTx}>✓</Text>
          </View>
          <Text style={styles.confirmTitle}>First Pencil Presented</Text>
          <Text style={styles.confirmSub}>
            Logged {pencilTs ? formatTime(pencilTs) : '—'} · Today
          </Text>
        </LinearGradient>
      )}

      {deal?.pencilMarked ? <ManagerTOFloatingButton /> : null}
    </ScreenChrome>
  );
};

const ManagerTOFloatingButton: React.FC = () => {
  const [open, setOpen] = useState(false);
  return (
    <>
      <Pressable style={styles.mgrFloat} onPress={() => setOpen(true)}>
        <Text style={styles.mgrFloatTx}>🤝 Manager T.O.</Text>
      </Pressable>
      <ManagerTOSheet visible={open} onClose={() => setOpen(false)} />
    </>
  );
};

const ManagerTOSheet: React.FC<{ visible: boolean; onClose: () => void }> = ({
  visible,
  onClose,
}) => {
  const services = useServices();
  const deal = useDealStore((s) => s.deal);
  const setTimestamp = useDealStore((s) => s.setTimestamp);
  const [transcript, setTranscript] = useState('Listening for new voice...');
  const [detection, setDetection] = useState<string | null>(null);

  useEffect(() => {
    if (!visible) {
      setTranscript('Listening for new voice...');
      setDetection(null);
      return;
    }

    if (services.audio instanceof ScriptedAudioService && deal) {
      services.audio.setContext({
        customer: deal.setup.customer,
        dealership: deal.setup.dealership,
        rep: deal.setup.rep,
        vehicle: deal.setup.vehicle,
      });
    }

    let cancelled = false;
    let session: AudioSession | null = null;

    services.audio
      .start({ screenId: 'manager_to', stepId: '8.1' })
      .then((s) => {
        if (cancelled) {
          s.stop().catch(() => {});
          return;
        }
        session = s;
        s.onTranscript((e: TranscriptEvent) => setTranscript(e.text));
        s.onDetection((e: DetectionEvent) => {
          if (e.stepId === '8.1') {
            setDetection('New voice + name intro detected — T.O. confirmed');
            if (!deal?.timestamps.mgrTo) {
              setTimestamp('mgrTo', Date.now());
            }
          }
        });
      })
      .catch((err) => {
        if (cancelled) return;
         
        console.error('[AuditPro] manager_to audio.start failed', err);
        setTranscript('Audio unavailable.');
      });

    return () => {
      cancelled = true;
      session?.stop().catch(() => {});
    };
  }, [visible, services.audio, deal, setTimestamp]);

  const customer = deal?.setup.customer ?? '—';
  const vehicle = deal?.setup.vehicle ?? '—';

  return (
    <Modal visible={visible} animationType="slide" transparent onRequestClose={onClose}>
      <View style={styles.overlay}>
        <View style={styles.sheet}>
          <View style={styles.handle} />
          <View style={styles.sheetHeader}>
            <Text style={styles.sheetTitle}>Manager T.O.</Text>
            <Pressable style={styles.close} onPress={onClose}>
              <Text style={styles.closeTx}>✕</Text>
            </Pressable>
          </View>
          <ScriptCard
            body={
              <Text>
                "{customer}, I'd like you to meet James — our Sales Manager. James, {customer} is interested in the {vehicle}."
              </Text>
            }
          />
          <AudioWidget
            label="Listening for manager voice + introduction"
            transcript={<AudioTranscript text={transcript} />}
            detection={detection}
          />
        </View>
      </View>
    </Modal>
  );
};

const styles = StyleSheet.create({
  quote: {
    backgroundColor: colors.cardBg,
    borderRadius: 13,
    overflow: 'hidden',
    shadowColor: '#000',
    shadowOpacity: 0.07,
    shadowRadius: 6,
    shadowOffset: { width: 0, height: 2 },
    elevation: 2,
  },
  quoteHeader: {
    backgroundColor: '#f7f8fc',
    paddingHorizontal: 13,
    paddingVertical: 8,
    borderBottomWidth: 0.5,
    borderBottomColor: colors.divider,
  },
  quoteHeaderTx: {
    fontSize: 10,
    fontWeight: '700',
    color: colors.textMuted,
    textTransform: 'uppercase',
    letterSpacing: 0.6,
  },
  quoteRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: 13,
    paddingVertical: 7,
    borderBottomWidth: 0.5,
    borderBottomColor: colors.borderSoft,
  },
  quoteTotal: {
    backgroundColor: colors.primaryLight,
    paddingVertical: 9,
    borderBottomWidth: 0,
  },
  quoteLabel: { fontSize: 12, color: '#000' },
  quoteValue: { fontSize: 12, fontWeight: '600', color: '#000' },
  quoteTotalValue: {
    fontSize: 14,
    fontWeight: '800',
    color: colors.primary,
    fontFamily: fonts.displayHeavy,
  },
  markBtn: {
    backgroundColor: '#1a1a2e',
    borderRadius: 12,
    paddingVertical: 13,
    alignItems: 'center',
    shadowColor: '#000',
    shadowOpacity: 0.3,
    shadowRadius: 16,
    shadowOffset: { width: 0, height: 5 },
    elevation: 5,
  },
  markBtnTx: { color: '#fff', fontSize: 14, fontWeight: '700', fontFamily: fonts.display },
  confirmCard: {
    borderRadius: 13,
    paddingVertical: 13,
    paddingHorizontal: 13,
    alignItems: 'center',
    borderWidth: 1.5,
    borderColor: 'rgba(21,163,84,0.3)',
    gap: 6,
  },
  confirmIco: {
    width: 40,
    height: 40,
    borderRadius: 20,
    backgroundColor: colors.success,
    alignItems: 'center',
    justifyContent: 'center',
    shadowColor: colors.success,
    shadowOpacity: 0.35,
    shadowRadius: 12,
    shadowOffset: { width: 0, height: 4 },
    elevation: 4,
  },
  confirmIcoTx: { color: '#fff', fontSize: 18, fontWeight: '800' },
  confirmTitle: {
    fontSize: 14,
    fontWeight: '700',
    color: colors.successDeep,
    fontFamily: fonts.display,
  },
  confirmSub: { fontSize: 11, color: 'rgba(21,163,84,0.8)' },
  mgrFloat: {
    position: 'absolute',
    right: 14,
    bottom: 75,
    backgroundColor: colors.success,
    paddingHorizontal: 12,
    paddingVertical: 9,
    borderRadius: 12,
    shadowColor: colors.success,
    shadowOpacity: 0.4,
    shadowRadius: 14,
    shadowOffset: { width: 0, height: 4 },
    elevation: 6,
  },
  mgrFloatTx: {
    color: '#fff',
    fontSize: 11,
    fontWeight: '700',
    fontFamily: fonts.display,
  },
  overlay: { flex: 1, backgroundColor: 'rgba(0,0,0,0.5)', justifyContent: 'flex-end' },
  sheet: {
    backgroundColor: '#fff',
    borderTopLeftRadius: 20,
    borderTopRightRadius: 20,
    paddingHorizontal: 16,
    paddingTop: 12,
    paddingBottom: 24,
    gap: 10,
  },
  handle: { width: 36, height: 4, backgroundColor: '#e0e0e8', borderRadius: 2, alignSelf: 'center', marginBottom: 4 },
  sheetHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
  },
  sheetTitle: {
    fontFamily: fonts.displayHeavy,
    fontSize: 16,
    fontWeight: '800',
    color: '#000',
  },
  close: {
    width: 28,
    height: 28,
    borderRadius: 8,
    backgroundColor: '#f0f0f5',
    alignItems: 'center',
    justifyContent: 'center',
  },
  closeTx: { fontSize: 14, color: colors.textMuted },
});
