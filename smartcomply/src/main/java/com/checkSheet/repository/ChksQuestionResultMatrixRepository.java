package com.checkSheet.repository;

import com.checkSheet.entity.ChksQuestionFile;
import com.checkSheet.entity.ChksQuestionResult;
import com.checkSheet.entity.ChksQuestionResultMatrix;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
@Repository
public interface ChksQuestionResultMatrixRepository extends JpaRepository<ChksQuestionResultMatrix, Long> {

    @Override
    Optional<ChksQuestionResultMatrix> findById(Long id);

    @Modifying
    @Query("DELETE FROM ChksQuestionResultMatrix m WHERE m.checksheet.id = :checksheetId")
    void deleteByChecksheet_Id(@Param("checksheetId") Long checksheetId);

    @Modifying
    void deleteByChksQuestionResult_Id(@Param("chksQuestionResultId") Long chksQuestionResultId);

    List<ChksQuestionResultMatrix> findByChecksheet_IdOrderById(Long checksheetId);

    List<ChksQuestionResultMatrix> findByChksQuestionResult_Id(Long checksheetId);

    List<ChksQuestionResultMatrix> findByChksQuestionResult_IdIn(List<Long> matrixChksQueResIds);
}
