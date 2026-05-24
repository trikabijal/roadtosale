package com.checkSheet.repository;

import com.checkSheet.entity.Permission;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PermissionRepository extends JpaRepository<Permission, Long> {

    @Override
    Optional<Permission> findById(Long id);

    Optional<Permission> findByPermissionCode(String permissionCode);

    List<Permission> findByDeletedAtIsNull();

    Optional<Permission> findByPermissionCodeAndDeletedAtIsNull(String permissionCode);
    
    Optional<Permission> findByIdAndDeletedAtIsNull(Long id);
    
    /**
     * Search permissions with pagination for permission management list
     */
    @Query("SELECT p FROM Permission p WHERE p.deletedAt IS NULL " +
           "AND (LOWER(p.name) LIKE LOWER(CONCAT('%', :search, '%')) " +
           "OR LOWER(p.permissionCode) LIKE LOWER(CONCAT('%', :search, '%')) " +
           "OR LOWER(p.description) LIKE LOWER(CONCAT('%', :search, '%')))")
    Page<Permission> searchPermissions(@Param("search") String search, Pageable pageable);
    
    /**
     * Check for duplicate permission code excluding a specific ID (for edit validation)
     */
    @Query("SELECT p FROM Permission p WHERE p.permissionCode = :code AND p.deletedAt IS NULL " +
           "AND (:excludeId IS NULL OR p.id != :excludeId)")
    Optional<Permission> findByPermissionCodeAndDeletedAtIsNullExcludingId(
        @Param("code") String permissionCode, 
        @Param("excludeId") Long excludeId);
}

