package com.checkSheet.DTO;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;
import java.util.List;

/** Intervention payload (PRD "Improvement Campaign"). */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class InterventionDTO {
    private Long id;
    private Long auditId;
    private String auditName;
    private String name;
    private String theme;
    private String priority;
    private Date targetDate;
    private String status;          // DRAFT | ACTIVE | CLOSED
    private Date activatedAt;
    private Date closedAt;
    private Date createdAt;
    private Date updatedAt;

    // Stats (populated when listing)
    private long totalAssignmentTargets;
    private long assignmentsInstantiated;
    private long assignmentsCompleted;
    private long assignmentsNonCompliant;

    // Detail-only
    private List<Long> questionIds;
    /** audit_assignment ids in scope (the materialised targets). */
    private List<Long> targetAuditAssignmentIds;
}
