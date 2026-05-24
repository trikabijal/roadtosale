package com.checkSheet.DAO;

import com.checkSheet.DTO.ChksGeneralFieldValueDTO;
import com.checkSheet.DTO.UserChecksheetAnswerDTO;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class ChksGeneralFieldValueDAO {
    @Autowired
    private EntityManager entityManager;

    public List<ChksGeneralFieldValueDTO> getUserChksGeneralFieldValues(Long userChecksheetId) {
        try {
            String nativeQuery = """
                SELECT 
                    id,
                    inspection_id,
                    chks_general_field_id,
                    value
                FROM chks_general_field_values
                WHERE inspection_id = :userChecksheetId
                AND deleted_at IS NULL
                ORDER BY id ASC
            """;

            return entityManager.createNativeQuery(nativeQuery, "getUserChksGeneralFieldValues")
                    .setParameter("userChecksheetId",userChecksheetId)
                    .getResultList();
        } catch (NoResultException e) {
            e.printStackTrace();
            return List.of();
        }
    }

}
