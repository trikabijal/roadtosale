package com.checkSheet.repository;

import com.checkSheet.entity.Inspection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Date;
import java.util.List;
import java.util.Optional;

/**
 * Post-V1.28: an "intervention assignment" is just an Inspection with
 * kind='INTERVENTION'. All access goes through the inspections table.
 *
 * The AA→IA chain that the old queries traversed (ia.auditAssignment.audit
 * .id, ia.auditAssignment.dealerPrincipalUser.id) collapsed: AA's columns
 * (audit, location, operator, dealer principal, region owner) are now on
 * Inspection directly. So queries that used to join AA become single-table
 * filters.
 */
@Repository
public interface InterventionAssignmentRepository extends JpaRepository<Inspection, Long> {
    Optional<Inspection> findByIdAndDeletedAtIsNull(Long id);

    @Query("""
        SELECT i FROM Inspection i
         WHERE i.intervention.id = :interventionId
           AND i.kind = 'INTERVENTION'
           AND i.deletedAt IS NULL
         ORDER BY i.createdAt DESC
    """)
    List<Inspection> findByInterventionIdAndDeletedAtIsNullOrderByCreatedAtDesc(@Param("interventionId") Long interventionId);

    /** Idempotency guard for plan instantiation: "is there already a plan
     *  (kind=INTERVENTION inspection) for this intervention at the same
     *  location as the given audit-side inspection?" The :auditAssignmentId
     *  parameter is the AUDIT-kind inspection's id (post-collapse same as
     *  the assignment id); the location lookup goes through it. */
    @Query("""
        SELECT i FROM Inspection i
         WHERE i.kind = 'INTERVENTION'
           AND i.intervention.id = :interventionId
           AND i.auditeeLocation.id = (SELECT a.auditeeLocation.id FROM Inspection a WHERE a.id = :auditAssignmentId)
           AND i.deletedAt IS NULL
    """)
    Optional<Inspection> findByInterventionIdAndAuditAssignmentIdAndDeletedAtIsNull(
            @Param("interventionId") Long interventionId, @Param("auditAssignmentId") Long auditAssignmentId);

    /** Was: lookup all IAs on a given AA. Post-collapse the AA id is the
     *  inspection id. Returns the matching kind=INTERVENTION inspection
     *  (one — multi-wave is not supported). */
    @Query("""
        SELECT i FROM Inspection i
         WHERE i.id = :auditAssignmentId AND i.kind = 'INTERVENTION' AND i.deletedAt IS NULL
    """)
    List<Inspection> findByAuditAssignmentIdAndDeletedAtIsNull(@Param("auditAssignmentId") Long auditAssignmentId);

    @Query("""
        SELECT i FROM Inspection i
         WHERE i.deletedAt IS NULL
           AND i.kind = 'INTERVENTION'
           AND i.status IN ('ASSIGNED','IN_PROGRESS')
           AND i.targetDate < :asOf
    """)
    List<Inspection> findOverdue(@Param("asOf") Date asOf);

    /** All P1 INTERVENTION inspections on a given audit. */
    @Query("""
        SELECT i FROM Inspection i
         WHERE i.intervention.audit.id = :auditId
           AND i.kind = 'INTERVENTION'
           AND i.priority = 'P1'
           AND i.deletedAt IS NULL
    """)
    List<Inspection> findP1ForAudit(@Param("auditId") Long auditId);

    /** "My Plans" for a Dealer Principal user. Filter by dealerPrincipalUser
     *  on the inspection itself (column lives on Inspection post-collapse). */
    @Query("""
        SELECT i FROM Inspection i
         WHERE i.dealerPrincipalUser.id = :userId
           AND i.kind = 'INTERVENTION'
           AND i.deletedAt IS NULL
         ORDER BY i.createdAt DESC
    """)
    List<Inspection> findMineByDealerPrincipal(@Param("userId") Long userId);

    /** All plans on a dealer (across all of the dealer's locations). */
    @Query("""
        SELECT i FROM Inspection i
         WHERE i.auditeeLocation.auditee.id = :auditeeId
           AND i.kind = 'INTERVENTION'
           AND i.deletedAt IS NULL
         ORDER BY i.createdAt DESC
    """)
    List<Inspection> findByAuditeeIdAndDeletedAtIsNull(@Param("auditeeId") Long auditeeId);

    /** All plans on a specific physical location. */
    @Query("""
        SELECT i FROM Inspection i
         WHERE i.auditeeLocation.id = :locationId
           AND i.kind = 'INTERVENTION'
           AND i.deletedAt IS NULL
         ORDER BY i.createdAt DESC
    """)
    List<Inspection> findByAuditeeLocationIdAndDeletedAtIsNull(@Param("locationId") Long locationId);
}
