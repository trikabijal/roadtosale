package com.checkSheet.DAO;

import com.checkSheet.DTO.LovDataDTO;
import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Repository
public class LovDataDAO {

    @Autowired
    private EntityManager entityManager;

    public List<LovDataDTO> getAllLovData() {
        try {
            String query = "select ld.name, ld.value, ld.value_type from lov_data ld ";
            List<LovDataDTO> result = (List<LovDataDTO>) entityManager.createNativeQuery(query, "getLovData").getResultList();
            return result;
        } catch (Exception e) {
            e.printStackTrace();
            return new ArrayList<>();
        }
    }

    public LovDataDTO getLovData(String name) {
        try {
            String query = "select ld.name, ld.value, ld.value_type from lov_data ld ";
            if(!Objects.equals(name, null) && !Objects.equals(name, "")) {
                query += " where lower(ld.name)  = '" + name.toLowerCase() + "' ";
            }
            LovDataDTO result = (LovDataDTO) entityManager.createNativeQuery(query, "getLovData").getSingleResult();
            return result;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }


}
