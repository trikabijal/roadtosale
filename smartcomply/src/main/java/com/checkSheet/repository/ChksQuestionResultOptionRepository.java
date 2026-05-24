package com.checkSheet.repository;

import com.checkSheet.entity.ChksQuestionResult;
import com.checkSheet.entity.ChksQuestionResultMatrix;
import com.checkSheet.entity.ChksQuestionResultOption;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
@Repository
public interface ChksQuestionResultOptionRepository extends JpaRepository<ChksQuestionResultOption, Long> {

    @Override
    Optional<ChksQuestionResultOption> findById(Long id);

    @Modifying
    @Query("DELETE FROM ChksQuestionResultOption m WHERE m.checksheet.id = :checksheetId")
    void deleteByChecksheet_Id(@Param("checksheetId") Long checksheetId);

    @Modifying
    void deleteByChksQuestionResult_Id(@Param("chksQuestionResultId") Long chksQuestionResultId);

    List<ChksQuestionResultOption> findByChecksheet_IdOrderById(Long checksheetId);

    List<ChksQuestionResultOption> findByChksQuestionResult_Id(Long checksheetId);
    List<ChksQuestionResultOption> findByChksQuestionResult_IdIn(List<Long> chksQueResIds);
}
