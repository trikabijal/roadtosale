package com.checkSheet.repository;

import com.checkSheet.entity.Intervention;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface InterventionRepository extends JpaRepository<Intervention, Long> {
    List<Intervention> findByDeletedAtIsNullOrderByCreatedAtDesc();
    List<Intervention> findByAuditIdAndStatusAndDeletedAtIsNull(Long auditId, String status);
    Optional<Intervention> findByIdAndDeletedAtIsNull(Long id);

    /** Activation-time conflict check: any other ACTIVE intervention on the
     *  same audit cycle that already claims one of the supplied questions. */
    @Query("""
        SELECT DISTINCT i FROM Intervention i
          JOIN InterventionQuestion q ON q.intervention = i
         WHERE i.audit.id = :auditId
           AND i.status = 'ACTIVE'
           AND i.deletedAt IS NULL
           AND q.deletedAt IS NULL
           AND q.chksQuestion.id IN :questionIds
           AND (:excludeInterventionId IS NULL OR i.id <> :excludeInterventionId)
    """)
    List<Intervention> findActiveInterventionsClaimingQuestions(
        @Param("auditId") Long auditId,
        @Param("questionIds") List<Long> questionIds,
        @Param("excludeInterventionId") Long excludeInterventionId
    );
}
