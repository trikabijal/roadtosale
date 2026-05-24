package com.checkSheet.repository;

import com.checkSheet.entity.ChksGeneralFieldValue;
import com.checkSheet.entity.ChksHeaderData;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
@Repository
public interface ChksGeneralFieldValueRepository extends JpaRepository<ChksGeneralFieldValue, Long> {

    @Override
    Optional<ChksGeneralFieldValue> findById(Long id);

    Optional<ChksGeneralFieldValue> findByInspectionIdAndChksGeneralFieldId(Long userChecksheetId, Long chksGeneralFieldId);
    List<ChksGeneralFieldValue> findByInspection_Id(Long userChecksheetId);
}
