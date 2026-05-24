import React from 'react';
import { NavigationContainer } from '@react-navigation/native';
import { createNativeStackNavigator } from '@react-navigation/native-stack';
import { useTheme } from '../theme';
import type { RootStackParamList } from './types';

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

  return (
    <NavigationContainer
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
