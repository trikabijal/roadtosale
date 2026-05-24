package com.checkSheet.repository;

import com.checkSheet.entity.InterventionAssignmentQuestion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface InterventionAssignmentQuestionRepository extends JpaRepository<InterventionAssignmentQuestion, Long> {
    List<InterventionAssignmentQuestion> findByInspectionIdAndDeletedAtIsNull(Long interventionAssignmentId);
}
