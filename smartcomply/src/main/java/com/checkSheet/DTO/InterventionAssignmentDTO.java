package com.checkSheet.DTO;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;
import java.util.List;

/** Outbound DTO for an Inspection (PRD "Improvement Plan").
 *  Per-dealership instance of a campaign. All site / dealer / region info
 *  is denormalised for the frontend so it doesn't have to chase joins. */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class InterventionAssignmentDTO {
    private Long id;
    private Long interventionId;
    private String interventionName;
    private String theme;
    private String priority;          // P1 | P2 | P3 (snapshotted)
    private Date targetDate;          // snapshotted
    private String status;            // V1.28: ASSIGNED | IN_PROGRESS | SUBMITTED | VALIDATED | APPROVED | DECLINED
    private Date acknowledgedAt;
    private Long acknowledgedByUserId;
    private Date completedAt;
    private Date closedAsNonCompliantAt;
    private String closureReason;

    // Anchor — the audit_assignment this plan derives from.
    private Long auditAssignmentId;
    private Long auditId;
    private String auditName;

    // Denormalised location / dealer / region for table rendering
    private Long auditeeLocationId;
    private String locationAddress;
    private Long auditeeId;
    private String auditeeName;
    private String auditeeCode;
    private Long regionId;
    private String regionName;

    // Owners
    private Long dealerPrincipalUserId;
    private String dealerPrincipalName;
    private Long regionOwnerUserId;
    private String regionOwnerName;
    private Long operatorUserId;

    // Failing question subset (intersection of campaign questions × this dealer's NOT-OK answers)
    private List<Long> questionIds;

    // Re-inspection waves: every UC pointing at this assignment (latest first).
    private List<Long> reinspectionInspectionIds;
    private Long latestReinspectionInspectionId;
    private String latestReinspectionStatus;
    private Date latestReinspectionApprovedAt;

    private Date createdAt;
    private Date updatedAt;
}
