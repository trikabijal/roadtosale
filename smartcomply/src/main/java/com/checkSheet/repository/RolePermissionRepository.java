package com.checkSheet.repository;

import com.checkSheet.entity.RolePermission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RolePermissionRepository extends JpaRepository<RolePermission, Long> {

    @Override
    Optional<RolePermission> findById(Long id);

    List<RolePermission> findByRoleId(Long roleId);

    List<RolePermission> findByPermissionId(Long permissionId);

    @Query("SELECT rp FROM RolePermission rp WHERE rp.role.id = :roleId AND rp.permission.id = :permissionId")
    Optional<RolePermission> findByRoleIdAndPermissionId(Long roleId, Long permissionId);

    @Query("SELECT rp FROM RolePermission rp WHERE rp.role.id = :roleId")
    List<RolePermission> findAllByRoleId(Long roleId);

    @Query("SELECT rp.permission.permissionCode FROM RolePermission rp WHERE rp.role.id = :roleId")
    List<String> findPermissionCodesByRoleId(Long roleId);
    
    /**
     * Count how many roles are using a specific permission
     */
    @Query("SELECT COUNT(rp) FROM RolePermission rp WHERE rp.permission.id = :permissionId AND rp.role.deletedAt IS NULL")
    Long countRolesUsingPermission(@Param("permissionId") Long permissionId);
}

