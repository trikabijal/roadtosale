package com.checkSheet.DAO;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import com.checkSheet.DTO.UserChecksheetAnswerDTO;
import com.checkSheet.DTO.UserChecksheetMatrixAnswerDTO;

import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.Query;

@Repository
public class UserChecksheetAnswerDAO {
    @Autowired
    private EntityManager entityManager;

    public List<UserChecksheetAnswerDTO> getUserChksAnswers(Long userChecksheetId) {
        try {
            String nativeQuery = """
                SELECT 
                    id,
                    inspection_id,
                    chks_question_result_id,
                    answer,
                    chks_question_rslt_option_id,
                    judgement,
                    answered_at,
                    is_not_applicable
                FROM user_checksheet_answers
                WHERE inspection_id = :userChecksheetId
                AND deleted_at IS NULL
                ORDER BY id ASC
            """;

            return entityManager.createNativeQuery(nativeQuery, "getUserChksAnswers")
                    .setParameter("userChecksheetId",userChecksheetId)
                    .getResultList();
        } catch (NoResultException e) {
            e.printStackTrace();
            return List.of();
        }
    }

    public List<UserChecksheetAnswerDTO> getUserChksMtrxAnswers(Long userChecksheetId) {
        try {
            String nativeQuery = """
                SELECT 
                    id,
                    inspection_id,
                    chks_question_result_id,
                    chks_question_result_matrix_id,
                    result,
                    order_no,
                    judgement,
                    mc_result,
                    answered_at
                FROM user_checksheet_matrix_answers
                WHERE inspection_id = :userChecksheetId
                AND deleted_at IS NULL
                ORDER BY id ASC
            """;

            return entityManager.createNativeQuery(nativeQuery, "getUserChksMtrxAnswers")
                    .setParameter("userChecksheetId",userChecksheetId)
                    .getResultList();
        } catch (NoResultException e) {
            e.printStackTrace();
            return List.of();
        }
    }

    public List<UserChecksheetAnswerDTO> getUserChecksheetAnswers(Long userChecksheetId) {
        String sql = """
            SELECT
                uca.id,
                uca.answer,
                uca.inspection_id,
                uca.chks_question_id,
                uca.chks_question_result_id,
                uca.chks_question_rslt_option_id,
                ucaj.judgement,
                ucaj.remarks,
                uca.is_not_applicable,
                uca.judgement AS raw_judgement
            FROM (SELECT * FROM user_checksheet_answers uca WHERE uca.deleted_by IS NULL
                AND uca.inspection_id = :userChecksheetId) uca
            LEFT JOIN (SELECT * FROM usr_chksheet_ans_judgements ucaj WHERE ucaj.deleted_by IS NULL and
                ucaj.inspection_id = :userChecksheetId) ucaj ON
                ucaj.chks_question_id = uca.chks_question_id
                AND ucaj.inspection_id = uca.inspection_id
                AND ucaj.deleted_by IS NULL
            WHERE uca.inspection_id = :userChecksheetId
            AND uca.deleted_by IS NULL
            ORDER BY uca.id
        """;

        Query query = entityManager.createNativeQuery(sql);
        query.setParameter("userChecksheetId", userChecksheetId);

        List<Object[]> results = query.getResultList();
        return results.stream()
            .map(row -> {
                UserChecksheetAnswerDTO dto = new UserChecksheetAnswerDTO();
                int index = 0;
                dto.setId(((Number) row[index++]).longValue());
                dto.setAnswer((String) row[index++]);
                dto.setInspectionId(((Number) row[index++]).longValue());
                dto.setChksQuestionId(((Number) row[index++]).longValue());
                dto.setChksQuestionResultId(((Number) row[index++]).longValue());
                dto.setChksQuestionRsltOptionId(row[index] != null ? ((Number) row[index]).longValue() : null);
                index++;
                dto.setChecksheetQuestionJudgement(row[index] != null ? ((String) row[index]) : null);
                index++;
                dto.setRemarks((String) row[index++]);
                dto.setIsNotApplicable((Boolean) row[index++]);
                // Raw integer judgement on user_checksheet_answers row itself —
                // 1 = OK, 2 = NOT OK. Used as a fallback when the judgement
                // table has no row (e.g. mobile-app and seed-written audits).
                dto.setJudgement(row[index] != null ? ((Number) row[index]).shortValue() : null);
                return dto;
            })
            .collect(Collectors.toList());
    }

