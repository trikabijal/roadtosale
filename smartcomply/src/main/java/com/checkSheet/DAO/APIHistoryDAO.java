package com.checkSheet.DAO;

import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;


@Repository
public class APIHistoryDAO {

    @Autowired
    private EntityManager entityManager;

    @Transactional
    public void deleteAllByCreatedDateBefore(String date) {
        try {
            String query = "delete from api_history ah where ah.created_at < '" + date + "' ";
            entityManager.createNativeQuery(query).executeUpdate();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
