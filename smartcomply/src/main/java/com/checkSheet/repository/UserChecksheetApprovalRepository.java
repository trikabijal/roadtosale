package com.checkSheet.repository;

import com.checkSheet.entity.ChecksheetApproval;
import com.checkSheet.entity.UserChecksheetApproval;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
@Repository
public interface UserChecksheetApprovalRepository extends JpaRepository<UserChecksheetApproval, Long> {

    @Override
    Optional<UserChecksheetApproval> findById(Long id);

    List<UserChecksheetApproval> findByChecksheet_IdOrderById(Long checksheetId);

    Optional<UserChecksheetApproval> findByChecksheet_IdAndDataApproverUserId_Id(Long checksheetId, Long dataApproverUserId);

    // Post-V1.28: review state is per-INSPECTION (one submission), not per-template.
    // Two inspections of the same template reviewed by the same approver must
    // not collide on the lookup-or-create path; key on inspection_id instead.
    Optional<UserChecksheetApproval> findByInspection_IdAndDataApproverUserId_IdAndDeletedAtIsNull(
            Long inspectionId, Long dataApproverUserId);

    List<UserChecksheetApproval> findByChecksheet_Id(Long checksheetId);

    void deleteAllByChecksheet_Id(Long checksheetId);
}
