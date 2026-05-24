package com.checkSheet.repository;

import com.checkSheet.entity.Inspection;
import com.checkSheet.entity.UsrChksheetAnsJudgement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
@Repository
public interface UsrChksheetAnsJudgementRepository extends JpaRepository<UsrChksheetAnsJudgement, Long> {

    @Override
    Optional<UsrChksheetAnsJudgement> findById(Long id);

    Optional<UsrChksheetAnsJudgement> findByInspectionIdAndChksQuestionId(Long userChecksheetId, Long chksQuestionId);

    Optional<UsrChksheetAnsJudgement> findByChksQuestionIdAndInspection_Id(Long chksQuestionId, Long userChecksheetId);

    List<UsrChksheetAnsJudgement> findByInspection_IdAndJudgement(Long userChecksheetId, String judgement);

    List<UsrChksheetAnsJudgement> findByInspection_IdInAndJudgementAndChksQuestionId(List<Long> userChecksheetIds, String judgement, Long chksQuestionId);
}

