package com.checkSheet.DAO;

import com.checkSheet.DTO.ChksQuestionDTO;
import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Repository
public class ChksQuestionDAO {

    @Autowired
    private EntityManager entityManager;



    public List<ChksQuestionDTO> getChksQuestionByChecksheetHeaderDataId(Long cqksHeaderDataId) {
        try {
            String query = "SELECT cq.id, cq.name, cq.description, cq.order_no " +
                    " FROM chks_questions cq where 1=1 ";
            if(!Objects.isNull(cqksHeaderDataId)) {
                query += " and cq.chks_header_data_id = " + cqksHeaderDataId +" ";
            }
            query += " order by cq.order_no NULLS LAST, cq.id ";
            List<ChksQuestionDTO> result = (List<ChksQuestionDTO>) entityManager.createNativeQuery(query, "getChksQuestionByChecksheetHeaderDataId").getResultList();
            return result;
        } catch (Exception e) {
            e.printStackTrace();
            return new ArrayList<>();
        }
    }
    public List<ChksQuestionDTO> getChksQuestionByChecksheetId(Long cqksId) {
        try {
            String query = "SELECT id, name, description,chks_header_id, chks_header_data_id, order_no " +
                    " FROM chks_questions where 1=1 ";
            if(!Objects.isNull(cqksId)) {
                query += " and checksheet_id = '" + cqksId +"' ";
            }
            query += " order by id ";
            System.out.println(query);
            List<ChksQuestionDTO> result = (List<ChksQuestionDTO>) entityManager.createNativeQuery(query, "getChksQuestionByChecksheetId").getResultList();
            return result;
        } catch (Exception e) {
            e.printStackTrace();
            return new ArrayList<>();
        }
    }
}
