package com.checkSheet.repository;

import com.checkSheet.entity.InterventionAssignmentTarget;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface InterventionAssignmentTargetRepository extends JpaRepository<InterventionAssignmentTarget, Long> {
    List<InterventionAssignmentTarget> findByInterventionIdAndDeletedAtIsNull(Long interventionId);

    /** Used by the instantiation listener: which interventions claim this
     *  audit_assignment? Each match becomes a candidate for plan creation. */
    List<InterventionAssignmentTarget> findByInspectionIdAndDeletedAtIsNull(Long auditAssignmentId);

    Optional<InterventionAssignmentTarget> findByInterventionIdAndInspectionIdAndDeletedAtIsNull(Long interventionId, Long auditAssignmentId);
}
