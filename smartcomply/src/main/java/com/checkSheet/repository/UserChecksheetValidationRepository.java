package com.checkSheet.repository;

import com.checkSheet.entity.ChecksheetValidation;
import com.checkSheet.entity.UserChecksheetValidation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
@Repository
public interface UserChecksheetValidationRepository extends JpaRepository<UserChecksheetValidation, Long> {

    @Override
    Optional<UserChecksheetValidation> findById(Long id);

    List<UserChecksheetValidation> findByChecksheet_IdOrderById(Long checksheetId);

    Optional<UserChecksheetValidation> findByChecksheet_IdAndDataValidatorUserId_Id(Long checksheetId, Long dataValidatorUserId);

    // Post-V1.28: review state is per-INSPECTION (one submission), not per-template.
    // Two inspections of the same template reviewed by the same validator must
    // not collide on the lookup-or-create path; key on inspection_id instead.
    Optional<UserChecksheetValidation> findByInspection_IdAndDataValidatorUserId_IdAndDeletedAtIsNull(
            Long inspectionId, Long dataValidatorUserId);

    List<UserChecksheetValidation> findByChecksheet_Id(Long checksheetId);

    void deleteAllByChecksheet_Id(Long checksheetId);

    List<UserChecksheetValidation> findByInspection_Id(Long id);
}
