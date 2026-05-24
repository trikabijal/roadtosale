package com.checkSheet.repository;

import com.checkSheet.constant.ChecksheetStatusType;
import com.checkSheet.constant.ChecksheetType;
import com.checkSheet.entity.Checksheet;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.Date;
@Repository

public interface ChecksheetRepository extends JpaRepository<Checksheet, Long> {

    @Override
    Optional<Checksheet> findById(Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM Checksheet c WHERE c.id = :id")
    Optional<Checksheet> findByIdWithLock(@Param("id") Long id);

    List<Checksheet> findByChecksheet_Id(Long id);

    List<Checksheet> findByDepartment_IdAndIdNot(Long departmentId, Long checksheetId);

    boolean existsByModelNoIgnoreCase(String modelNo);
    boolean existsByModelNoIgnoreCaseAndIdNot(String modelNo, Long id);

    List<Checksheet> findByChecksheetTypeAndStatus(ChecksheetType aPublic, ChecksheetStatusType approved);

    @Query(
        value = """
        SELECT COALESCE(MAX(CAST(SUBSTRING(c.uid FROM 'F(\\d+)$') AS INTEGER)),0)
        FROM checksheets c
        JOIN departments d ON c.department_id = d.id
        WHERE d.department_id = :departmentId
        """,
        nativeQuery = true
    )
    long countChksInDepartment(Long departmentId);

    List<Checksheet> findByDepartment_IdIn(List<Long> departmentIds);

    @Query(value = "SELECT COUNT(c) FROM checksheets c WHERE :userId = ANY(c.waiting_user_ids)", nativeQuery = true)
    long getWaitingCount(Long userId);

    @Query(value = "SELECT * FROM checksheets c WHERE :day = ANY(c.npd_day) AND status = 'APPROVED'", nativeQuery = true)
    List<Checksheet> findByNpdDayContains(@Param("day") String day);

    @Query("""
        SELECT c
        FROM Checksheet c
        WHERE c.status = com.checkSheet.constant.ChecksheetStatusType.APPROVED
          AND c.deletedAt IS NULL
          AND (c.expiryDate IS NULL OR c.expiryDate >= :currentDate)
        ORDER BY c.name ASC
    """)
    List<Checksheet> findApprovedAndNotExpired(@Param("currentDate") Date currentDate);
}
