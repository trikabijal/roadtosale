package com.checkSheet.repository;

import com.checkSheet.entity.Inspection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Post-V1.28: an "audit assignment" is just an Inspection with kind='AUDIT'.
 * All access goes through the inspections table. Methods filter by kind so
 * INTERVENTION-kind rows aren't accidentally returned.
 */
@Repository
public interface AuditAssignmentRepository extends JpaRepository<Inspection, Long> {

    @Query("""
        SELECT i FROM Inspection i
         WHERE i.kind = 'AUDIT' AND i.audit.id = :auditId AND i.deletedAt IS NULL
    """)
    List<Inspection> findByAuditIdAndDeletedAtIsNull(@Param("auditId") Long auditId);

    @Query("""
        SELECT i FROM Inspection i
         WHERE i.kind = 'AUDIT'
           AND i.audit.id = :auditId
           AND i.auditeeLocation.id = :auditeeLocationId
           AND i.deletedAt IS NULL
    """)
    Optional<Inspection> findByAuditIdAndAuditeeLocationIdAndDeletedAtIsNull(
            @Param("auditId") Long auditId, @Param("auditeeLocationId") Long auditeeLocationId);

    @Query("""
        SELECT COUNT(i) FROM Inspection i
         WHERE i.kind = 'AUDIT' AND i.audit.id = :auditId AND i.deletedAt IS NULL
    """)
    long countByAuditIdAndDeletedAtIsNull(@Param("auditId") Long auditId);
}
