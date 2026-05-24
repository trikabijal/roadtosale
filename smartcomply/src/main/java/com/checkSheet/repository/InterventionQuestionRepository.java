package com.checkSheet.repository;

import com.checkSheet.entity.InterventionQuestion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface InterventionQuestionRepository extends JpaRepository<InterventionQuestion, Long> {
    List<InterventionQuestion> findByInterventionIdAndDeletedAtIsNull(Long interventionId);
    boolean existsByInterventionIdAndChksQuestionIdAndDeletedAtIsNull(Long interventionId, Long chksQuestionId);
    void deleteByInterventionIdAndChksQuestionIdIn(Long interventionId, List<Long> chksQuestionIds);

    /** Batch fetch for the instantiation listener — pull every
     *  InterventionQuestion across many interventions in one round-trip. */
    List<InterventionQuestion> findByInterventionIdInAndDeletedAtIsNull(java.util.Collection<Long> interventionIds);
}
