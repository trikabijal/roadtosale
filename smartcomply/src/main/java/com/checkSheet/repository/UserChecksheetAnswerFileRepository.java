package com.checkSheet.repository;

import com.checkSheet.entity.UserChecksheetAnswerFile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Date;
import java.util.List;
import java.util.Optional;

@Repository
public interface UserChecksheetAnswerFileRepository extends JpaRepository<UserChecksheetAnswerFile, Long> {

    @Override
    Optional<UserChecksheetAnswerFile> findById(Long id);

    List<UserChecksheetAnswerFile> findByIdIn(List<Long> userChecksheetAnswerFileIds);

    void deleteByIdIn(List<Long> userChecksheetAnswerFileIds);

    List<UserChecksheetAnswerFile> findByUserChecksheetAnswer_Inspection_IdAndDeletedAtIsNull(Long userChecksheetId);

    List<UserChecksheetAnswerFile> findByUserChecksheetAnswer_IdAndDeletedAtIsNullOrderByIdAsc(Long userChecksheetAnswerId);

    boolean existsByUserChecksheetAnswer_IdAndPathAndDeletedAtIsNull(Long userChecksheetAnswerId, String path);

    List<UserChecksheetAnswerFile> findByUserChecksheetAnswer_IdAndPathInAndDeletedAtIsNull(Long userChecksheetAnswerId, List<String> paths);

    List<UserChecksheetAnswerFile> findByUserChecksheetAnswer_IdAndDeletedAtIsNull(Long userChecksheetAnswerId);

    List<UserChecksheetAnswerFile> findByUserChecksheetAnswer_IdAndDeletedAtIsNullAndCreatedAtBetween(
        Long userChecksheetAnswerId, Date startDate, Date endDate
    );
}
