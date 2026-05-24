// Normative CRM contract — PRD §7.2.
// v1 ships AuditProCrmProvider (wraps SmartComply myAssignments).
// Future adapters (CDK, VinSolutions) implement this interface.

export interface Appointment {
  id: string;                  // SmartComply assignmentId (as string for interface generality)
  scheduledAt: Date | null;    // null for walk-ins
  customer: {
    firstName: string;
    lastName?: string;
    phone?: string;
    email?: string;
  };
  vehicleShortlist?: Array<{
    make: string;
    model: string;
    year: number;
    trim?: string;
  }>;
  repId: string;
  storeId: string;
  source: string;              // 'auditpro' | 'cdk' | 'vinsolutions'
  // SmartComply-specific — present when source === 'auditpro'
  smartComply?: {
    assignmentId: number;
    auditId: number;
    checksheetId: number;
    userChecksheetId: number | null;
    status: string;
  };
}

export interface CrmAppointmentProvider {
  getTodayAppointments(storeId: string): Promise<Appointment[]>;
  getAppointment(id: string): Promise<Appointment>;
  createWalkIn(repId: string, storeId: string): Promise<Appointment>;
}
