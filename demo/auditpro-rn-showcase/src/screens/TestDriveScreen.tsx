import React, { useEffect } from 'react';
import { View, Text, StyleSheet } from 'react-native';
import { useNavigation } from '@react-navigation/native';
import { NativeStackNavigationProp } from '@react-navigation/native-stack';
import Animated, {
  useSharedValue,
  useAnimatedStyle,
  withRepeat,
  withTiming,
  Easing,
} from 'react-native-reanimated';
import { ScreenChrome } from '../components/ScreenChrome';
import { StepHeading } from '../components/StepHeading';
import { ScriptCard } from '../components/ScriptCard';
import { AudioWidget } from '../components/AudioWidget';
import { AudioTranscript } from '../components/AudioTranscript';
import { YNCard } from '../components/YNCard';
import { useAudioScript } from '../hooks/useAudioScript';
import { useDealStore } from '../store/deal';
import { useServices } from '../services/context';
import { colors } from '../theme';
import { RootStackParamList } from '../navigation/types';

const Pulse: React.FC = () => {
  const v = useSharedValue(1);
  useEffect(() => {
    v.value = withRepeat(withTiming(2.4, { duration: 2000, easing: Easing.out(Easing.ease) }), -1, false);
  }, [v]);
  const style = useAnimatedStyle(() => ({
    transform: [{ scale: v.value }],
    opacity: 0.35 - (v.value - 1) * 0.25,
  }));
  return <Animated.View style={[styles.pulse, style]} />;
};

// Route-match threshold for marking 5.1 satisfied. Real GPS will compare the
// driven path against the dealership's registered demo loop; until that lands
// the scripted GPS reports a fixed 94% on stop, so any non-zero stop counts.
const ROUTE_MATCH_THRESHOLD = 70;

export const TestDriveScreen: React.FC = () => {
  const navigation = useNavigation<NativeStackNavigationProp<RootStackParamList>>();
  const audio = useAudioScript('test_drive', '5');
  const deal = useDealStore((s) => s.deal);
  const setFlag = useDealStore((s) => s.setFlag);
  const setBoolean = useDealStore((s) => s.setBoolean);
  const services = useServices();

  useEffect(() => {
    services.gps.startTracking('test-drive').catch((err) => {
       
      console.error('[AuditPro] gps.startTracking failed', err);
    });
    return () => {
      services.gps.stopTracking().catch(() => {
        // best-effort cleanup
      });
    };
  }, [services.gps]);

  useEffect(() => {
    if (audio.detection?.stepId === '5.2' && audio.detection.type === 'auto-confirm') {
      const current = deal?.flags['dr1'];
      if (!current || current.value !== 'no') setFlag('dr1', 'yes', 'auto');
    }
  }, [audio.detection, deal?.flags, setFlag]);

  const dr1 = deal?.flags['dr1']?.value ?? null;
  const dr1Source = deal?.flags['dr1']?.source;

  const completeAndAdvance = async () => {
    try {
      const snapshot = await services.gps.stopTracking();
      if (snapshot.matchPercent >= ROUTE_MATCH_THRESHOLD) {
        await setBoolean('routeCompleted', true);
      }
    } catch (err) {
       
      console.error('[AuditPro] gps.stopTracking failed', err);
    }
    navigation.navigate('TradeIn');
  };

  return (
    <ScreenChrome
      routeName="TestDrive"
      onBack={() => navigation.navigate('Walkaround')}
      onNext={completeAndAdvance}
      backLabel="← Back"
      nextLabel="Trade-In →"
    >
      <StepHeading eyebrow="Step 5 · Test Drive" title="Test Drive Route" />

      <ScriptCard
        label="Script — during drive"
        body={
          <Text>
            "Notice how the car just nudged us back into the lane? That's Lane Assist — always on at highway speeds."
          </Text>
        }
      />

      <View style={styles.gps}>
        <View style={styles.route} />
        <View style={[styles.dot, styles.start]} />
        <View style={[styles.dot, styles.end]} />
        <Pulse />
        <Text style={styles.gpsLabel}>📍 GPS tracking — 8.4 km · route match 94%</Text>
      </View>

      <AudioWidget
        label="In-vehicle audio active"
        transcript={<AudioTranscript text={audio.transcript} />}
        detection={
          audio.detection?.stepId === '5.2'
            ? 'Live feature explanation detected — 5.2 confirmed'
            : null
        }
      />

      <YNCard
        question="5.2 — Did you explain a feature while it was active?"
        value={dr1}
        source={dr1Source}
        onChange={(v) => setFlag('dr1', v, 'manual')}
        autoOverride={dr1 === 'yes' && dr1Source === 'auto' ? 'Auto-detected · tap to override' : undefined}
      />
    </ScreenChrome>
  );
};

const styles = StyleSheet.create({
  gps: {
    backgroundColor: '#E4EDF5',
    borderRadius: 12,
    height: 110,
    position: 'relative',
  },
  route: {
    position: 'absolute',
    top: 20,
    left: 20,
    right: 20,
    bottom: 20,
    borderWidth: 2,
    borderStyle: 'dashed',
    borderColor: 'rgba(29,82,232,0.45)',
    borderRadius: 9,
  },
  dot: {
    position: 'absolute',
    width: 11,
    height: 11,
    borderRadius: 11,
    borderWidth: 2.5,
    borderColor: '#fff',
  },
  start: { top: 18, left: 18, backgroundColor: colors.primary },
  end: { bottom: 40, right: 32, backgroundColor: colors.success },
  pulse: {
    position: 'absolute',
    bottom: 36,
    right: 28,
    width: 18,
    height: 18,
    borderRadius: 18,
    borderWidth: 2,
    borderColor: colors.success,
  },
  gpsLabel: {
    position: 'absolute',
    bottom: 7,
    left: 0,
    right: 0,
    textAlign: 'center',
    fontSize: 10,
    color: colors.primary,
    fontWeight: '600',
  },
});