    public List<UserChecksheetMatrixAnswerDTO> getUserChecksheetMatrixAnswers(Long userChecksheetId) {
        String sql = """
            SELECT 
                ucma.id,
                ucma.mc_result as answer,
                ucma.inspection_id,
                ucma.chks_question_id,
                ucma.chks_question_result_id,
                ucma.chks_question_result_matrix_id,
                ucma.order_no,
                ucma.judgement
            FROM user_checksheet_matrix_answers ucma
            WHERE ucma.inspection_id = :userChecksheetId
            AND ucma.deleted_by IS NULL
            ORDER BY ucma.id
        """;

        Query query = entityManager.createNativeQuery(sql);
        query.setParameter("userChecksheetId", userChecksheetId);

        List<Object[]> results = query.getResultList();
        return results.stream()
            .map(row -> {
                UserChecksheetMatrixAnswerDTO dto = new UserChecksheetMatrixAnswerDTO();
                int index = 0;
                dto.setId(((Number) row[index++]).longValue());
                dto.setAnswer((String) row[index++]);
                dto.setInspectionId(((Number) row[index++]).longValue());
                dto.setChksQuestionId(((Number) row[index++]).longValue());
                dto.setChksQuestionResultId(((Number) row[index++]).longValue());
                dto.setChksQuestionResultMatrixId(((Number) row[index++]).longValue());
                dto.setOrderNo(((Number) row[index++]).intValue());
                dto.setJudgement(row[index] != null ? ((Number) row[index]).shortValue() : null);
                return dto;
            })
            .collect(Collectors.toList());
    }

    public List<UserChecksheetMatrixAnswerDTO> getUserChecksheetMatrixAnswers(Long userChecksheetId, Long checksheetQuestionId, Long chksQuestionResultId) {
        String sql = """
            SELECT 
                ucma.id,
                ucma.mc_result as mc_result,
                ucma.result as result,
                ucma.inspection_id,
                ucma.chks_question_id,
                ucma.chks_question_result_id,
                ucma.chks_question_result_matrix_id,
                ucma.order_no,
                ucma.judgement,
                cqrm.chks_matrix_col_hdr,
                cqrm.chks_matrix_row_hdr
            FROM (select * from user_checksheet_matrix_answers ucma 
            WHERE ucma.inspection_id = :userChecksheetId
            AND ucma.chks_question_id = :checksheetQuestionId
            AND ucma.chks_question_result_id = :chksQuestionResultId ) ucma 
            LEFT JOIN (select * from chks_question_result_matrices cqrm ) cqrm on cqrm.id = ucma.chks_question_result_matrix_id 
            ORDER BY ucma.id
        """;

        Query query = entityManager.createNativeQuery(sql);
        query.setParameter("userChecksheetId", userChecksheetId);
        query.setParameter("checksheetQuestionId", checksheetQuestionId);
        query.setParameter("chksQuestionResultId", chksQuestionResultId);

        List<Object[]> results = query.getResultList();
        return results.stream()
                .map(row -> {
                    UserChecksheetMatrixAnswerDTO dto = new UserChecksheetMatrixAnswerDTO();
                    int index = 0;
                    dto.setId(((Number) row[index++]).longValue());
                    dto.setResult((String) row[index++]);
                    dto.setMcResult((String) row[index++]);
                    dto.setInspectionId(((Number) row[index++]).longValue());
                    dto.setChksQuestionId(((Number) row[index++]).longValue());
                    dto.setChksQuestionResultId(((Number) row[index++]).longValue());
                    dto.setChksQuestionResultMatrixId(((Number) row[index++]).longValue());
                    dto.setOrderNo(((Number) row[index++]).intValue());
                    dto.setJudgement(row[index] != null ? ((Number) row[index]).shortValue() : null);
                    index++;
                    dto.setChksMatrixColHdr((String) row[index++]);
                    dto.setChksMatrixRowHdr((String) row[index++]);
                    return dto;
                })
                .collect(Collectors.toList());
    }
}
