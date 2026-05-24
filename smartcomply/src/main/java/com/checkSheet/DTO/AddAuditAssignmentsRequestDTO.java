package com.checkSheet.DTO;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
public class AddAuditAssignmentsRequestDTO {
    private Long auditId;
    private List<AuditAssignmentCreateDTO> assignments;
}
