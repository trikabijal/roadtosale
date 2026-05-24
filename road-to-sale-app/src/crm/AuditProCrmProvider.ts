import type { CrmAppointmentProvider, Appointment } from './types';
import type { ISmartComplyClient } from '../api/ISmartComplyClient';
import type { AssignmentDTO } from '../api/types';

export class AuditProCrmProvider implements CrmAppointmentProvider {
  constructor(private readonly client: ISmartComplyClient) {}

  async getTodayAppointments(_storeId: string): Promise<Appointment[]> {
    const assignments = await this.client.getMyAssignments();
    return assignments
      .filter((a) => a.status === 'ASSIGNED' || a.status === 'IN_PROGRESS')
      .map(assignmentToAppointment);
  }

  async getAppointment(id: string): Promise<Appointment> {
    const assignments = await this.client.getMyAssignments();
    const found = assignments.find((a) => String(a.assignmentId) === id);
    if (!found) throw new Error(`Assignment not found: ${id}`);
    return assignmentToAppointment(found);
  }

  async createWalkIn(repId: string, _storeId: string): Promise<Appointment> {
    // Walk-in: create ad-hoc inspection against the standing walk-in audit campaign.
    // auditId and auditeeLocationId are resolved from env config.
    const walkInAuditId = parseInt(process.env.SMARTCOMPLY_WALKIN_AUDIT_ID ?? '1', 10);
    const locationId = parseInt(process.env.SMARTCOMPLY_LOCATION_ID ?? '1', 10);
    const userId = parseInt(repId, 10);

    await this.client.createWalkInAssignment({
      auditId: walkInAuditId,
      assignments: [{ auditeeLocationId: locationId, operatorUserId: userId }],
    });

    // Fetch updated assignments and return the newest one
    const assignments = await this.client.getMyAssignments();
    const newest = assignments
      .filter((a) => a.status === 'ASSIGNED')
      .sort((a, b) => b.assignmentId - a.assignmentId)[0];
    if (!newest) throw new Error('Walk-in assignment creation failed.');
    return assignmentToAppointment(newest);
  }
}

function assignmentToAppointment(a: AssignmentDTO): Appointment {
  return {
    id: String(a.assignmentId),
    scheduledAt: null,            // SmartComply assignments don't carry a scheduled time
    customer: { firstName: 'Walk-In' },  // filled by rep in SessionSetup
    repId: '',                    // resolved from auth context after login
    storeId: '',                  // resolved from location after login
    source: 'auditpro',
    smartComply: {
      assignmentId: a.assignmentId,
      auditId: a.auditId,
      checksheetId: a.checksheetId,
      userChecksheetId: a.userChecksheetId,
      status: a.status,
    },
  };
}
