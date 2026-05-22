import React from 'react';
import { View, ScrollView, StyleSheet, KeyboardAvoidingView, Platform } from 'react-native';
import { TopHeader } from './TopHeader';
import { BottomNav } from './BottomNav';
import { colors } from '../theme';
import { useDealStore } from '../store/deal';
import { ScreenName, STEP_BY_ROUTE } from '../data/steps';

type Props = {
  routeName: ScreenName;
  children: React.ReactNode;
  scroll?: boolean;
  onBack?: () => void;
  onNext?: () => void;
  hideNav?: boolean;
  backLabel?: string;
  nextLabel?: string;
  hideBack?: boolean;
  hideNext?: boolean;
};

export const ScreenChrome: React.FC<Props> = ({
  routeName,
  children,
  scroll,
  onBack,
  onNext,
  hideNav,
  backLabel,
  nextLabel,
  hideBack,
  hideNext,
}) => {
  const deal = useDealStore((s) => s.deal);
  const step = STEP_BY_ROUTE[routeName];
  const dealership = deal?.setup.dealership ?? '—';

  const Body = scroll ? (
    <ScrollView
      style={styles.body}
      contentContainerStyle={styles.bodyContent}
      keyboardShouldPersistTaps="handled"
      showsVerticalScrollIndicator={false}
    >
      {children}
    </ScrollView>
  ) : (
    <View style={[styles.body, styles.bodyContent]}>{children}</View>
  );

  return (
    <KeyboardAvoidingView
      style={styles.root}
      behavior={Platform.OS === 'ios' ? 'padding' : undefined}
    >
      <TopHeader
        dealership={dealership}
        stepLabel={step?.label ?? routeName}
        percent={step?.percent ?? 0}
      />
      <View style={styles.fillContent}>{Body}</View>
      {!hideNav ? (
        <BottomNav
          onBack={onBack}
          onNext={onNext}
          backLabel={backLabel}
          nextLabel={nextLabel}
          hideBack={hideBack}
          hideNext={hideNext}
        />
      ) : null}
    </KeyboardAvoidingView>
  );
};

const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: colors.screenBg },
  fillContent: { flex: 1 },
  body: { flex: 1 },
  bodyContent: {
    paddingHorizontal: 16,
    paddingTop: 13,
    paddingBottom: 13,
    gap: 8,
  },
});
