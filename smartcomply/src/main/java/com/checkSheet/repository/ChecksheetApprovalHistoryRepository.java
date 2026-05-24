package com.checkSheet.repository;

import com.checkSheet.entity.ChecksheetApprovalHistory;
import com.checkSheet.entity.ChecksheetApprovalHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
@Repository
public interface ChecksheetApprovalHistoryRepository extends JpaRepository<ChecksheetApprovalHistory, Long> {

    @Override
    Optional<ChecksheetApprovalHistory> findById(Long id);

    List<ChecksheetApprovalHistory> findByChecksheet_IdOrderById(Long checksheetId);

    Optional<ChecksheetApprovalHistory> findByChecksheet_IdAndApproverUserId_Id(Long checksheetId, Long validatorUserId);

    List<ChecksheetApprovalHistory> findByChecksheet_Id(Long checksheetId);

    void deleteAllByChecksheet_Id(Long checksheetId);

}
