package com.checkSheet.DAO;

import com.checkSheet.DTO.ChksHeaderDataFileDTO;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Repository
public class ChksHeaderDataFileDAO {

    @Autowired
    private EntityManager entityManager;


    public List<ChksHeaderDataFileDTO> getChksHeaderDataFileByChecksheetId(Long checksheetId) {
        try {
            String query = "Select id, path, chks_header_data_id from chks_header_data_files chdf where 1=1 ";
            if(!Objects.isNull(checksheetId)){
                query += " and chdf.checksheet_id = :checksheetId ";
            }
            query += " order by chdf.chks_header_data_id, chdf.id asc ";

            Query nativeQuery = entityManager.createNativeQuery(query, "getChksHeaderDataFileByChecksheetId")
                    .setParameter("checksheetId", checksheetId);

            return nativeQuery.getResultList();
        } catch (Exception e) {
            e.printStackTrace();
            return new ArrayList<>();
        }
    }
}
