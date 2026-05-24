package com.checkSheet.repository;

import com.checkSheet.entity.Checksheet;
import com.checkSheet.entity.Inspection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Date;
import java.util.List;
import java.util.Optional;
@Repository
public interface UserChecksheetRepository extends JpaRepository<Inspection, Long> {

    @Override
    Optional<Inspection> findById(Long id);

    @Query("SELECT uc FROM Inspection uc WHERE uc.operatorUser.id = :userId AND uc.checksheet.id = :checksheetId")
    List<Inspection> findByUserIdChecksheetId(Long userId, Long checksheetId);
    List<Inspection> findByChecksheet(Checksheet checksheet);

    @Query("SELECT u FROM Inspection u WHERE u.operatorUser.id = :userId AND u.checksheet.id = :checksheetId AND DATE(u.startedAt) BETWEEN :startDate AND :endDate and u.status not in ('IN_PROGRESS') ORDER BY u.submittedAt DESC")
    List<Inspection> findByUserIdChecksheetIdAndStartedDateBetween(
            @Param("userId") Long userId,
            @Param("checksheetId") Long checksheetId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate
    );

    @Query(value = "SELECT COUNT(uc) FROM inspections uc WHERE :userId = ANY(uc.waiting_user_ids)", nativeQuery = true)
    long getWaitingCount(Long userId);

    /** Post-V1.28: AA id == inspection id. Look up by inspection id + kind=AUDIT + status. */
    @Query("""
        SELECT i FROM Inspection i
         WHERE i.id = :id AND i.kind = 'AUDIT' AND i.status = :status AND i.deletedAt IS NULL
    """)
    Optional<Inspection> findByAuditAssignmentIdAndStatusAndDeletedAtIsNull(
            @Param("id") Long auditAssignmentId, @Param("status") String status);

    /** Post-V1.28: a single intervention assignment maps to a single Inspection.
     *  Returned as a list for caller compatibility (was multi-wave). */
    @Query("""
        SELECT i FROM Inspection i
         WHERE i.id = :iaId AND i.kind = 'INTERVENTION' AND i.deletedAt IS NULL
    """)
    List<Inspection> findReinspectionsByInterventionAssignmentId(@Param("iaId") Long interventionAssignmentId);

    /** Post-V1.28: AA id == inspection id. Returns the single matching inspection
     *  in a list (was a multi-row result before, now always 0 or 1). */
    @Query("""
        SELECT i FROM Inspection i
         WHERE i.id = :aaId AND i.kind = 'AUDIT' AND i.deletedAt IS NULL
    """)
    List<Inspection> findByAuditAssignmentIdAndDeletedAtIsNullOrdered(@Param("aaId") Long auditAssignmentId);
}
