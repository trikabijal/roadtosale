package com.checkSheet.DAO;

import com.checkSheet.DTO.ChksGeneralFieldDTO;
import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Repository
public class ChksGeneralFieldDAO {

    @Autowired
    private EntityManager entityManager;

    public List<Long> getChksGeneralFieldByName(Long ChksGeneralFieldId, Long checkSheetId, String name) {
        try {
            String query = "SELECT cgf.id FROM chks_general_fields cgf where 1=1 ";
            if(!Objects.isNull(checkSheetId)) {
                query += " and cgf.checksheet_id = '" + checkSheetId +"' ";
            }
            if(!Objects.isNull(ChksGeneralFieldId)) {
                query += " and cgf.id != '" + ChksGeneralFieldId +"' ";
            }
            if(!Objects.isNull(name) && !name.isEmpty()) {
                query += " and cgf.name = '" + name + "' ";
            }
            List<Long> result = (List<Long>) entityManager.createNativeQuery(query).getResultList();
            return result;
        } catch(Exception e) {
            e.printStackTrace();
            return new ArrayList<>();
        }
    }

    public List<ChksGeneralFieldDTO> getChksGeneralFieldByChecksheetId(Long checkSheetId) {
        try {
            String query = "SELECT cgf.id, cgf.name, cgf.checksheet_id FROM chks_general_fields cgf where 1=1 ";
            if(!Objects.isNull(checkSheetId)) {
                query += " and cgf.checksheet_id = '" + checkSheetId +"' ";
            }
            query += " order by cgf.id asc ";
            List<ChksGeneralFieldDTO> result = (List<ChksGeneralFieldDTO>) entityManager.createNativeQuery(query, "getChksGeneralFieldByChecksheetId").getResultList();
            return result;
        } catch(Exception e) {
            e.printStackTrace();
            return new ArrayList<>();
        }
    }
}
