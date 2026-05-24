package com.checkSheet.DAO;

import com.checkSheet.DTO.ChksQuestionDTO;
import com.checkSheet.DTO.ChksQuestionFileDTO;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Repository
public class ChksQuestionFileDAO {

    @Autowired
    private EntityManager entityManager;

    public List<ChksQuestionFileDTO> getChksQuestionFileByChecksheetId(Long checksheetId) {
        try {
            String query = "Select id, path, chks_question_id from chks_question_files where 1=1 ";
            if(!Objects.isNull(checksheetId)){
                query += " and checksheet_id = :checksheetId ";
            }
            query += " order by chks_question_id, id asc ";

            Query nativeQuery = entityManager.createNativeQuery(query, "getChksQuestionFileDTO")
                    .setParameter("checksheetId", checksheetId);
            return nativeQuery.getResultList();
        } catch (Exception e) {
            e.printStackTrace();
            return new ArrayList<>();
        }
    }
}
