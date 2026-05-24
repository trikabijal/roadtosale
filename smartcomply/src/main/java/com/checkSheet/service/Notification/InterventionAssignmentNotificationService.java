package com.checkSheet.service.Notification;

import com.checkSheet.entity.Inspection;

/** Stub contract for plan-related push/email notifications.
 *  Implementations are wired tenant-side; the in-repo default is no-op
 *  with structured logging so demos run without external integrations. */
public interface InterventionAssignmentNotificationService {

    /** Notify the dealer principal that a new plan was created for them. */
    void notifyPlanCreated(Inspection ia);

    /** Notify the regional head that a plan was just acknowledged. */
    void notifyPlanAcknowledged(Inspection ia);

    /** Notify owners that a plan auto-flipped to NON_COMPLIANT (overdue). */
    void notifyPlanOverdue(Inspection ia);

    /** Notify owners that the plan was completed (clean re-inspection). */
    void notifyPlanCompleted(Inspection ia);
}
