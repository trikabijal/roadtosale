package com.checkSheet.repository;

import com.checkSheet.entity.ChecksheetValidationHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
@Repository
public interface ChecksheetValidationHistoryRepository extends JpaRepository<ChecksheetValidationHistory, Long> {

    @Override
    Optional<ChecksheetValidationHistory> findById(Long id);

    List<ChecksheetValidationHistory> findByChecksheet_IdOrderById(Long checksheetId);

    Optional<ChecksheetValidationHistory> findByChecksheet_IdAndValidatorUserId_Id(Long checksheetId, Long validatorUserId);

    List<ChecksheetValidationHistory> findByChecksheet_Id(Long checksheetId);
    void deleteAllByChecksheet_Id(Long checksheetId);

}
