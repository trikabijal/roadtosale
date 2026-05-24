package com.checkSheet.DAO;

import com.checkSheet.DTO.ChecksheetDTO;
import com.checkSheet.DTO.ChksGeneralFieldDTO;
import com.checkSheet.DTO.ChksHeaderDataDTO;
import com.checkSheet.constant.ChecksheetStatusType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Repository
public class ChksHeaderDataDAO {

    @Autowired
    private EntityManager entityManager;

    @SuppressWarnings("unchecked")
    public List<Object[]> getChksHeaderDataByChecksheetId(Long checksheetId) {
        try {
            String query = """
                WITH RECURSIVE header_hierarchy AS (
                    SELECT DISTINCT ON (hd.id)
                        hd.id, hd.name, hd.description, hd.chks_header_id,
                        hd.chks_header_data_id, hd.level, hd.order_no,
                        CAST(hd.id as varchar) as path
                    FROM chks_header_data hd
                    WHERE hd.checksheet_id = :checksheetId 
                    AND hd.chks_header_data_id IS NULL
                    
                    UNION ALL
                    
                    SELECT DISTINCT ON (child.id)
                        child.id, child.name, child.description, 
                        child.chks_header_id, child.chks_header_data_id,
                        child.level, child.order_no,
                        CAST(h.path || ',' || child.id as varchar)
                    FROM (select * from chks_header_data hd WHERE hd.checksheet_id = :checksheetId) child
                    INNER JOIN header_hierarchy h ON h.id = child.chks_header_data_id
                )
                SELECT 
                     h.id, h.name, h.description, h.chks_header_id, h.chks_header_data_id, h.level, h.order_no
                FROM header_hierarchy h
               JOIN (
                   SELECT * FROM chks_header_data hd WHERE hd.checksheet_id = :checksheetId
               ) chd ON chd.id = h.id
               ORDER BY CASE WHEN h.chks_header_data_id IS NULL THEN 0 ELSE 1 END, chks_header_data_id, h.order_no NULLS LAST, h.id, h.path, h.level
            """;

            Query nativeQuery = entityManager.createNativeQuery(query)
                    .setParameter("checksheetId", checksheetId);

            return nativeQuery.getResultList();
        } catch (Exception e) {
            e.printStackTrace();
            return new ArrayList<>();
        }
    }

    public List<ChksHeaderDataDTO> getChksHeaderData(Long checksheetId) {
        try {
            String query = "SELECT id,name,description,checksheet_id,chks_header_id,chks_header_data_id,level,order_no " +
                    "FROM chks_header_data where checksheet_id = " + checksheetId +" order by id " ;
            List<ChksHeaderDataDTO> result = (List<ChksHeaderDataDTO>) entityManager.createNativeQuery(query, "getChksHeaderDataByChksId").getResultList();
            return result;
        } catch(Exception e) {
            e.printStackTrace();
            return new ArrayList<>();
        }
    }
}
