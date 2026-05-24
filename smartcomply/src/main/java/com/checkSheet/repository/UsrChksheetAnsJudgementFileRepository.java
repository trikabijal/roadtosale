package com.checkSheet.repository;

import com.checkSheet.entity.UsrChksheetAnsJudgementFile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UsrChksheetAnsJudgementFileRepository extends JpaRepository<UsrChksheetAnsJudgementFile, Long> {

    Optional<UsrChksheetAnsJudgementFile> findById(Long id);

    List<UsrChksheetAnsJudgementFile> findByIdIn(List<Long> usrChksheetAnsJudgementFileIds);

    void deleteByIdIn(List<Long> usrChksheetAnsJudgementFileIds);


    List<UsrChksheetAnsJudgementFile> findByInspection_IdAndUsrChksheetAnsJudgement_Id(Long userChecksheetId, Long usrChksheetAnsJudgementId);
}
