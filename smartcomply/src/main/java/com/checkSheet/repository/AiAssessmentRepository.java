package com.checkSheet.repository;

import com.checkSheet.entity.AiAssessment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AiAssessmentRepository extends JpaRepository<AiAssessment, Long> {

    Optional<AiAssessment> findFirstByInspectionIdAndChksQuestionResultIdAndDeletedAtIsNullOrderByAssessedAtDesc(
            Long userChecksheetId, Long chksQuestionResultId);

    List<AiAssessment> findByInspectionIdAndDeletedAtIsNullOrderByAssessedAtDesc(Long userChecksheetId);
}
