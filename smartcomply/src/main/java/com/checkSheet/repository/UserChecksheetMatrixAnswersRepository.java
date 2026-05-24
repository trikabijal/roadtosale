package com.checkSheet.repository;

import com.checkSheet.entity.UserChecksheetMatrixAnswers;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
@Repository
public interface UserChecksheetMatrixAnswersRepository extends JpaRepository<UserChecksheetMatrixAnswers, Long> {

    @Override
    Optional<UserChecksheetMatrixAnswers> findById(Long id);
    Optional<UserChecksheetMatrixAnswers> findByInspectionIdAndChksQuestionResultIdAndChksQuestionResultMatrixIdAndOrderNo
            (Long userChecksheetId, Long chksQuestionResultId, Long chksQuestionResultMatrixId, Integer orderNo);

    List<UserChecksheetMatrixAnswers> findByInspection_IdInOrderByOrderNo(List<Long> userChksIds);

    @Modifying
    long deleteByInspection_IdAndChksQuestionResult_Id(Long userChecksheetId, Long chksQuestionResultId);
}
