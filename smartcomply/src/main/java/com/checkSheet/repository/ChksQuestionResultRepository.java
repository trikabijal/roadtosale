package com.checkSheet.repository;

import com.checkSheet.DTO.ChksQuestionResultDTO;
import com.checkSheet.entity.ChksQuestionResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
@Repository
public interface ChksQuestionResultRepository extends JpaRepository<ChksQuestionResult, Long> {

    @Override
    Optional<ChksQuestionResult> findById(Long id);

    @Modifying
    @Query("DELETE FROM ChksQuestionResult r WHERE r.checksheet.id = :checksheetId")
    void deleteByChecksheet_Id(@Param("checksheetId") Long checksheetId);

    Optional<ChksQuestionResult> findByChksHeader_IdAndChksQuestion_IdAndChecksheet_Id(Long chksHeaderId, Long chksQuestionId, Long checksheetId);

    List<ChksQuestionResult> findByChecksheet_IdOrderById(Long checksheetId);

    List<ChksQuestionResult> findByChksQuestion_IdOrderById(Long questionId);


    Optional<ChksQuestionResult> findByChksQuestion_IdAndChecksheet_IdAndChksHeader_Id(Long questionId, Long checksheetId, Long checksheetHeaderId);

    @Query(nativeQuery = true, value = """
        SELECT 
            cqr.id,
            cqr.checksheet_id as checksheetId,
            cqr.chks_header_id as chksHeaderId,
            cqr.chks_question_id as chksQuestionId,
            cqr.answer_type as answerType,
            cqr.objective_type as chksQuestionResultObjectiveType,
            cqr.upper_limit as upperLimit,
            cqr.lower_limit as lowerLimit,
            cqr.unit,
            cqr.matrix_name as matrixName,
            cqr.matrix_row_header_names as matrixRowHeaderNames,
            cqr.matrix_column_header_names as matrixColumnHeaderNames,
            cqr.no_of_results as noOfResults,
            cqr.no_of_rows as noOfRows,
            cqr.no_of_columns as noOfColumns,
            cqr.chks_matrix_row_name as chksMatrixRowName,
            cqr.chks_matrix_col_name as chksMatrixColName
        FROM chks_question_results cqr
        WHERE cqr.chks_question_id IN (:questionIds)
    """)
    List<ChksQuestionResultDTO> getQuestionResultsByQuestionIds(@Param("questionIds") List<Long> questionIds);

    List<ChksQuestionResultDTO> findByChksQuestionIdIn(List<Long> questionIds);

    @Query("SELECT COUNT(r) FROM ChksQuestionResult r WHERE r.checksheet.id = :checksheetId")
    long countByChecksheetId(@Param("checksheetId") Long checksheetId);
}
