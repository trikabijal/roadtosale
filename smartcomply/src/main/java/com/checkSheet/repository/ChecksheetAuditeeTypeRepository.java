package com.checkSheet.repository;

import com.checkSheet.entity.ChecksheetAuditeeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ChecksheetAuditeeTypeRepository extends JpaRepository<ChecksheetAuditeeType, Long> {
    void deleteAllByChecksheet_Id(Long checksheetId);

    List<ChecksheetAuditeeType> findByChecksheet_IdAndDeletedByIsNull(Long checksheetId);
}
