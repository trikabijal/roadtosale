package com.checkSheet.repository;

import com.checkSheet.entity.AuditeeLocation;
import com.checkSheet.entity.AuditeeLocationType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AuditeeLocationTypeRepository extends JpaRepository<AuditeeLocationType, Long> {

    @Query("""
            SELECT DISTINCT alt.auditeeLocation
            FROM AuditeeLocationType alt
            JOIN ChecksheetAuditeeType cat ON cat.auditeeType.id = alt.auditeeType.id
            WHERE cat.checksheet.id = :checksheetId
              AND alt.status = 'ACTIVE'
              AND alt.deletedAt IS NULL
              AND alt.auditeeLocation.status = 'ACTIVE'
              AND alt.auditeeLocation.deletedAt IS NULL
            """)
    List<AuditeeLocation> findActiveAuditeeLocationsByChecksheetId(@Param("checksheetId") Long checksheetId);

    @Query("""
            SELECT COUNT(DISTINCT alt.auditeeLocation.id)
            FROM AuditeeLocationType alt
            JOIN ChecksheetAuditeeType cat ON cat.auditeeType.id = alt.auditeeType.id
            WHERE cat.checksheet.id = :checksheetId
              AND alt.auditeeLocation.id IN :auditeeLocationIds
              AND alt.status = 'ACTIVE'
              AND alt.deletedAt IS NULL
              AND alt.auditeeLocation.status = 'ACTIVE'
              AND alt.auditeeLocation.deletedAt IS NULL
            """)
    long countDistinctValidAuditeeLocationsForChecksheet(@Param("checksheetId") Long checksheetId,
                                                         @Param("auditeeLocationIds") List<Long> auditeeLocationIds);
}
