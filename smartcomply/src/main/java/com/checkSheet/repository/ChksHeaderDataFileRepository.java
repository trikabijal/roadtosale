package com.checkSheet.repository;

import com.checkSheet.entity.ChksHeaderDataFile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ChksHeaderDataFileRepository extends JpaRepository<ChksHeaderDataFile, Long> {

    @Override
    Optional<ChksHeaderDataFile> findById(Long id);

    @Modifying
    @Query("DELETE FROM ChksHeaderDataFile f WHERE f.checksheet.id = :checksheetId")
    void deleteByChecksheet_Id(@Param("checksheetId") Long checksheetId);

    List<ChksHeaderDataFile> findByChecksheet_IdOrderById(Long checksheetId);

    List<ChksHeaderDataFile> findByChksHeaderData_IdOrderById(Long chksHeaderDataId);
}
