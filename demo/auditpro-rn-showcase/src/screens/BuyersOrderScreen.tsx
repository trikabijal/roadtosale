import React, { useState } from 'react';
import { View, Text, StyleSheet, Pressable } from 'react-native';
import { useNavigation } from '@react-navigation/native';
import { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { LinearGradient } from 'expo-linear-gradient';
import Animated, {
  useSharedValue,
  useAnimatedStyle,
  withSequence,
  withTiming,
} from 'react-native-reanimated';
import { ScreenChrome } from '../components/ScreenChrome';
import { StepHeading } from '../components/StepHeading';
import { RealCameraSurface } from '../components/RealCameraSurface';
import { useDealStore } from '../store/deal';
import { useServices } from '../services/context';
import { colors } from '../theme';
import { RootStackParamList } from '../navigation/types';

const formatTime = (ts: number) =>
  new Date(ts).toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' });

export const BuyersOrderScreen: React.FC = () => {
  const navigation = useNavigation<NativeStackNavigationProp<RootStackParamList>>();
  const deal = useDealStore((s) => s.deal);
  const setBoolean = useDealStore((s) => s.setBoolean);
  const setTimestamp = useDealStore((s) => s.setTimestamp);
  const services = useServices();
  const flash = useSharedValue(0);
  const [busy, setBusy] = useState(false);

  const flashStyle = useAnimatedStyle(() => ({ opacity: flash.value }));

  const capture = async () => {
    if (deal?.boCaptured || busy) return;
    setBusy(true);
    flash.value = withSequence(withTiming(0.95, { duration: 100 }), withTiming(0, { duration: 250 }));
    try {
      const photo = await services.camera.capturePhoto('buyers-order');
      await services.cv.extractBuyersOrder(photo.uri);
      const now = Date.now();
      await setBoolean('boCaptured', true);
      await setTimestamp('boSigned', now);
    } finally {
      setBusy(false);
    }
  };

  const customer = deal?.setup.customer ?? '—';
  const vehicle = deal?.setup.vehicle ?? '—';
  const captured = Boolean(deal?.boCaptured);
  const ts = deal?.timestamps.boSigned ?? null;

  return (
    <ScreenChrome
      routeName="BuyersOrder"
      onBack={() => navigation.navigate('Pencil')}
      onNext={() => navigation.navigate('FIHandoff')}
      backLabel="← Back"
      nextLabel="F&I Handoff →"
    >
      <StepHeading
        eyebrow="Step 7.2 · OTD Transparency"
        title="Buyer's Order"
        subtitle="Photograph the signed order to confirm OTD figures and start the F&I timer"
      />

      <RealCameraSurface />

      {!captured ? (
        <Pressable style={styles.prompt} onPress={capture}>
          <Animated.View style={[styles.flash, flashStyle]} pointerEvents="none" />
          <Text style={styles.promptIcon}>📄📷</Text>
          <Text style={styles.promptTitle}>Tap to photograph buyer's order</Text>
          <Text style={styles.promptSub}>
            Computer vision will confirm signature and line items
          </Text>
        </Pressable>
      ) : (
        <View style={styles.reveal}>
          <LinearGradient colors={['#EDFAF4', '#E0F8EE']} style={styles.revealHeader}>
            <Text style={{ fontSize: 16 }}>📄</Text>
            <Text style={styles.revealHeaderTx}>Buyer's Order · {customer}</Text>
          </LinearGradient>
          <Row label="Vehicle" value={vehicle} />
          <Row label="Selling Price" value="$41,250" />
          <Row label="Trade Allowance" value="−$18,500" />
          <Row label="Tax + Fees" value="$2,918" />
          <View style={[styles.row, { backgroundColor: colors.successLight }]}>
            <Text style={[styles.rowLabel, { fontWeight: '700', color: '#000', fontSize: 12 }]}>
              Total OTD
            </Text>
            <Text style={[styles.rowValue, { fontWeight: '800', color: colors.primary, fontSize: 13 }]}>
              $25,668
            </Text>
          </View>
          <Text style={styles.sig}>
            ✓ Signed · Captured {ts ? formatTime(ts) : '—'} · Signature confirmed by CV
          </Text>
        </View>
      )}

      {captured ? (
        <View style={styles.banner}>
          <View style={styles.bannerIco}>
            <Text style={styles.bannerIcoTx}>✓</Text>
          </View>
          <Text style={styles.bannerTx}>
            Buyer's order captured · F&I 20-min timer started {ts ? formatTime(ts) : '—'}
          </Text>
        </View>
      ) : null}
    </ScreenChrome>
  );
};

const Row: React.FC<{ label: string; value: string }> = ({ label, value }) => (
  <View style={styles.row}>
    <Text style={styles.rowLabel}>{label}</Text>
    <Text style={styles.rowValue}>{value}</Text>
  </View>
);

const styles = StyleSheet.create({
  prompt: {
    backgroundColor: colors.cardBg,
    borderRadius: 13,
    paddingVertical: 22,
    paddingHorizontal: 20,
    alignItems: 'center',
    justifyContent: 'center',
    borderWidth: 2,
    borderStyle: 'dashed',
    borderColor: colors.borderDashed,
    gap: 10,
    overflow: 'hidden',
  },
  flash: { ...StyleSheet.absoluteFillObject, backgroundColor: '#fff' },
  promptIcon: { fontSize: 32 },
  promptTitle: { fontSize: 14, fontWeight: '600', color: '#000' },
  promptSub: { fontSize: 11, color: colors.textSubtle, textAlign: 'center' },
  reveal: {
    backgroundColor: colors.cardBg,
    borderRadius: 13,
    overflow: 'hidden',
    borderWidth: 1.5,
    borderColor: colors.success,
  },
  revealHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 7,
    paddingHorizontal: 13,
    paddingVertical: 10,
    borderBottomWidth: 0.5,
    borderBottomColor: 'rgba(21,163,84,0.2)',
  },
  revealHeaderTx: { fontSize: 13, fontWeight: '700', color: '#000' },
  row: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    paddingHorizontal: 13,
    paddingVertical: 6,
    borderBottomWidth: 0.5,
    borderBottomColor: colors.borderSoft,
  },
  rowLabel: { fontSize: 11, color: colors.textMuted },
  rowValue: { fontSize: 11, fontWeight: '600', color: '#000' },
  sig: {
    fontSize: 10,
    color: colors.success,
    fontStyle: 'italic',
    paddingHorizontal: 13,
    paddingVertical: 8,
    backgroundColor: colors.successLight,
    fontWeight: '500',
  },
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
