package com.checkSheet.DAO;

import com.checkSheet.DTO.ChksHeaderDTO;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Repository
public class ChecksheetApprovalDAO {

    @Autowired
    private EntityManager entityManager;
}
