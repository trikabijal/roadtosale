package com.checkSheet.DTO;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;
import java.util.List;

/** Inbound for POST /api/intervention/createDraft.
 *  Targeting is composed via one of the three shortcut modes below; the
 *  service materialises into intervention_assignment_targets at activation. */
@Data @NoArgsConstructor @AllArgsConstructor
public class InterventionCreateDTO {
    private String name;
    private String theme;
    private Long auditId;
    private String priority;          // P1 | P2 | P3
    private Date targetDate;
    private List<Long> questionIds;

    /** Targeting shortcut — resolved to assignment ids at activation:
     *  ALL: leave targetAuditAssignmentIds null
     *  BY_REGION: set targetRegionId
     *  MANUAL: set targetAuditAssignmentIds explicitly */
    private String targetingMode;     // ALL | BY_REGION | MANUAL  (UI hint, not stored)
    private Long targetRegionId;      // when BY_REGION
    private List<Long> targetAuditAssignmentIds;  // when MANUAL
}
