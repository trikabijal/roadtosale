package com.checkSheet.DAO;

import com.checkSheet.DTO.ChksHeaderDataFileDTO;
import com.checkSheet.constant.ChksQuestionResultObjectiveType;
import com.checkSheet.constant.ChksQuestionResultType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;

import jakarta.persistence.Query;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.checkSheet.DTO.ChksQuestionResultDTO;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Repository
public class ChksQuestionResultDAO {

    @Autowired
    private EntityManager entityManager;

    
    public ChksQuestionResultDTO getResultData(Long checksheetId, Long chksHeaderId, Long chksQuestionId, Long id) {
        try {
            String nativeQuery = "SELECT cqr.id, cqr.checksheet_id, cqr.chks_header_id, cqr.chks_question_id,\n" +
                    " cqr.answer_type, cqr.objective_type, cqr.upper_limit, cqr.lower_limit,\n" +
                    " cqr.unit, cqr.matrix_name, " +
                    " cqr.matrix_row_header_names, cqr.matrix_column_header_names,\n" +
                    " cqr.no_of_results, cqr.no_of_rows, cqr.no_of_columns," +
                    " cqr.chks_matrix_row_name, cqr.chks_matrix_col_name, cqr.is_optional \n" +
                    " FROM chks_question_results cqr \n" +
                    " WHERE 1=1 ";
            if(!Objects.isNull(checksheetId)) {
                nativeQuery += " and cqr.checksheet_id = '" + checksheetId+ "' ";
            }
            if(!Objects.isNull(chksHeaderId)) {
                nativeQuery += " and cqr.chks_header_id = '" + chksHeaderId+ "' ";
            }
            if(!Objects.isNull(chksQuestionId)) {
                nativeQuery += " and cqr.chks_question_id = '" + chksQuestionId+ "' ";
            }
            if(!Objects.isNull(id)) {
                nativeQuery += " and cqr.id = '" + id+ "' ";
            }

            ChksQuestionResultDTO getResultData = (ChksQuestionResultDTO) entityManager.createNativeQuery(nativeQuery, "getResultData")
                    .getSingleResult();
            return getResultData;
        } catch (NoResultException e) {
            e.printStackTrace();
            return null;
        }
    }


    public List<ChksQuestionResultDTO> getChksQuestionResultByChecksheetId(Long checksheetId) {
        try {
            String query = "SELECT cqr.id, cqr.checksheet_id, cqr.chks_header_id, cqr.chks_question_id,\n" +
                    " cqr.answer_type, cqr.objective_type, cqr.upper_limit, cqr.lower_limit,\n" +
                    " cqr.unit, cqr.matrix_name, " +
                    " cqr.matrix_row_header_names, cqr.matrix_column_header_names,\n" +
                    " cqr.no_of_results, cqr.no_of_rows, cqr.no_of_columns," +
                    " cqr.chks_matrix_row_name, cqr.chks_matrix_col_name, cqr.is_optional \n" +
                    " from chks_question_results cqr where 1=1 ";
            if(!Objects.isNull(checksheetId)){
                query += " and cqr.checksheet_id = :checksheetId ";
            }

            Query nativeQuery = entityManager.createNativeQuery(query, "getResultData")
                    .setParameter("checksheetId", checksheetId);

            return nativeQuery.getResultList();
        } catch (Exception e) {
            e.printStackTrace();
            return new ArrayList<>();
        }
    }

    public List<ChksQuestionResultDTO> getQuestionResults(List<Long> questionIds) {
        try {
            String nativeQuery = 
                "SELECT cqr.id, cqr.chks_header_id, cqr.chks_question_id, " +
                "cqr.answer_type, cqr.objective_type, cqr.upper_limit, cqr.lower_limit, " +
                "cqr.unit, cqr.matrix_name, cqr.matrix_file_location, " +
                "cqr.no_of_results, cqr.no_of_rows, cqr.no_of_columns, " +
                "cqr.chks_matrix_row_name, cqr.chks_matrix_col_name " +
                "FROM chks_question_results cqr " +
                "WHERE cqr.chks_question_id IN (:questionIds)";

            Query query = entityManager.createNativeQuery(nativeQuery)
                .setParameter("questionIds", questionIds);

            List<Object[]> results = query.getResultList();
            List<ChksQuestionResultDTO> resultDTOs = new ArrayList<>();

            for (Object[] row : results) {
                ChksQuestionResultDTO dto = new ChksQuestionResultDTO();
                int i = 0;
                dto.setId(((Number) row[i++]).longValue());
                dto.setChksHeaderId(((Number) row[i++]).longValue());
                dto.setChksQuestionId(((Number) row[i++]).longValue());
                dto.setAnswerType(row[i++] != null ? ChksQuestionResultType.valueOf((String) row[i-1]) : null);
                dto.setChksQuestionResultObjectiveType(row[i++] != null ? ChksQuestionResultObjectiveType.valueOf((String) row[i-1]) : null);
                dto.setUpperLimit(row[i++] != null ? ((Number) row[i-1]).doubleValue() : null);
                dto.setLowerLimit(row[i++] != null ? ((Number) row[i-1]).doubleValue() : null);
                dto.setUnit((String) row[i++]);
                dto.setMatrixName((String) row[i++]);
                dto.setMatrixFileLocation((String) row[i++]);
                dto.setNoOfResults(row[i++] != null ? ((Number) row[i-1]).longValue() : null);
                dto.setNoOfRows(row[i++] != null ? ((Number) row[i-1]).longValue() : null);
                dto.setNoOfColumns(row[i++] != null ? ((Number) row[i-1]).longValue() : null);
                dto.setChksMatrixRowName((String) row[i++]);
                dto.setChksMatrixColName((String) row[i]);

                resultDTOs.add(dto);
            }

            return resultDTOs;
        } catch (Exception e) {
            e.printStackTrace();
            return new ArrayList<>();
        }
    }

}
