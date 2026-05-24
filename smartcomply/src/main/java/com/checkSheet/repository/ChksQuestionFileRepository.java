package com.checkSheet.repository;

import com.checkSheet.entity.ChksQuestionFile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
@Repository
public interface ChksQuestionFileRepository extends JpaRepository<ChksQuestionFile, Long> {

    @Override
    Optional<ChksQuestionFile> findById(Long id);

    @Modifying
    @Query("DELETE FROM ChksQuestionFile f WHERE f.checksheet.id = :checksheetId")
    void deleteByChecksheet_Id(@Param("checksheetId") Long checksheetId);

    List<ChksQuestionFile> findByChecksheet_IdOrderById(Long checksheetId);

    List<ChksQuestionFile> findByChksQuestion_Id(Long chksQuestionId);

    List<ChksQuestionFile> findByChksQuestion_IdOrderById(Long chksQuestionId);

}
