package com.checkSheet.DTO;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Inbound for POST /api/intervention-assignment/{id}/closeNonCompliant.
 *  Reason is required to keep an audit trail of why the plan was closed
 *  without a clean re-inspection (PRD §3.3.2). */
@Data @NoArgsConstructor @AllArgsConstructor
public class NonCompliantClosureDTO {
    private String reason;
}
