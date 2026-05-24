package com.checkSheet.repository;

import com.checkSheet.entity.ChksHeader;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
@Repository
public interface ChksHeaderRepository extends JpaRepository<ChksHeader, Long> {

    @Override
    Optional<ChksHeader> findById(Long id);

    Optional<ChksHeader> findByChksHeader_Id(Long id);

    List<ChksHeader> findByChecksheet_IdAndIsResultColumnFalseOrderById(Long checksheetId);

    List<ChksHeader> findByChecksheet_IdAndIsResultColumnTrueOrderById(Long checksheetId);

    List<ChksHeader> findByChecksheet_IdOrderById(Long checksheetId);

    List<ChksHeader> findByChecksheetIdAndSummaryReportLevel(Long checksheetId, Byte summaryReportLevel);

    void deleteByChecksheet_Id(Long checkhseetId);
}
