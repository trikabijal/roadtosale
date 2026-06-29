import React from 'react';
import { SafeAreaProvider } from 'react-native-safe-area-context';
import JustTalkScreen from './src/JustTalkScreen';

export default function App() {
  return (
    <SafeAreaProvider>
      <JustTalkScreen />
    </SafeAreaProvider>
  );
}
