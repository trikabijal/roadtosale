package com.checkSheet.service;

import com.checkSheet.DTO.PermissionDTO;
import com.checkSheet.DTO.response.ResponseDTO;
import com.checkSheet.entity.Permission;
import com.checkSheet.entity.Role;
import com.checkSheet.exception.CustomException;

import java.util.List;
import java.util.Set;

public interface PermissionService {
    
    /**
     * Get all permissions for a role, including inherited permissions from parent roles
     * @param roleId The role ID
     * @return Set of permission codes
     */
    Set<String> getPermissionsForRole(Long roleId) throws CustomException;
    
    /**
     * Get all permissions for a role by role code
     * @param roleCode The role code (e.g., "SUPER_ADMIN")
     * @return Set of permission codes
     */
    Set<String> getPermissionsForRoleCode(String roleCode) throws CustomException;
    
    /**
     * Get all effective permissions for a user based on all their roles
     * @param userId The user ID
     * @return Set of permission codes
     */
    Set<String> getEffectivePermissionsForUser(Long userId) throws CustomException;
    
    /**
     * Check if a user has a specific permission
     * @param userId The user ID
     * @param permissionCode The permission code to check (e.g., "USER_CREATE")
     * @return true if user has the permission, false otherwise
     */
    boolean hasPermission(Long userId, String permissionCode) throws CustomException;
    
    /**
     * Get all permissions for a role (direct only, without inheritance)
     * @param roleId The role ID
     * @return List of Permission entities
     */
    List<Permission> getDirectPermissionsForRole(Long roleId) throws CustomException;
    
    /**
     * Get all permissions (for admin/management purposes)
     * @return List of all permissions
     */
    List<Permission> getAllPermissions() throws CustomException;
    
    /**
     * Resolve permissions for a role including all parent roles in hierarchy
     * @param role The role entity
     * @return Set of all permission codes (direct + inherited)
     */
    Set<String> resolvePermissionsWithHierarchy(Role role) throws CustomException;
    
    /**
     * Get allowed department IDs for a user based on their UserRoleDepartment assignments
     * @param userId The user ID
     * @return List of department IDs user can access. Empty list means NO access.
     * @throws CustomException if user has no permissions
     */
    List<Long> getAllowedDepartmentIds(Long userId) throws CustomException;
    
    /**
     * Get allowed section IDs for a user based on their UserRoleDepartment assignments
     * @param userId The user ID
     * @return List of section IDs user can access. Empty list means NO access.
     * @throws CustomException if user has no permissions
     */
    List<Long> getAllowedSectionIds(Long userId) throws CustomException;
    
    // ===================== Permission Management CRUD - SUPER_ADMIN only =====================
    
    /**
     * Search permissions with pagination
     * @param permissionDTO DTO containing search criteria and pagination info
     * @return Paginated list of permissions
     */
    ResponseDTO<?> searchPermissions(PermissionDTO permissionDTO) throws CustomException;
    
    /**
     * Get a single permission by ID
     * @param permissionDTO DTO containing permission ID
     * @return Permission details
     */
    ResponseDTO<?> getPermission(PermissionDTO permissionDTO) throws CustomException;
    
    /**
     * Create a new permission
     * @param permissionDTO DTO containing permission data
     * @return Created permission
     */
    ResponseDTO<?> createPermission(PermissionDTO permissionDTO) throws CustomException;
    
    /**
     * Update an existing permission (permission code cannot be changed)
     * @param permissionDTO DTO containing updated permission data
     * @return Updated permission
     */
    ResponseDTO<?> updatePermission(PermissionDTO permissionDTO) throws CustomException;
    
    /**
     * Delete a permission (only if not associated with any role)
     * @param permissionDTO DTO containing permission ID
     * @return Success/failure response
     */
    ResponseDTO<?> deletePermission(PermissionDTO permissionDTO) throws CustomException;
    
    /**
     * Remove a role from a permission (de-associate)
     * @param permissionId The permission ID
     * @param roleId The role ID to remove
     * @return Success/failure response
     */
    ResponseDTO<?> removeRoleFromPermission(Long permissionId, Long roleId) throws CustomException;
    
    /**
     * Add roles to a permission (associate) - skips existing associations
     * @param permissionId The permission ID
     * @param roleIds List of role IDs to add
     * @return Success/failure response
     */
    ResponseDTO<?> addRolesToPermission(Long permissionId, java.util.List<Long> roleIds) throws CustomException;
}

