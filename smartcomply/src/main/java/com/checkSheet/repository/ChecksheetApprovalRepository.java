package com.checkSheet.repository;

import com.checkSheet.entity.ChecksheetApproval;
import com.checkSheet.entity.ChecksheetValidation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
@Repository
public interface ChecksheetApprovalRepository extends JpaRepository<ChecksheetApproval, Long> {

    @Override
    Optional<ChecksheetApproval> findById(Long id);

    List<ChecksheetApproval> findByChecksheet_IdOrderById(Long checksheetId);

    Optional<ChecksheetApproval> findByChecksheet_IdAndApproverUserId_Id(Long checksheetId, Long validatorUserId);

    List<ChecksheetApproval> findByChecksheet_Id(Long checksheetId);

    void deleteAllByChecksheet_Id(Long checksheetId);
}
