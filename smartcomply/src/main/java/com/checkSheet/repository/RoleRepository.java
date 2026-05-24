package com.checkSheet.repository;

import com.checkSheet.entity.Role;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
@Repository
public interface RoleRepository extends JpaRepository<Role, Long> {

    @Override
    Optional<Role> findById(Long id);

    Optional<Role> findByRoleCode(String roleCode);

    Optional<Role> findByName(String name);

    @Query("SELECT r FROM Role r LEFT JOIN FETCH r.parentRole WHERE r.id = :id")
    Optional<Role> findByIdWithParent(@Param("id") Long id);

    @Query("SELECT r FROM Role r LEFT JOIN FETCH r.parentRole WHERE r.roleCode = :roleCode")
    Optional<Role> findByRoleCodeWithParent(@Param("roleCode") String roleCode);
    
    // For role management - exclude soft deleted
    List<Role> findByDeletedAtIsNull();
    
    Optional<Role> findByIdAndDeletedAtIsNull(Long id);
    
    Optional<Role> findByRoleCodeAndDeletedAtIsNull(String roleCode);
    
    Optional<Role> findByNameAndDeletedAtIsNull(String name);
    
    // Check for duplicate role code (excluding current role for updates)
    @Query("SELECT r FROM Role r WHERE r.roleCode = :roleCode AND r.deletedAt IS NULL AND (:excludeId IS NULL OR r.id != :excludeId)")
    Optional<Role> findByRoleCodeAndDeletedAtIsNullExcludingId(@Param("roleCode") String roleCode, @Param("excludeId") Long excludeId);
    
    // Paginated search for role list
    @Query("SELECT r FROM Role r WHERE r.deletedAt IS NULL AND " +
           "(LOWER(r.name) LIKE LOWER(CONCAT('%', :search, '%')) OR " +
           "LOWER(r.roleCode) LIKE LOWER(CONCAT('%', :search, '%')))")
    Page<Role> searchRoles(@Param("search") String search, Pageable pageable);
    
    // Count roles (excluding soft deleted)
    @Query("SELECT COUNT(r) FROM Role r WHERE r.deletedAt IS NULL")
    Long countActiveRoles();
}
