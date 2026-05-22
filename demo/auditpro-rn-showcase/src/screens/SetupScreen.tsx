import React, { useState } from 'react';
import {
  View,
  Text,
  TextInput,
  StyleSheet,
  Pressable,
  KeyboardAvoidingView,
  Platform,
  ScrollView,
} from 'react-native';
import { useNavigation } from '@react-navigation/native';
import { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { colors, fonts } from '../theme';
import { useDealStore } from '../store/deal';
import { RootStackParamList } from '../navigation/types';

export const SetupScreen: React.FC = () => {
  const navigation = useNavigation<NativeStackNavigationProp<RootStackParamList>>();
  const startDeal = useDealStore((s) => s.startDeal);
  const [dealership, setDealership] = useState('');
  const [rep, setRep] = useState('');
  const [customer, setCustomer] = useState('');
  const [vehicle, setVehicle] = useState('');
  const [busy, setBusy] = useState(false);

  const canSubmit =
    dealership.trim() !== '' &&
    rep.trim() !== '' &&
    customer.trim() !== '' &&
    vehicle.trim() !== '';

  const launch = async () => {
    if (busy || !canSubmit) return;
    setBusy(true);
    try {
      await startDeal({
        dealership: dealership.trim(),
        rep: rep.trim(),
        customer: customer.trim(),
        vehicle: vehicle.trim(),
      });
      navigation.replace('Dashboard');
    } finally {
      setBusy(false);
    }
  };

  return (
    <KeyboardAvoidingView
      style={styles.root}
      behavior={Platform.OS === 'ios' ? 'padding' : undefined}
    >
      <ScrollView
        contentContainerStyle={styles.scroll}
        keyboardShouldPersistTaps="handled"
        showsVerticalScrollIndicator={false}
      >
        <View style={styles.card}>
          <View style={styles.logoRow}>
            <View style={styles.logoMark}>
              <Text style={styles.logoTick}>✓</Text>
            </View>
            <Text style={styles.logoText}>AuditPro</Text>
          </View>

          <Text style={styles.heading}>New audit</Text>
          <Text style={styles.subheading}>
            Enter the deal details — these populate through every audit step.
          </Text>

          <Field
            label="Dealership name"
            value={dealership}
            onChange={setDealership}
            placeholder="e.g. Riverside Honda"
          />
          <Field
            label="Sales rep name"
            value={rep}
            onChange={setRep}
            placeholder="Your name"
          />
          <Field
            label="Customer name"
            value={customer}
            onChange={setCustomer}
            placeholder="Customer's full name"
          />
          <Field
            label="Vehicle"
            value={vehicle}
            onChange={setVehicle}
            placeholder="e.g. 2025 Honda CR-V EX-L"
          />

          <Pressable
            style={({ pressed }) => [
              styles.cta,
              pressed && styles.ctaPressed,
              !canSubmit && styles.ctaDisabled,
            ]}
            onPress={launch}
            disabled={!canSubmit || busy}
          >
            <Text style={styles.ctaTx}>{busy ? 'Starting…' : 'Begin Audit →'}</Text>
          </Pressable>
        </View>
      </ScrollView>
    </KeyboardAvoidingView>
  );
};

const Field: React.FC<{
  label: string;
  value: string;
  onChange: (v: string) => void;
  placeholder?: string;
}> = ({ label, value, onChange, placeholder }) => (
  <View style={styles.field}>
    <Text style={styles.fieldLabel}>{label}</Text>
    <TextInput
      style={styles.input}
      value={value}
      onChangeText={onChange}
      placeholder={placeholder}
      placeholderTextColor="rgba(255,255,255,0.25)"
      autoCorrect={false}
      autoCapitalize="words"
    />
  </View>
);

const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: colors.setupBg },
  scroll: {
    flexGrow: 1,
    justifyContent: 'center',
    paddingHorizontal: 24,
    paddingVertical: 32,
  },
  card: {
    backgroundColor: colors.setupCard,
    borderColor: colors.setupBorder,
    borderWidth: 1,
    borderRadius: 28,
    padding: 32,
    width: '100%',
    maxWidth: 460,
    alignSelf: 'center',
    shadowColor: '#000',
    shadowOpacity: 0.6,
    shadowRadius: 40,
    shadowOffset: { width: 0, height: 20 },
    elevation: 10,
  },
  logoRow: { flexDirection: 'row', alignItems: 'center', gap: 10, marginBottom: 32 },
  logoMark: {
    width: 38,
    height: 38,
    backgroundColor: colors.primary,
    borderRadius: 11,
    alignItems: 'center',
    justifyContent: 'center',
    shadowColor: colors.primary,
    shadowOpacity: 0.5,
    shadowRadius: 14,
    shadowOffset: { width: 0, height: 4 },
    elevation: 5,
  },
  logoTick: { color: '#fff', fontSize: 18, fontWeight: '800' },
  logoText: {
    fontFamily: fonts.displayHeavy,
    fontSize: 22,
    fontWeight: '800',
    color: '#fff',
    letterSpacing: -0.5,
  },
  heading: {
    fontFamily: fonts.display,
    fontSize: 28,
    fontWeight: '700',
    color: '#fff',
    lineHeight: 32,
    marginBottom: 6,
  },
  subheading: {
    fontSize: 14,
    color: colors.setupTextDim,
    marginBottom: 26,
    lineHeight: 22,
  },
  field: { marginBottom: 14 },
  fieldLabel: {
    fontSize: 10,
    fontWeight: '600',
    color: 'rgba(255,255,255,0.25)',
    letterSpacing: 1.4,
    textTransform: 'uppercase',
    marginBottom: 6,
  },
  input: {
    backgroundColor: colors.setupInputBg,
    borderColor: colors.setupInputBorder,
    borderWidth: 1,
    borderRadius: 12,
    paddingHorizontal: 16,
    paddingVertical: 12,
    color: '#fff',
    fontSize: 15,
  },
  cta: {
    backgroundColor: colors.primary,
    borderRadius: 13,
    paddingVertical: 15,
    alignItems: 'center',
    justifyContent: 'center',
    marginTop: 12,
    shadowColor: colors.primary,
    shadowOpacity: 0.4,
    shadowRadius: 20,
    shadowOffset: { width: 0, height: 6 },
    elevation: 6,
  },
  ctaPressed: { backgroundColor: colors.primaryDark, transform: [{ translateY: -1 }] },
  ctaDisabled: { opacity: 0.4 },
  ctaTx: {
    color: '#fff',
    fontFamily: fonts.display,
    fontSize: 15,
    fontWeight: '700',
  },
});
