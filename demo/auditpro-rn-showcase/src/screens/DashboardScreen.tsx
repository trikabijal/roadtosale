import React from 'react';
import { View, Text, StyleSheet, Pressable, ScrollView } from 'react-native';
import { useNavigation } from '@react-navigation/native';
import { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { LinearGradient } from 'expo-linear-gradient';
import { colors, fonts } from '../theme';
import { useDealStore } from '../store/deal';
import { ScreenChrome } from '../components/ScreenChrome';
import { RootStackParamList } from '../navigation/types';

const Stat: React.FC<{ label: string; value: string; tone?: 'blue' | 'green' | 'red' | 'default' }>
= ({ label, value, tone = 'default' }) => (
  <View style={styles.stat}>
    <Text style={styles.statLabel}>{label}</Text>
    <Text
      style={[
        styles.statValue,
        tone === 'blue' && { color: colors.primary },
        tone === 'green' && { color: colors.success },
        tone === 'red' && { color: colors.error },
      ]}
    >
      {value}
    </Text>
  </View>
);

export const DashboardScreen: React.FC = () => {
  const navigation = useNavigation<NativeStackNavigationProp<RootStackParamList>>();
  const deal = useDealStore((s) => s.deal);
  const customer = deal?.setup.customer ?? '—';
  const vehicle = deal?.setup.vehicle ?? '—';
  const repFirstName = (deal?.setup.rep ?? '').split(' ')[0] || '—';

  return (
    <ScreenChrome routeName="Dashboard" hideNav>
      <ScrollView
        style={{ flex: 1, marginHorizontal: -16, marginTop: -13 }}
        contentContainerStyle={styles.scroll}
        showsVerticalScrollIndicator={false}
      >
        <LinearGradient
          colors={[colors.primary, '#3B72F8']}
          start={{ x: 0, y: 0 }}
          end={{ x: 1, y: 1 }}
          style={styles.hero}
        >
          <Text style={styles.heroGreet}>Good morning 👋</Text>
          <Text style={styles.heroName}>{repFirstName}</Text>
          <Text style={styles.heroSub}>{deal?.setup.dealership ?? '—'}</Text>
        </LinearGradient>

        <View style={styles.statGrid}>
          <Stat label="Deals Today" value="3" tone="blue" />
          <Stat label="Compliance" value="87%" tone="green" />
          <Stat label="Steps Done" value="21" />
          <Stat label="Flags" value="2" tone="red" />
        </View>

        <Text style={styles.sectionLabel}>Active Deals</Text>

        <Pressable
          style={({ pressed }) => [styles.dealCard, pressed && { borderColor: colors.primary }]}
          onPress={() => navigation.navigate('Greet')}
        >
          <View style={styles.dealRow}>
            <Text style={styles.dealName}>{customer}</Text>
            <View style={styles.badge}>
              <Text style={styles.badgeTx}>In Progress</Text>
            </View>
          </View>
          <Text style={styles.dealCar}>{vehicle}</Text>
          <View style={styles.miniBar}>
            <View style={[styles.miniFill, { width: '14%' }]} />
          </View>
        </Pressable>

        <Pressable style={styles.cta} onPress={() => navigation.navigate('Greet')}>
          <Text style={styles.ctaTx}>+ New Walk-In</Text>
        </Pressable>
      </ScrollView>
    </ScreenChrome>
  );
};

const styles = StyleSheet.create({
  scroll: { paddingBottom: 24 },
  hero: {
    paddingHorizontal: 16,
    paddingVertical: 16,
    overflow: 'hidden',
  },
  heroGreet: { fontSize: 11, color: 'rgba(255,255,255,0.7)', marginBottom: 3 },
  heroName: {
    fontFamily: fonts.displayHeavy,
    fontSize: 20,
    fontWeight: '800',
    color: '#fff',
    letterSpacing: -0.3,
  },
  heroSub: { fontSize: 11, color: 'rgba(255,255,255,0.65)' },
  statGrid: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 7,
    paddingHorizontal: 16,
    paddingVertical: 12,
  },
  stat: {
    flexBasis: '48%',
    flexGrow: 1,
    backgroundColor: '#fff',
    borderRadius: 12,
    paddingHorizontal: 12,
    paddingVertical: 10,
    shadowColor: '#000',
    shadowOpacity: 0.08,
    shadowRadius: 4,
    shadowOffset: { width: 0, height: 1 },
    elevation: 1,
  },
  statLabel: { fontSize: 10, color: colors.textSubtle, marginBottom: 2 },
  statValue: {
    fontFamily: fonts.display,
    fontSize: 24,
    fontWeight: '700',
    color: '#000',
    letterSpacing: -0.3,
  },
  sectionLabel: {
    fontSize: 10,
    fontWeight: '600',
    color: colors.textSubtle,
    textTransform: 'uppercase',
    letterSpacing: 0.6,
    paddingHorizontal: 16,
    marginBottom: 6,
  },
  dealCard: {
    marginHorizontal: 16,
    marginBottom: 8,
    backgroundColor: '#fff',
    borderRadius: 12,
    paddingHorizontal: 13,
    paddingVertical: 11,
    shadowColor: '#000',
    shadowOpacity: 0.08,
    shadowRadius: 4,
    shadowOffset: { width: 0, height: 1 },
    elevation: 1,
    borderWidth: 1.5,
    borderColor: 'transparent',
  },
  dealRow: { flexDirection: 'row', justifyContent: 'space-between', marginBottom: 4 },
  dealName: { fontSize: 14, fontWeight: '600', color: '#000' },
  badge: {
    backgroundColor: colors.badgeBg,
    paddingHorizontal: 7,
    paddingVertical: 2,
    borderRadius: 20,
  },
  badgeTx: { fontSize: 10, color: colors.primary, fontWeight: '600' },
  dealCar: { fontSize: 12, color: colors.textMuted, marginBottom: 6 },
  miniBar: { height: 3, backgroundColor: '#f0f0f5', borderRadius: 2, overflow: 'hidden' },
  miniFill: { height: '100%', backgroundColor: colors.primary, borderRadius: 2 },
  cta: {
    marginHorizontal: 16,
    marginTop: 8,
    backgroundColor: colors.primary,
    borderRadius: 13,
    paddingVertical: 14,
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
