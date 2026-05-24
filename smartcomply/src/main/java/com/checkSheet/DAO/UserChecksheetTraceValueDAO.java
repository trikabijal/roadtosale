package com.checkSheet.DAO;

import com.checkSheet.DTO.UserChecksheetAnswerDTO;
import com.checkSheet.DTO.UserChecksheetTraceValueDTO;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import java.util.List;
@Repository
public class UserChecksheetTraceValueDAO {
    @Autowired
    private EntityManager entityManager;

    public List<UserChecksheetTraceValueDTO> getUserChksAnswerTraceValues(Long userChecksheetId) {
        try {
            String nativeQuery = """
                SELECT 
                    id,
                    inspection_id,
                    chks_header_data_id,
                    trace_value                    
                FROM user_checksheet_trace_values
                WHERE inspection_id = :userChecksheetId
                AND deleted_at IS NULL
                ORDER BY id ASC
            """;

            return entityManager.createNativeQuery(nativeQuery, "getUserChksTraceValues")
                    .setParameter("userChecksheetId",userChecksheetId)
                    .getResultList();
        } catch (NoResultException e) {
            e.printStackTrace();
            return List.of();
        }
    }

}
