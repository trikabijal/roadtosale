package com.checkSheet.DAO;

import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Repository
public class UserRoleDepartmentDAO {

    @Autowired
    private EntityManager entityManager;

    public List<String> getUserByEmailAndUserId(Long userId) {
        try {
            String query = "SELECT distinct urd.role_code FROM " +
                    " ( (select urd.role_id from user_role_departments urd " +
                    " WHERE urd.user_id  = " + userId + ") urd" +
                    " join (select r.id, r.role_code from roles r ) r on urd.role_id = r.id) urd  ";
            List<String> result = (List<String>) entityManager.createNativeQuery(query).getResultList();
            return result;
        } catch(Exception e) {
            e.printStackTrace();
            return new ArrayList<>();
        }
    }

    public Map<String, String> getRoleNamesForUser(Long userId) {
        try {
            String query = "SELECT DISTINCT r.role_code, r.name as role_name FROM user_role_departments urd " +
                    "JOIN roles r ON urd.role_id = r.id " +
                    "WHERE urd.user_id = " + userId + " AND urd.deleted_by IS NULL";
            List<Object[]> result = entityManager.createNativeQuery(query).getResultList();
            Map<String, String> roleNames = new HashMap<>();
            for (Object[] row : result) {
                roleNames.put((String) row[0], (String) row[1]);
            }
            return roleNames;
        } catch (Exception e) {
            e.printStackTrace();
            return new HashMap<>();
        }
    }
}
