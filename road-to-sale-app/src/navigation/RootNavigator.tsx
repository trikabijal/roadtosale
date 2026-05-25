import React, { useEffect } from 'react';
import { Alert } from 'react-native';
import { NavigationContainer, useNavigationContainerRef } from '@react-navigation/native';
import { createNativeStackNavigator } from '@react-navigation/native-stack';
import { useTheme } from '../theme';
import type { RootStackParamList } from './types';
import { getSessionRepository } from '../db/repositorySingleton';

import AuthScreen from '../screens/AuthScreen';
import HomeScreen from '../screens/HomeScreen';
import SessionSetupScreen from '../screens/SessionSetupScreen';
import ActiveSessionScreen from '../screens/ActiveSessionScreen';
import TradeInScreen from '../screens/TradeInScreen';
import SessionSummaryScreen from '../screens/SessionSummaryScreen';
import HistoryScreen from '../screens/HistoryScreen';

const Stack = createNativeStackNavigator<RootStackParamList>();

export default function RootNavigator() {
  const { colors, isDark } = useTheme();
  const navigationRef = useNavigationContainerRef<RootStackParamList>();

  // ── Task 9.3: crash recovery on mount ────────────────────────────────────
  useEffect(() => {
    (async () => {
      try {
        const activeSessions = await getSessionRepository().getActiveSessions();
        if (activeSessions.length === 0) return;
        const session = activeSessions[0];
        Alert.alert(
          'Session in Progress',
          'A session was interrupted. Would you like to resume it?',
          [
            {
              text: 'Discard',
              style: 'destructive',
              onPress: async () => {
                try {
                  await getSessionRepository().updateSession({ id: session.id, status: 'crashed' });
                } catch {
                  // silently ignore — session will remain active but we navigated away
                }
              },
            },
            {
              text: 'Resume',
              onPress: () => {
                navigationRef.current?.navigate('ActiveSession', { sessionId: session.id });
              },
            },
          ],
        );
      } catch {
        // repo not ready or unavailable — skip recovery silently
      }
    })();
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  return (
    <NavigationContainer
      ref={navigationRef}
      theme={{
        dark: isDark,
        colors: {
          primary: colors.accent,
          background: colors.background,
          card: colors.surface,
          text: colors.textPrimary,
          border: colors.border,
          notification: colors.error,
        },
        fonts: {
          regular: { fontFamily: 'System', fontWeight: '400' },
          medium: { fontFamily: 'System', fontWeight: '500' },
          bold: { fontFamily: 'System', fontWeight: '700' },
          heavy: { fontFamily: 'System', fontWeight: '900' },
        },
      }}
    >
      <Stack.Navigator
        initialRouteName="Auth"
        screenOptions={{
          headerStyle: { backgroundColor: colors.surface },
          headerTintColor: colors.textPrimary,
          headerShadowVisible: false,
          contentStyle: { backgroundColor: colors.background },
        }}
      >
        <Stack.Screen name="Auth" component={AuthScreen} options={{ headerShown: false }} />
        <Stack.Screen name="Home" component={HomeScreen} options={{ title: 'Road to Sale', headerLeft: () => null }} />
        <Stack.Screen name="SessionSetup" component={SessionSetupScreen} options={{ title: 'New Session' }} />
        <Stack.Screen name="ActiveSession" component={ActiveSessionScreen} options={{ title: 'Session', headerBackVisible: false }} />
        <Stack.Screen name="TradeIn" component={TradeInScreen} options={{ title: 'Trade-In' }} />
        <Stack.Screen name="SessionSummary" component={SessionSummaryScreen} options={{ title: 'Session Summary' }} />
        <Stack.Screen name="History" component={HistoryScreen} options={{ title: 'History' }} />
      </Stack.Navigator>
    </NavigationContainer>
  );
}
