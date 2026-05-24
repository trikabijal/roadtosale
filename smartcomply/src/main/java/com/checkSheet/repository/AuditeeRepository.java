package com.checkSheet.repository;

import com.checkSheet.entity.Auditee;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AuditeeRepository extends JpaRepository<Auditee, Long> {
    Optional<Auditee> findByIdAndDeletedAtIsNull(Long id);
    List<Auditee> findByDeletedAtIsNullOrderByName();
}
