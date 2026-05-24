package com.checkSheet.DAO;

import com.checkSheet.DTO.ChksQuestionResultMatrixDTO;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

@Repository
public class ChksQuestionResultMatrixDAO {

    @Autowired
    private EntityManager entityManager;

    @SuppressWarnings("unchecked")
    public List<ChksQuestionResultMatrixDTO> getMatrixData(Long chksQuestionResultId) {
        try {
            String nativeQuery = """
                SELECT  
                    cqrm.id,
                    cqrm.chks_matrix_row_hdr,
                    cqrm.chks_matrix_col_hdr,
                    cqrm.data,
                    cqrm.comment,
                    cqrm.row_id,
                    cqrm.column_id
                FROM chks_question_result_matrices cqrm
                WHERE cqrm.chks_question_result_id = :chksQuestionResultId
                ORDER BY id
            """;

            List<ChksQuestionResultMatrixDTO> results = (List<ChksQuestionResultMatrixDTO>) entityManager.createNativeQuery(nativeQuery, "getMatrixData")
                .setParameter("chksQuestionResultId", chksQuestionResultId)
                .getResultList();

            return results;
        } catch (NoResultException e) {
            return new ArrayList<>();
        }
    }

    public List<ChksQuestionResultMatrixDTO> getMatrixDataByChksId(Long chksId) {
        try {
            String nativeQuery = """
                SELECT  
                    cqrm.id,
                    cqrm.chks_matrix_row_hdr,
                    cqrm.chks_matrix_col_hdr,
                    cqrm.data,
                    cqrm.comment,
                    cqrm.row_id,
                    cqrm.column_id,
                    cqrm.chks_question_result_id
                FROM chks_question_result_matrices cqrm
                WHERE cqrm.checksheet_id = :chksId
                ORDER BY id
            """;

            List<ChksQuestionResultMatrixDTO> results = (List<ChksQuestionResultMatrixDTO>) entityManager.createNativeQuery(nativeQuery, "getMatrixDataByChksId")
                    .setParameter("chksId", chksId)
                    .getResultList();

            return results;
        } catch (NoResultException e) {
            return new ArrayList<>();
        }
    }
}
