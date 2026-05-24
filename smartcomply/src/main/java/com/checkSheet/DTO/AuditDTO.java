package com.checkSheet.DTO;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditDTO {
    private Long id;
    private String name;
    private Long checksheetId;
    private String checksheetName;
    private String checksheetCode;   // modelNo of the referenced checksheet template
    private String status;          // DRAFT | ACTIVE | CLOSED
    private Date startDate;
    private Date endDate;
    private Date createdAt;
    private Date updatedAt;

    // Stats (computed in service when listing). Field name is "totalLocations"
    // (not "totalAssignments") to match what the smartcomply-angular audit-list
    // table reads — post-V1.28 the assignment IS the location-bound inspection,
    // and "locations" is the user-facing count anyway.
    private Long totalLocations;
    private Long done;
    private Long inProgress;
    private Long notStarted;

    // Detail-only (audit/{id})
    private List<AuditAssignmentDTO> assignments;
}
