package com.checkSheet.repository;

import com.checkSheet.entity.Inspection;
import com.checkSheet.entity.UserChecksheetTraceValue;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
@Repository
public interface UserChecksheetTraceValueRepository  extends JpaRepository<UserChecksheetTraceValue, Long> {
    @Override
    Optional<UserChecksheetTraceValue> findById(Long id);

    Optional<UserChecksheetTraceValue> findByInspectionIdAndChksHeaderDataId(Long userChecksheetId, Long chksHeaderDataId);

    List<UserChecksheetTraceValue> findByInspection_IdIn(List<Long> userChksIds);
}
