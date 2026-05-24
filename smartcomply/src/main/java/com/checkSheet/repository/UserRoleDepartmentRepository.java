package com.checkSheet.repository;

import com.checkSheet.entity.UserRoleDepartment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
@Repository
public interface UserRoleDepartmentRepository extends JpaRepository<UserRoleDepartment, Long> {

    @Override
    Optional<UserRoleDepartment> findById(Long id);

    List<UserRoleDepartment> findByUser_IdAndDeletedByIsNull(Long id);

    List<UserRoleDepartment> findByUser_IdAndDepartmentId_IdAndDeletedByIsNull(Long id, Long departmentId);

    List<UserRoleDepartment> findDistinctByUser_IdAndDeletedByIsNull(Long id);
    Optional<UserRoleDepartment> findByUser_IdAndDeletedByIsNullAndDepartmentIdIsNotNull(Long id);

    @Query("SELECT u FROM UserRoleDepartment u WHERE u.user.id = :userId AND u.role.id = :roleId AND u.departmentId.id = :departmentId AND u.deletedBy IS NULL")
    Optional<UserRoleDepartment> findByUser_IdAndRole_IdAndDepartment_IdAndDeletedByIsNull(
        Long userId, 
        Long roleId, 
        Long departmentId
    );


    @Query("SELECT u FROM UserRoleDepartment u WHERE u.user.id = :userId AND u.role.id = :roleId AND u.departmentId.id = :departmentId")
    Optional<UserRoleDepartment> findByUser_IdAndRole_IdAndDepartment_Id(
            Long userId,
            Long roleId,
            Long departmentId
    );

    Optional<UserRoleDepartment> findByUser_IdAndRole_IdAndDepartmentId_Id(
            Long userId,
            Long roleId,
            Long departmentId
    );

    Optional<UserRoleDepartment> findByUser_IdAndRole_IdAndDepartmentId_IdAndDeletedByIsNull(
            Long userId,
            Long roleId,
            Long departmentId
    );

    Optional<UserRoleDepartment> findByUser_IdAndRole_Id(
            Long userId,
            Long roleId
    );

    @Query("SELECT u FROM UserRoleDepartment u WHERE u.user.id = :userId AND u.role.id = :roleId AND u.deletedBy IS NULL")
    List<UserRoleDepartment> findByUserIdAndRoleId(
            Long userId,
            Long roleId
    );


    @Query("SELECT u FROM UserRoleDepartment u WHERE u.role.id = :roleId AND u.departmentId.id = :departmentId AND u.deletedBy IS NULL")
    Optional<UserRoleDepartment> findByRole_IdAndDepartment_IdAndDeletedByIsNull(
            Long roleId,
            Long departmentId
    );

    List<UserRoleDepartment> findByDepartmentId_IdIn(List<Long> departmentIds);
    
    // Count users assigned to a specific role (for deletion validation)
    @Query("SELECT COUNT(DISTINCT u.user.id) FROM UserRoleDepartment u WHERE u.role.id = :roleId AND u.deletedBy IS NULL")
    Long countUsersAssignedToRole(@Param("roleId") Long roleId);
    
    // Find all active assignments for a specific role
    @Query("SELECT u FROM UserRoleDepartment u WHERE u.role.id = :roleId AND u.deletedBy IS NULL")
    List<UserRoleDepartment> findByRoleIdAndDeletedByIsNull(@Param("roleId") Long roleId);

    @Query("""
            SELECT DISTINCT urd.user
            FROM UserRoleDepartment urd
            WHERE urd.deletedBy IS NULL
              AND urd.role.roleCode = :roleCode
              AND urd.user.deletedAt IS NULL
              AND urd.user.status = 'A'
            ORDER BY urd.user.firstName ASC, urd.user.lastName ASC
            """)
    List<com.checkSheet.entity.User> findDistinctActiveUsersByRoleCode(@Param("roleCode") String roleCode);

    @Query("""
            SELECT COUNT(urd) > 0
            FROM UserRoleDepartment urd
            WHERE urd.user.id = :userId
              AND urd.role.roleCode = :roleCode
              AND urd.deletedBy IS NULL
              AND urd.user.deletedAt IS NULL
              AND urd.user.status = 'A'
            """)
    boolean existsActiveUserWithRoleCode(@Param("userId") Long userId, @Param("roleCode") String roleCode);
}
