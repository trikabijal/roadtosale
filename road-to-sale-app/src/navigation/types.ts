import type { NativeStackScreenProps } from '@react-navigation/native-stack';

// Appointment coming from SmartComply myAssignments endpoint
export interface AppointmentBrief {
  assignmentId: number;
  auditId: number;
  auditName: string;
  checksheetId: number;
  checksheetName: string;
  locationLabel: string;
  userChecksheetId: number | null;
  status: 'ASSIGNED' | 'IN_PROGRESS' | 'SUBMITTED' | 'VALIDATED' | 'APPROVED';
  answeredQuestions: number;
  totalQuestions: number;
}

export type RootStackParamList = {
  Auth: undefined;
  Home: undefined;
  SessionSetup: { appointment?: AppointmentBrief };
  ActiveSession: { sessionId: string };
  TradeIn: { sessionId: string };
  SessionSummary: { sessionId: string; readOnly?: boolean };
  History: undefined;
};

export type AuthScreenProps = NativeStackScreenProps<RootStackParamList, 'Auth'>;
export type HomeScreenProps = NativeStackScreenProps<RootStackParamList, 'Home'>;
export type SessionSetupScreenProps = NativeStackScreenProps<RootStackParamList, 'SessionSetup'>;
export type ActiveSessionScreenProps = NativeStackScreenProps<RootStackParamList, 'ActiveSession'>;
export type TradeInScreenProps = NativeStackScreenProps<RootStackParamList, 'TradeIn'>;
export type SessionSummaryScreenProps = NativeStackScreenProps<RootStackParamList, 'SessionSummary'>;
export type HistoryScreenProps = NativeStackScreenProps<RootStackParamList, 'History'>;
