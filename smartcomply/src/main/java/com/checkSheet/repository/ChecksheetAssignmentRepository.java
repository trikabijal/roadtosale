package com.checkSheet.repository;

import com.checkSheet.entity.ChecksheetAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ChecksheetAssignmentRepository extends JpaRepository<ChecksheetAssignment, Long> {
    Optional<ChecksheetAssignment> findByChecksheet_IdAndAuditor_IdAndAuditeeLocation_Id(
            Long checksheetId,
            Long auditorId,
            Long auditeeLocationId
    );

    void deleteAllByChecksheet_IdAndAuditor_Id(Long checksheetId, Long auditorId);
}
