package com.checkSheet.repository;

import com.checkSheet.DTO.UserChecksheetValidationHistoryDTO;
import com.checkSheet.entity.UserChecksheetValidationHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
@Repository
public interface UserChecksheetValidationHistoryRepository extends JpaRepository<UserChecksheetValidationHistory, Long> {

    @Override
    Optional<UserChecksheetValidationHistory> findById(Long id);

    List<UserChecksheetValidationHistory> findByChecksheet_IdOrderById(Long checksheetId);

    Optional<UserChecksheetValidationHistory> findByChecksheet_IdAndDataValidatorUserId_Id(Long checksheetId, Long dataValidatorUserId);

    List<UserChecksheetValidationHistory> findByChecksheet_Id(Long checksheetId);

    List<UserChecksheetValidationHistory> findByInspection_IdAndVersion(Long id, Byte submissionVersion);
}
