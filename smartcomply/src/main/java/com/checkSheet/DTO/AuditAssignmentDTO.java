package com.checkSheet.DTO;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditAssignmentDTO {
    private Long id;
    private Long auditId;
    private Long auditeeLocationId;
    private String auditeeName;       // resolved from auditees.name
    private String locationAddress;   // auditee_locations.address
    private String city;              // resolved chain: location → city → state → region
    private String state;
    private String region;
    private Long operatorUserId;
    private String operatorUsername;
    private String userChecksheetStatus; // NOT_STARTED | IN_PROGRESS | SUBMITTED | VALIDATED | APPROVED
    @com.fasterxml.jackson.annotation.JsonAlias({"userChecksheetId"})
    private Long inspectionId;       // null if not started
}
