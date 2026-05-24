package com.checkSheet.repository;

import com.checkSheet.entity.SurpriseChecksheet;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SurpriseChecksheetRepository extends JpaRepository<SurpriseChecksheet, Long> {
    @Override
    Optional<SurpriseChecksheet> findById(Long id);
}
