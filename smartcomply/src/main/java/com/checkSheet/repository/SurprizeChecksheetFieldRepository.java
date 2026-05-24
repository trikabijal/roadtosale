package com.checkSheet.repository;

import com.checkSheet.entity.SurpriseChecksheetField;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SurprizeChecksheetFieldRepository  extends JpaRepository<SurpriseChecksheetField, Long> {
    @Override
    Optional<SurpriseChecksheetField> findById(Long id);
}
