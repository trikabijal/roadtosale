package com.checkSheet.repository;

import com.checkSheet.entity.Audit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AuditRepository extends JpaRepository<Audit, Long> {
    List<Audit> findByDeletedAtIsNullOrderByCreatedAtDesc();
    Optional<Audit> findByNameAndDeletedAtIsNull(String name);
    List<Audit> findByChecksheetIdAndDeletedAtIsNull(Long checksheetId);
    Optional<Audit> findByIdAndDeletedAtIsNull(Long id);
}
