package com.checkSheet.DAO;

import com.checkSheet.DTO.ChksGeneralFieldDTO;
import com.checkSheet.DTO.ChksHeaderDTO;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Repository
public class ChksHeaderDAO {

    @Autowired
    private EntityManager entityManager;

    public List<Long> getChksHeaderByName(Long chksHeaderId, Long checkSheetId, String name) {
        try {
            String query = "SELECT ch.id FROM chks_headers ch where 1=1 ";
            if(!Objects.isNull(checkSheetId)) {
                query += " and ch.checksheet_id = '" + checkSheetId +"' ";
            }
            if(!Objects.isNull(chksHeaderId)) {
                query += " and ch.id != '" + chksHeaderId +"' ";
            }
            if(!Objects.isNull(name) && !name.isEmpty()) {
                query += " and ch.name = '" + name + "' ";
            }
            List<Long> result = (List<Long>) entityManager.createNativeQuery(query).getResultList();
            return result;
        } catch(Exception e) {
            e.printStackTrace();
            return new ArrayList<>();
        }
    }

    public Long getChksHeaderParentIdByCheckSheetID(Long chksHeaderId, Long checkSheetId, Boolean isResultColumn) {
        try {
            String query = "SELECT ch.id FROM chks_headers ch where 1=1 ";
            if(!Objects.isNull(checkSheetId)) {
                query += " and ch.checksheet_id = '" + checkSheetId +"' ";
            }
            if(!Objects.isNull(chksHeaderId)) {
                query += " and ch.id != '" + chksHeaderId +"' ";
            }
            if(!Objects.isNull(isResultColumn) && !isResultColumn) {
                query += " and ch.is_result_column = false ";
            }
            query += " order by ch.id desc limit 1 ";
            Long result = (Long) entityManager.createNativeQuery(query).getSingleResult();
            return result;
        } catch(Exception e) {
            e.printStackTrace();
            return -1L;
        }
    }

    public List<ChksHeaderDTO> getChksHeaderByChecksheetId(Long checkSheetId, Boolean isResultColumn) {
        try {
            String query = "SELECT ch.id, ch.name, ch.checksheet_id, ch.chks_header_id, ch.is_result_column, ch.is_traceable, ch.summary_report_level" +
                    " FROM chks_headers ch where 1=1 ";
            if(!Objects.isNull(isResultColumn)) {
                query += " and ch.is_result_column = '" + isResultColumn +"' ";
            }
            if(!Objects.isNull(checkSheetId)) {
                query += " and ch.checksheet_id = '" + checkSheetId +"' ";
            }
            query += " order by ch.id ";
            List<ChksHeaderDTO> result = (List<ChksHeaderDTO>) entityManager.createNativeQuery(query, "getChksHeaderByChecksheetId").getResultList();
            return result;
        } catch(Exception e) {
            e.printStackTrace();
            return new ArrayList<>();
        }
    }

    public Long getChksHeaderByChksHeaderId(Long checkSheetId, List<Long> chksHeaderId, boolean isResultColumn) {
        try {
            String nativeQuery = "SELECT ch.id " +
                    " FROM chks_headers ch " +
                    "WHERE ch.id NOT IN (SELECT chks_header_id FROM chks_headers ch WHERE chks_header_id IS NOT NULL and ch.checksheet_id = '" + checkSheetId + "'\n" +
                    "  AND ch.is_result_column = '" + isResultColumn + "') \n" +
                    "  AND ch.checksheet_id = '" + checkSheetId +"' \n" +
                    "  AND ch.is_result_column = '" + isResultColumn + "' \n" +
                    " ORDER BY ch.id ";
            Query query = entityManager.createNativeQuery(nativeQuery);
//            query.setParameter("chksHeaderId", chksHeaderId);
            Long result = (Long) query.getSingleResult();
            return result;
        } catch(Exception e) {
            e.printStackTrace();
            return null;
        }
    }
}
