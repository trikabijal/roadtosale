package com.checkSheet.repository;

import com.checkSheet.entity.Department;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for Master Department Management (pure CRUD operations).
 * Master departments are departments where departmentId IS NULL (top-level departments).
 * This is separate from DepartmentRepository which handles Department Admin operations.
 */
@Repository
public interface MasterDepartmentRepository extends JpaRepository<Department, Long> {

    /**
     * Search master departments (top-level only, departmentId IS NULL) with pagination
     */
    @Query("SELECT d FROM Department d WHERE d.departmentId IS NULL AND d.deletedAt IS NULL " +
           "AND (LOWER(d.name) LIKE LOWER(CONCAT('%', :search, '%')))")
    Page<Department> searchMasterDepartments(@Param("search") String search, Pageable pageable);
    
    /**
     * Find a master department by ID (ensure it's a top-level department)
     */
    @Query("SELECT d FROM Department d WHERE d.id = :id AND d.departmentId IS NULL AND d.deletedAt IS NULL")
    Optional<Department> findMasterDepartmentById(@Param("id") Long id);
    
    /**
     * Check for duplicate master department name (for create/update validation)
     * Excludes the department with excludeId (for update operations)
     */
    @Query("SELECT d FROM Department d WHERE LOWER(d.name) = LOWER(:name) AND d.departmentId IS NULL " +
           "AND d.deletedAt IS NULL AND (:excludeId IS NULL OR d.id != :excludeId)")
    Optional<Department> findByNameAndDepartmentIdIsNullExcludingId(
        @Param("name") String name, 
        @Param("excludeId") Long excludeId);
    
    /**
     * Count sections (sub-departments) under a master department
     */
    @Query("SELECT COUNT(d) FROM Department d WHERE d.departmentId.id = :masterDepartmentId AND d.deletedAt IS NULL")
    Long countSectionsByMasterDepartmentId(@Param("masterDepartmentId") Long masterDepartmentId);
    
    /**
     * Get all sections (sub-departments) under a master department
     */
    @Query("SELECT d FROM Department d WHERE d.departmentId.id = :masterDepartmentId AND d.deletedAt IS NULL ORDER BY d.name ASC")
    List<Department> findSectionsByMasterDepartmentId(@Param("masterDepartmentId") Long masterDepartmentId);
    
    /**
     * Find section by ID (ensure it belongs to a master department)
     */
    @Query("SELECT d FROM Department d WHERE d.id = :id AND d.departmentId IS NOT NULL AND d.deletedAt IS NULL")
    Optional<Department> findSectionById(@Param("id") Long id);
    
    /**
     * Check for duplicate section name within a master department
     */
    @Query("SELECT d FROM Department d WHERE LOWER(d.name) = LOWER(:name) AND d.departmentId.id = :masterDepartmentId " +
           "AND d.deletedAt IS NULL AND (:excludeId IS NULL OR d.id != :excludeId)")
    Optional<Department> findSectionByNameAndMasterDepartmentIdExcludingId(
        @Param("name") String name, 
        @Param("masterDepartmentId") Long masterDepartmentId,
        @Param("excludeId") Long excludeId);
    
    /**
     * Check if any UserRoleDepartment references exist for a department/section
     * This is used to determine if deletion should be blocked
     */
    @Query(value = "SELECT COUNT(*) FROM user_role_departments WHERE department_id = :departmentId AND deleted_by IS NULL", nativeQuery = true)
    Long countActiveUserRoleDepartmentsByDepartmentId(@Param("departmentId") Long departmentId);
    
    /**
     * Check if any checksheets reference a department/section
     */
    @Query(value = "SELECT COUNT(*) FROM checksheets WHERE department_id = :departmentId AND deleted_at IS NULL", nativeQuery = true)
    Long countActiveChecksheetsByDepartmentId(@Param("departmentId") Long departmentId);
}
