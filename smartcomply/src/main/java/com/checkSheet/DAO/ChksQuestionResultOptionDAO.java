package com.checkSheet.DAO;

import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import com.checkSheet.DTO.ChksQuestionResultOptionDTO;

import java.util.List;

@Repository
public class ChksQuestionResultOptionDAO {

    @Autowired
    private EntityManager entityManager;
    
    public List<ChksQuestionResultOptionDTO> getOptionData(Long chksQuestionResultId) {
        try {
            String nativeQuery = """
                SELECT 
                    cqro.id,
                    cqro.option,
                    cqro.judgement
                FROM chks_question_result_options cqro
                WHERE cqro.chks_question_result_id = :chksQuestionResultId
                AND cqro.deleted_at IS NULL
                ORDER BY cqro.id ASC
            """;

            return entityManager.createNativeQuery(nativeQuery, "getOptionData")
                    .setParameter("chksQuestionResultId", chksQuestionResultId)
                    .getResultList();
        } catch (NoResultException e) {
            e.printStackTrace();
            return List.of();
        }
    }
    public List<ChksQuestionResultOptionDTO> getOptionDataByChksId(Long chksId) {
        try {
            String nativeQuery = """
                SELECT 
                    cqro.id,
                    cqro.option,
                    cqro.judgement,
                    cqro.chks_question_result_id
                FROM chks_question_result_options cqro
                WHERE cqro.checksheet_id = :chksId
                AND cqro.deleted_at IS NULL
                ORDER BY cqro.id ASC
            """;

            return entityManager.createNativeQuery(nativeQuery, "getOptionDataByChksId")
                    .setParameter("chksId", chksId)
                    .getResultList();
        } catch (NoResultException e) {
            e.printStackTrace();
            return List.of();
        }
    }
}
