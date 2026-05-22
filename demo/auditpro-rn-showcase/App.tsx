import React, { useEffect, useMemo, useState } from 'react';
import { ActivityIndicator, StyleSheet, View, Text } from 'react-native';
import { NavigationContainer } from '@react-navigation/native';
import { StatusBar } from 'expo-status-bar';
import { GestureHandlerRootView } from 'react-native-gesture-handler';
import { SafeAreaProvider } from 'react-native-safe-area-context';
import {
  useFonts,
  Syne_700Bold,
  Syne_800ExtraBold,
} from '@expo-google-fonts/syne';
import { RootNavigator } from './src/navigation/RootNavigator';
import { ServicesProvider } from './src/services/context';
import { buildServices, getAppMode, Services } from './src/services/composition';
import { useDealStore } from './src/store/deal';
import { colors } from './src/theme';
import { ErrorBoundary } from './src/components/ErrorBoundary';

// Root must wrap AppInner so the ErrorBoundary can catch render errors
// originating in AppInner itself — including buildServices() throwing when a
// future production mode hits a missing real provider.
export default function App() {
  return (
    <ErrorBoundary>
      <AppInner />
    </ErrorBoundary>
  );
}

function AppInner() {
  const [fontsLoaded] = useFonts({
    Syne_700Bold,
    Syne_800ExtraBold,
  });

  const services = useMemo<Services>(() => buildServices(getAppMode()), []);
  const bind = useDealStore((s) => s.bind);
  const [backendReady, setBackendReady] = useState(false);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      await services.backend.init();
      if (cancelled) return;
      bind(services.backend);
      const user = await services.auth.getCurrentUser();
      if (cancelled) return;
      if (user) services.telemetry.identify(user.id);
      services.telemetry.track('app_launched');
      setBackendReady(true);
    })();
    return () => {
      cancelled = true;
    };
  }, [services, bind]);

  if (!fontsLoaded || !backendReady) {
    return (
      <View style={styles.loader}>
        <ActivityIndicator size="large" color={colors.primary} />
        <Text style={styles.loaderTx}>Loading AuditPro…</Text>
      </View>
    );
  }

  return (
    <GestureHandlerRootView style={{ flex: 1 }}>
      <SafeAreaProvider>
        <ServicesProvider services={services}>
          <NavigationContainer>
            <StatusBar style="dark" />
            <RootNavigator />
          </NavigationContainer>
        </ServicesProvider>
      </SafeAreaProvider>
    </GestureHandlerRootView>
  );
}

const styles = StyleSheet.create({
  loader: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: colors.setupBg,
    gap: 12,
  },
  loaderTx: { color: '#fff', fontSize: 14 },
});
