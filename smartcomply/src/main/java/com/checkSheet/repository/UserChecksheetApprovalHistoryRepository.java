package com.checkSheet.repository;

import com.checkSheet.DTO.UserChecksheetApprovalHistoryDTO;
import com.checkSheet.entity.UserChecksheetApprovalHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
@Repository
public interface UserChecksheetApprovalHistoryRepository extends JpaRepository<UserChecksheetApprovalHistory, Long> {

    @Override
    Optional<UserChecksheetApprovalHistory> findById(Long id);

    List<UserChecksheetApprovalHistory> findByChecksheet_IdOrderById(Long checksheetId);

    Optional<UserChecksheetApprovalHistory> findByChecksheet_IdAndDataApproverUserId_Id(Long checksheetId, Long dataApproverUserId);

    List<UserChecksheetApprovalHistory> findByChecksheet_Id(Long checksheetId);

    List<UserChecksheetApprovalHistory> findByInspection_IdAndVersion(Long id, Byte submissionVersion);
}
