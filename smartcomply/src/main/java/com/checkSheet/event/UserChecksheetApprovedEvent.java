package com.checkSheet.event;

import org.springframework.context.ApplicationEvent;

/**
 * Published when a {@code Inspection} transitions to APPROVED via the
 * existing approval flow ({@code UserChecksheetApprovalServiceImpl}).
 *
 * Listeners use this to react to a confirmed audit instance:
 * <ul>
 *   <li>Audit-side UCs (auditAssignmentId != null): the InterventionInstantiationListener
 *       creates Improvement Plans for any ACTIVE intervention whose target
 *       set includes this audit_assignment AND whose question scope
 *       intersects the failing answers.</li>
 *   <li>Intervention-side UCs (interventionAssignmentId != null, the
 *       re-inspection waves): the same listener evaluates plan completion
 *       against the tracked-question subset.</li>
 * </ul>
 *
 * Carries the minimum context needed by listeners; if more is needed they
 * look it up from repositories.
 */
public class UserChecksheetApprovedEvent extends ApplicationEvent {

    private final Long userChecksheetId;
    private final Long auditId;                  // null for re-inspection-wave UCs
    private final Long auditeeLocationId;        // null for re-inspection-wave UCs
    private final Long auditAssignmentId;        // null for re-inspection-wave UCs
    private final Long interventionAssignmentId; // null for original-audit UCs

    public UserChecksheetApprovedEvent(Object source,
                                       Long userChecksheetId,
                                       Long auditId,
                                       Long auditeeLocationId,
                                       Long auditAssignmentId,
                                       Long interventionAssignmentId) {
        super(source);
        this.userChecksheetId = userChecksheetId;
        this.auditId = auditId;
        this.auditeeLocationId = auditeeLocationId;
        this.auditAssignmentId = auditAssignmentId;
        this.interventionAssignmentId = interventionAssignmentId;
    }

    public Long getInspectionId()         { return userChecksheetId; }
    public Long getAuditId()                  { return auditId; }
    public Long getAuditeeLocationId()        { return auditeeLocationId; }
    public Long getAuditAssignmentId()        { return auditAssignmentId; }
    public Long getInterventionAssignmentId() { return interventionAssignmentId; }
}
