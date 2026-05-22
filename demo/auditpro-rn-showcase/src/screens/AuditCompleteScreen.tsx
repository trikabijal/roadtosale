import React, { useEffect, useState } from 'react';
import { View, Text, StyleSheet, Pressable, ScrollView } from 'react-native';
import { useNavigation } from '@react-navigation/native';
import { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { LinearGradient } from 'expo-linear-gradient';
import { ScreenChrome } from '../components/ScreenChrome';
import { useDealStore } from '../store/deal';
import { useServices } from '../services/context';
import { AuditScore } from '../types';
import { colors, fonts } from '../theme';
import { RootStackParamList } from '../navigation/types';

export const AuditCompleteScreen: React.FC = () => {
  const navigation = useNavigation<NativeStackNavigationProp<RootStackParamList>>();
  const deal = useDealStore((s) => s.deal);
  const markCompleted = useDealStore((s) => s.markCompleted);
  const services = useServices();
  const [score, setScore] = useState<AuditScore | null>(null);

  useEffect(() => {
    let mounted = true;
    (async () => {
      if (!deal) return;
      const computed = await services.backend.computeScore(deal.id);
      if (mounted) setScore(computed);
      await markCompleted();
    })();
    return () => {
      mounted = false;
    };
    // We intentionally re-run only when the deal id changes; markCompleted
    // and services.backend are stable for the lifetime of the screen and
    // including them would re-run on every render.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [deal?.id]);

  const customer = deal?.setup.customer ?? '—';
  const vehicle = deal?.setup.vehicle ?? '—';

  return (
    <ScreenChrome routeName="AuditComplete" hideNav>
      <ScrollView
        style={{ flex: 1, marginHorizontal: -16, marginTop: -13 }}
        contentContainerStyle={styles.scroll}
        showsVerticalScrollIndicator={false}
      >
        <LinearGradient
          colors={[colors.primary, '#3B72F8']}
          start={{ x: 0, y: 0 }}
          end={{ x: 1, y: 1 }}
          style={styles.scoreCircle}
        >
          <Text style={styles.scoreNum}>{score?.score ?? '—'}%</Text>
          <Text style={styles.scoreLbl}>Score</Text>
        </LinearGradient>

        <Text style={styles.title}>Audit Complete</Text>
        <Text style={styles.sub}>
          {customer} · {vehicle}
        </Text>

        <View style={styles.table}>
          {(score?.items ?? []).map((it, i, arr) => (
            <View
              key={it.id}
              style={[styles.row, i === arr.length - 1 && { borderBottomWidth: 0 }]}
            >
              <Text style={styles.rowLabel}>{it.label}</Text>
              {it.passed ? (
                <Text style={styles.pass}>
                  ✓ {sourceLabel(it.source, it.detail)}
                </Text>
              ) : (
                <Text style={styles.fail}>⚑ {it.detail ?? 'missed'}</Text>
              )}
            </View>
          ))}
        </View>

        <Pressable style={styles.cta} onPress={() => navigation.navigate('Dashboard')}>
          <Text style={styles.ctaTx}>← Back to Dashboard</Text>
        </Pressable>
      </ScrollView>
    </ScreenChrome>
  );
};

function sourceLabel(source: string, detail?: string) {
  if (detail) return detail;
  switch (source) {
    case 'auto':
      return 'Auto';
    case 'photo':
      return 'Photo';
    case 'gps':
      return 'GPS';
    case 'cv':
      return 'Photo+CV';
    case 'timestamp':
      return 'Logged';
    case 'feature':
      return 'Pass';
    default:
      return 'Pass';
  }
}

const styles = StyleSheet.create({
  scroll: { paddingHorizontal: 16, paddingTop: 16, paddingBottom: 24, alignItems: 'center' },
  scoreCircle: {
    width: 86,
    height: 86,
    borderRadius: 43,
    alignItems: 'center',
    justifyContent: 'center',
    marginBottom: 12,
    shadowColor: colors.primary,
    shadowOpacity: 0.4,
    shadowRadius: 18,
    shadowOffset: { width: 0, height: 8 },
    elevation: 6,
  },
  scoreNum: {
    fontSize: 28,
    fontWeight: '800',
    color: '#fff',
    fontFamily: fonts.displayHeavy,
  },
  scoreLbl: {
    fontSize: 9,
    color: 'rgba(255,255,255,0.65)',
    textTransform: 'uppercase',
    letterSpacing: 0.9,
  },
  title: {
    fontSize: 18,
    fontWeight: '700',
    color: '#000',
    fontFamily: fonts.display,
    marginBottom: 3,
  },
  sub: { fontSize: 11, color: colors.textMuted, marginBottom: 12 },
  table: {
    width: '100%',
    backgroundColor: '#fff',
    borderRadius: 13,
    overflow: 'hidden',
    marginBottom: 12,
    shadowColor: '#000',
    shadowOpacity: 0.07,
    shadowRadius: 6,
    shadowOffset: { width: 0, height: 2 },
    elevation: 2,
  },
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: 13,
    paddingVertical: 8,
    borderBottomWidth: 0.5,
    borderBottomColor: colors.borderSoft,
  },
  rowLabel: { fontSize: 12, color: '#000' },
  pass: { fontSize: 11, fontWeight: '600', color: colors.success },
  fail: { fontSize: 11, fontWeight: '600', color: colors.error },
  cta: {
    width: '100%',
    backgroundColor: colors.primary,
    borderRadius: 13,
    paddingVertical: 13,
    alignItems: 'center',
    shadowColor: colors.primary,
    shadowOpacity: 0.3,
    shadowRadius: 16,
    shadowOffset: { width: 0, height: 5 },
    elevation: 4,
  },
  ctaTx: {
    color: '#fff',
    fontFamily: fonts.display,
    fontSize: 14,
    fontWeight: '700',
  },
});
