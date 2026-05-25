import 'react-native-gesture-handler';
import React from 'react';
import { StatusBar } from 'expo-status-bar';
import RootNavigator from './src/navigation/RootNavigator';
import { useRetryQueueConsumer } from './src/db/RetryQueueConsumer';

export default function App() {
  useRetryQueueConsumer();

  return (
    <>
      <StatusBar style="auto" />
      <RootNavigator />
    </>
  );
}
