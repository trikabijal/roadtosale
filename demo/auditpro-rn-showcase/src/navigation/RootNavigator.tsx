import React from 'react';
import { createNativeStackNavigator } from '@react-navigation/native-stack';
import { RootStackParamList } from './types';
import { SetupScreen } from '../screens/SetupScreen';
import { DashboardScreen } from '../screens/DashboardScreen';
import { GreetScreen } from '../screens/GreetScreen';
import { DiscoveryScreen } from '../screens/DiscoveryScreen';
import { FeatureMatchScreen } from '../screens/FeatureMatchScreen';
import { FrontLineReadyScreen } from '../screens/FrontLineReadyScreen';
import { WalkaroundScreen } from '../screens/WalkaroundScreen';
import { TestDriveScreen } from '../screens/TestDriveScreen';
import { TradeInScreen } from '../screens/TradeInScreen';
import { PencilScreen } from '../screens/PencilScreen';
import { BuyersOrderScreen } from '../screens/BuyersOrderScreen';
import { FIHandoffScreen } from '../screens/FIHandoffScreen';
import { AuditCompleteScreen } from '../screens/AuditCompleteScreen';

const Stack = createNativeStackNavigator<RootStackParamList>();

export const RootNavigator: React.FC = () => (
  <Stack.Navigator
    initialRouteName="Setup"
    screenOptions={{
      headerShown: false,
      animation: 'slide_from_right',
      contentStyle: { backgroundColor: '#f1f1f6' },
    }}
  >
    <Stack.Screen name="Setup" component={SetupScreen} />
    <Stack.Screen name="Dashboard" component={DashboardScreen} />
    <Stack.Screen name="Greet" component={GreetScreen} />
    <Stack.Screen name="Discovery" component={DiscoveryScreen} />
    <Stack.Screen name="FeatureMatch" component={FeatureMatchScreen} />
    <Stack.Screen name="FrontLineReady" component={FrontLineReadyScreen} />
    <Stack.Screen name="Walkaround" component={WalkaroundScreen} />
    <Stack.Screen name="TestDrive" component={TestDriveScreen} />
    <Stack.Screen name="TradeIn" component={TradeInScreen} />
    <Stack.Screen name="Pencil" component={PencilScreen} />
    <Stack.Screen name="BuyersOrder" component={BuyersOrderScreen} />
    <Stack.Screen name="FIHandoff" component={FIHandoffScreen} />
    <Stack.Screen name="AuditComplete" component={AuditCompleteScreen} />
  </Stack.Navigator>
);
