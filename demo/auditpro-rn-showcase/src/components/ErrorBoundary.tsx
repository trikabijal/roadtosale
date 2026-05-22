import React from 'react';
import { Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import { colors, fonts } from '../theme';

type Props = {
  children: React.ReactNode;
  onReset?: () => void;
};

type State = {
  error: Error | null;
};

// Surfaces render-time crashes with a visible recovery UI instead of the
// React Native white screen. The boundary itself must never throw.
//
// Production users see a polite "Something went wrong" with a Retry; only
// __DEV__ shows the error name, message, and stack so we don't leak internals
// to a rep on a customer-facing device.
export class ErrorBoundary extends React.Component<Props, State> {
  state: State = { error: null };

  static getDerivedStateFromError(error: Error): State {
    return { error };
  }

  componentDidCatch(error: Error, info: React.ErrorInfo) {
     
    console.error('[AuditPro] Render crash:', error, info.componentStack);
  }

  handleReset = () => {
    this.setState({ error: null });
    this.props.onReset?.();
  };

  render() {
    if (!this.state.error) return this.props.children;
    return (
      <View style={styles.container}>
        <Text style={styles.title}>Something went wrong</Text>
        <Text style={styles.sub}>
          AuditPro hit an unexpected error. Tap retry to return to the audit.
        </Text>
        {__DEV__ ? (
          <ScrollView style={styles.errorBox} contentContainerStyle={styles.errorBoxInner}>
            <Text style={styles.errorName}>{this.state.error.name}</Text>
            <Text style={styles.errorMsg}>{this.state.error.message}</Text>
            {this.state.error.stack ? (
              <Text style={styles.stack}>{this.state.error.stack}</Text>
            ) : null}
          </ScrollView>
        ) : null}
        <Pressable style={styles.cta} onPress={this.handleReset}>
          <Text style={styles.ctaTx}>Retry</Text>
        </Pressable>
      </View>
    );
  }
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: colors.setupBg,
    padding: 24,
    justifyContent: 'center',
    gap: 12,
  },
  title: {
    color: '#fff',
    fontSize: 22,
    fontWeight: '800',
    fontFamily: fonts.displayHeavy,
  },
  sub: { color: 'rgba(255,255,255,0.7)', fontSize: 13 },
  errorBox: {
    maxHeight: 280,
    backgroundColor: 'rgba(255,255,255,0.06)',
    borderRadius: 10,
    borderWidth: 1,
    borderColor: 'rgba(255,255,255,0.12)',
  },
  errorBoxInner: { padding: 12 },
  errorName: { color: colors.error, fontSize: 13, fontWeight: '700', marginBottom: 4 },
  errorMsg: { color: '#fff', fontSize: 12, marginBottom: 8 },
  stack: { color: 'rgba(255,255,255,0.5)', fontSize: 10, fontFamily: 'Courier' },
  cta: {
    backgroundColor: colors.primary,
    paddingVertical: 14,
    borderRadius: 12,
    alignItems: 'center',
  },
  ctaTx: { color: '#fff', fontSize: 14, fontWeight: '700' },
});
