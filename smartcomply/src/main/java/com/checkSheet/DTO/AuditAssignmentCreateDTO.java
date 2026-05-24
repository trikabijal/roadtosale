package com.checkSheet.DTO;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Single entry in the addAuditAssignments request body — typed instead of a
 * raw Map so payload parsing fails cleanly on bad input rather than via
 * ClassCast deep in the service.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AuditAssignmentCreateDTO {
    private Long auditeeLocationId;
    private Long operatorUserId;
    // V1.30 follow-up — dealer-principal acknowledge flow 403s when this is
    // null on the resulting Inspection, which then cascades to interventions
    // instantiated from that Inspection. Optional on input: clients that
    // don't yet know the DP can omit this and patch it later via a future
    // edit endpoint.
    private Long dealerPrincipalUserId;
}
