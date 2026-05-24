package com.checkSheet.DAO;

import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

@Repository
public class UserChecksheetApprovalDAO {

    @Autowired
    private EntityManager entityManager;
}
