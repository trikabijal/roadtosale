package com.checkSheet.repository;

import com.checkSheet.entity.ChksHeaderData;
import com.checkSheet.entity.ChksQuestion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
@Repository
public interface ChksQuestionRepository extends JpaRepository<ChksQuestion, Long> {

    @Override
    Optional<ChksQuestion> findById(Long id);

    @Modifying
    @Query("DELETE FROM ChksQuestion q WHERE q.checksheet.id = :checksheetId")
    void deleteByChecksheet_Id(@Param("checksheetId") Long checksheetId);

    List<ChksQuestion> findByChecksheet_IdOrderById(Long checksheetId);
    List<ChksQuestion> findByIdIn(List<Long> questionIds);
    List<ChksQuestion> findByChksHeaderData_Id(Long chksHeaderDataId);

    @Query("SELECT cq FROM ChksQuestion cq WHERE cq.checksheet.id = :checksheetId and cq.chksHeaderData.id = :chksHeaderDataId " +
            " order by cq.orderNo, cq.id ")
    List<ChksQuestion> findByChecksheet_IdAndChecksheetChksHeaderDataOrderById(Long checksheetId, Long chksHeaderDataId);

    @Query("SELECT cq FROM ChksQuestion cq WHERE cq.checksheet.id = :checksheetId and cq.chksHeaderData.id is null " +
            " order by cq.orderNo, cq.id ")
    List<ChksQuestion> findByChecksheet_IdAndChecksheetChksHeaderDataIsNullOrderById(Long checksheetId);

    Optional<ChksQuestion> findQuestionById(Long chksQuestionId);

    @Modifying
    @Query("DELETE FROM ChksQuestion q WHERE q.id IN (:questionIds)")
    void deleteQuestionsByIds(@Param("questionIds") List<Long> questionIds);

    @Query("SELECT COUNT(cq) FROM ChksQuestion cq WHERE cq.checksheet.id = :checksheetId")
    long countByChecksheetId(@Param("checksheetId") Long checksheetId);

}
