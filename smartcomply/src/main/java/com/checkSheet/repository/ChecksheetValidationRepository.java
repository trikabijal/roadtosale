package com.checkSheet.repository;

import com.checkSheet.entity.ChecksheetValidation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
@Repository
public interface ChecksheetValidationRepository extends JpaRepository<ChecksheetValidation, Long> {

    @Override
    Optional<ChecksheetValidation> findById(Long id);

    List<ChecksheetValidation> findByChecksheet_IdOrderById(Long checksheetId);

    Optional<ChecksheetValidation> findByChecksheet_IdAndValidatorUserId_Id(Long checksheetId, Long validatorUserId);

    List<ChecksheetValidation> findByChecksheet_Id(Long checksheetId);

    void deleteAllByChecksheet_Id(Long checksheetId);
}
