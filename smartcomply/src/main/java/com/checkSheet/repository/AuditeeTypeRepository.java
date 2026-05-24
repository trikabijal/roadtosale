package com.checkSheet.repository;

import com.checkSheet.entity.AuditeeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AuditeeTypeRepository extends JpaRepository<AuditeeType, Long> {
    List<AuditeeType> findAllByOrderByLabelAsc();
}
