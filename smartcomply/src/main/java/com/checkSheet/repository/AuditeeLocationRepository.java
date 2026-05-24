package com.checkSheet.repository;

import com.checkSheet.entity.AuditeeLocation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AuditeeLocationRepository extends JpaRepository<AuditeeLocation, Long> {
    List<AuditeeLocation> findAllByIdInAndStatusAndDeletedAtIsNull(List<Long> ids, String status);
}
