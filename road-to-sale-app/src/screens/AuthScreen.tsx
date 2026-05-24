import React, { useEffect, useState } from 'react';
import {
  ActivityIndicator,
  KeyboardAvoidingView,
  Platform,
  StyleSheet,
  Text,
  TextInput,
  TouchableOpacity,
  View,
} from 'react-native';

import { SmartComplyClient } from '../api/SmartComplyClient';
import { getSmartComplyClient } from '../api/clientSingleton';
import type { AuthScreenProps } from '../navigation/types';
import { useTheme } from '../theme';
import { Typography } from '../theme';

export default function AuthScreen({ navigation }: AuthScreenProps) {
  const { colors } = useTheme();

  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [loading, setLoading] = useState(true);   // true during initial token check
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // ── On mount: skip auth if already logged in ──────────────────────────────
  useEffect(() => {
    SmartComplyClient.hasValidToken().then((hasToken) => {
      if (hasToken) {
        navigation.replace('Home');
      } else {
        setLoading(false);
      }
    });
  }, [navigation]);

  // ── Sign in ───────────────────────────────────────────────────────────────
  async function handleSignIn() {
    if (!username.trim() || !password) {
      setError('Please enter your username and password.');
      return;
    }
    setError(null);
    setSubmitting(true);
    try {
      await getSmartComplyClient().login({
        username: username.trim(),
        password,
        deviceType: 'APP',
      });
      navigation.replace('Home');
    } catch (err: unknown) {
      if (err instanceof Error) {
        setError(err.message);
      } else {
        setError('An unexpected error occurred. Please try again.');
      }
    } finally {
      setSubmitting(false);
    }
  }

  // ── Render: full-screen spinner while checking stored token ───────────────
  if (loading) {
    return (
      <View style={[styles.centered, { backgroundColor: colors.background }]}>
        <ActivityIndicator size="large" color={colors.accent} />
      </View>
    );
  }

  // ── Render: login form ────────────────────────────────────────────────────
  return (
    <KeyboardAvoidingView
      style={[styles.flex, { backgroundColor: colors.background }]}
      behavior={Platform.OS === 'ios' ? 'padding' : 'height'}
      keyboardVerticalOffset={0}
    >
      <View style={styles.inner}>
        {/* ── Logo area ──────────────────────────────────────────────────── */}
        <View style={styles.logoSection}>
          <Text style={[styles.logoTitle, { color: colors.textPrimary }]}>Road to Sale</Text>
          <Text style={[styles.logoSubtitle, { color: colors.accent }]}>by AuditPro</Text>
        </View>

        {/* ── Form ───────────────────────────────────────────────────────── */}
        <View style={styles.form}>
          <Text style={[styles.fieldLabel, { color: colors.textSecondary }]}>Username</Text>
          <TextInput
            style={[
              styles.input,
              {
                backgroundColor: colors.surface,
                borderColor: colors.border,
                color: colors.textPrimary,
              },
            ]}
            value={username}
            onChangeText={setUsername}
            placeholder="Enter username"
            placeholderTextColor={colors.textMuted}
            autoCapitalize="none"
            autoCorrect={false}
            returnKeyType="next"
            textContentType="username"
            accessibilityLabel="Username"
          />

          <Text style={[styles.fieldLabel, { color: colors.textSecondary }]}>Password</Text>
          <TextInput
            style={[
              styles.input,
              {
                backgroundColor: colors.surface,
                borderColor: colors.border,
                color: colors.textPrimary,
              },
            ]}
            value={password}
            onChangeText={setPassword}
            placeholder="Enter password"
            placeholderTextColor={colors.textMuted}
            secureTextEntry
            returnKeyType="done"
            onSubmitEditing={handleSignIn}
            textContentType="password"
            accessibilityLabel="Password"
          />

          {/* ── Error message ─────────────────────────────────────────────── */}
          {error !== null && (
            <Text style={[styles.errorText, { color: colors.error }]} accessibilityRole="alert">
              {error}
            </Text>
          )}

          {/* ── Sign In button ────────────────────────────────────────────── */}
          <TouchableOpacity
            style={[
              styles.signInButton,
              { backgroundColor: submitting ? colors.accentPressed : colors.accent },
            ]}
            onPress={handleSignIn}
            disabled={submitting}
            activeOpacity={0.8}
            accessibilityRole="button"
            accessibilityLabel="Sign In"
          >
            {submitting ? (
              <ActivityIndicator color="#FFFFFF" />
            ) : (
              <Text style={styles.signInButtonText}>Sign In</Text>
            )}
          </TouchableOpacity>
        </View>
      </View>
    </KeyboardAvoidingView>
  );
}

const styles = StyleSheet.create({
  flex: {
    flex: 1,
  },
  centered: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
  },
  inner: {
    flex: 1,
    justifyContent: 'center',
    paddingHorizontal: 28,
    paddingBottom: 40,
  },

  // Logo
  logoSection: {
    alignItems: 'center',
    marginBottom: 48,
  },
  logoTitle: {
    fontSize: 34,
    fontWeight: '700',
    letterSpacing: -0.5,
  },
  logoSubtitle: {
    ...Typography.body,
    fontWeight: '500',
    marginTop: 4,
  },

  // Form
  form: {
    width: '100%',
  },
  fieldLabel: {
    ...Typography.captionMedium,
    marginBottom: 6,
    marginTop: 16,
  },
  input: {
    height: 52,
    borderRadius: 10,
    borderWidth: 1,
    paddingHorizontal: 16,
    ...Typography.body,
  },
  errorText: {
    ...Typography.caption,
    marginTop: 12,
    textAlign: 'center',
  },
  signInButton: {
    height: 52,
    borderRadius: 10,
    justifyContent: 'center',
    alignItems: 'center',
    marginTop: 28,
  },
  signInButtonText: {
    ...Typography.buttonLarge,
    color: '#FFFFFF',
  },
});
