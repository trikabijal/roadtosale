package com.checkSheet.repository;

import com.checkSheet.entity.ChecksheetApprovalHistory;
import com.checkSheet.entity.Inspection;
import com.checkSheet.entity.UserChecksheetAnswer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
@Repository
public interface UserChecksheetAnswerRepository extends JpaRepository<UserChecksheetAnswer, Long> {

    @Override
    Optional<UserChecksheetAnswer> findById(Long id);

    Optional<UserChecksheetAnswer> findByInspectionIdAndChksQuestionResultId(Long userChecksheetId, Long chksQuestionResultId);

    Optional<UserChecksheetAnswer> findByInspectionIdAndChksQuestion_IdAndChksQuestionResult_Id(
        Long userChecksheetId, Long chksQuestionId, Long chksQuestionResultId);

    List<UserChecksheetAnswer> findByChksQuestion_IdAndInspection_IdOrderByChksQuestionResult_Id(Long chksQuestionId, Long userChecksheetId);

    /** All answers belonging to one user_checksheet — used by improvement-plan
     *  instantiation to find the failing questions. Soft-delete agnostic;
     *  caller filters as needed. */
    List<UserChecksheetAnswer> findByInspectionId(Long userChecksheetId);

}
