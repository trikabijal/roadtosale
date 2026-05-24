package com.checkSheet.DAO;

import com.checkSheet.DTO.ChksGeneralFieldValueDTO;
import com.checkSheet.DTO.UsrChecksheetAnsJudgementDTO;
import com.checkSheet.DTO.UsrChksheetAnsJudgementFileDTO;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.Query;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.stream.Collectors;

@Repository
public class UsrChecksheetAnsJudgementDAO {
    @Autowired
    private EntityManager entityManager;

    public List<UsrChecksheetAnsJudgementDTO> getUsrChecksheetAnsJudgements(Long userChecksheetId) {
        try {
            String nativeQuery = """
                SELECT 
                    id,
                    inspection_id,
                    chks_question_id,
                    judgement,
                    remarks
                FROM usr_chksheet_ans_judgements
                WHERE inspection_id = :userChecksheetId
                AND deleted_at IS NULL
                ORDER BY id ASC
            """;

            return entityManager.createNativeQuery(nativeQuery, "getUsrChecksheetAnsJudgements")
                    .setParameter("userChecksheetId",userChecksheetId)
                    .getResultList();
        } catch (NoResultException e) {
            e.printStackTrace();
            return List.of();
        }
    }

    public List<UsrChksheetAnsJudgementFileDTO> usrChecksheetAnsJudgementFiles(Long userChecksheetId) {
        try {
            String nativeQuery = """
                SELECT 
                    id,
                    inspection_id,
                    usr_chksheet_ans_judgement_id,
                    path
                FROM usr_chksheet_ans_judgement_files
                WHERE inspection_id = :userChecksheetId
                AND deleted_at IS NULL
                ORDER BY id ASC
            """;

            return entityManager.createNativeQuery(nativeQuery, "getUsrChecksheetAnsJudgementFiles")
                    .setParameter("userChecksheetId",userChecksheetId)
                    .getResultList();
        } catch (NoResultException e) {
            e.printStackTrace();
            return List.of();
        }
    }

    public List<Map<String, Object>> getConsecutiveNotOkJudgements(Long checksheetId, Long escalationDays, Long operatorUserId) {
        try {
            String nativeQuery = """
                WITH RankedJudgements AS (
                    SELECT\s
                        ucaj.chks_question_id,
                        ucaj.judgement,
                        ucaj.remarks,
                        cq.name as question_name,
                        uc.created_by as user_id,
                        ucaj.created_at,
                        ROW_NUMBER() OVER (
                            PARTITION BY ucaj.chks_question_id, uc.created_by\s
                            ORDER BY uc.started_at DESC
                        ) as rn
                    FROM usr_chksheet_ans_judgements ucaj
                    JOIN (
                        SELECT * FROM inspections uc\s
                        WHERE  checksheet_id = :checksheetId
                        and status = 'APPROVED'
                    ) uc ON ucaj.inspection_id = uc.id
                    JOIN chks_questions cq ON ucaj.chks_question_id = cq.id
                    WHERE uc.checksheet_id = :checksheetId
                    AND ucaj.deleted_at IS NULL
                    order by uc.started_at DESC
                )
                SELECT\s
                    chks_question_id,
                    question_name,
                    COUNT(*) as consecutive_not_ok_count
                FROM RankedJudgements
                where 1=1
                AND rn <= :escalationDays
                AND judgement = 'NOT OK'
                GROUP BY\s
                    chks_question_id,
                    question_name
                HAVING COUNT(*) = :escalationDays
            """;

            Query query = entityManager.createNativeQuery(nativeQuery);
            query.setParameter("checksheetId", checksheetId);
            query.setParameter("escalationDays", escalationDays);
//            query.setParameter("operatorUserId", operatorUserId);

            List<Object[]> results = query.getResultList();
            
            return results.stream().map(row -> {
                Map<String, Object> map = new HashMap<>();
                map.put("chksQuestionId", ((Number) row[0]).longValue());
                map.put("questionName", (String) row[1]);
//                map.put("userId", ((Number) row[2]).longValue());
                map.put("consecutiveCount", ((Number) row[2]).longValue());
                return map;
            }).collect(Collectors.toList());
        } catch (Exception e) {
            e.printStackTrace();
            return List.of();
        }
    }
}
