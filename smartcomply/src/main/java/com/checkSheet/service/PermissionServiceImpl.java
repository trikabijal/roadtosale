package com.checkSheet.service;

import com.checkSheet.DAO.UserRoleDepartmentDAO;
import com.checkSheet.DTO.PermissionDTO;
import com.checkSheet.DTO.RoleDTO;
import com.checkSheet.DTO.response.ResponseDTO;
import com.checkSheet.entity.Permission;
import com.checkSheet.entity.Role;
import com.checkSheet.entity.RolePermission;
import com.checkSheet.entity.User;
import com.checkSheet.entity.UserRoleDepartment;
import com.checkSheet.exception.CustomException;
import com.checkSheet.repository.PermissionRepository;
import com.checkSheet.repository.RolePermissionRepository;
import com.checkSheet.repository.RoleRepository;
import com.checkSheet.repository.UserRoleDepartmentRepository;
import com.checkSheet.constant.SystemRole;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class PermissionServiceImpl implements PermissionService {

    @Autowired
    private RolePermissionRepository rolePermissionRepository;

    @Autowired
    private PermissionRepository permissionRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private UserRoleDepartmentRepository userRoleDepartmentRepository;
    
    @Autowired
    private UserRoleDepartmentDAO userRoleDepartmentDAO;
    
    @Autowired
    private UtilityService utilityService;

    @Override
    public Set<String> getPermissionsForRole(Long roleId) throws CustomException {
        try {
            Optional<Role> roleOpt = roleRepository.findByIdWithParent(roleId);
            if (!roleOpt.isPresent()) {
                throw new CustomException("Role not found with ID: " + roleId, HttpStatus.NOT_FOUND);
            }
            return resolvePermissionsWithHierarchy(roleOpt.get());
        } catch (CustomException ce) {
            throw ce;
        } catch (Exception e) {
            e.printStackTrace();
            throw new CustomException("Error fetching permissions for role: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    public Set<String> getPermissionsForRoleCode(String roleCode) throws CustomException {
        try {
            Optional<Role> roleOpt = roleRepository.findByRoleCodeWithParent(roleCode);
            if (!roleOpt.isPresent()) {
                throw new CustomException("Role not found with code: " + roleCode, HttpStatus.NOT_FOUND);
            }
            return resolvePermissionsWithHierarchy(roleOpt.get());
        } catch (CustomException ce) {
            throw ce;
        } catch (Exception e) {
            e.printStackTrace();
            throw new CustomException("Error fetching permissions for role code: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    public Set<String> getEffectivePermissionsForUser(Long userId) throws CustomException {
        try {
            // Get all active roles for the user
            List<UserRoleDepartment> userRoles = userRoleDepartmentRepository.findByUser_IdAndDeletedByIsNull(userId);
            
            if (userRoles == null || userRoles.isEmpty()) {
                return new HashSet<>();
            }

            Set<String> allPermissions = new HashSet<>();
            
            // Collect permissions from all user roles
            for (UserRoleDepartment urd : userRoles) {
                Role role = urd.getRole();
                if (role != null) {
                    Set<String> rolePermissions = resolvePermissionsWithHierarchy(role);
                    allPermissions.addAll(rolePermissions);
                }
            }
            
            return allPermissions;
        } catch (Exception e) {
            e.printStackTrace();
            throw new CustomException("Error fetching effective permissions for user: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    public boolean hasPermission(Long userId, String permissionCode) throws CustomException {
        try {
            Set<String> userPermissions = getEffectivePermissionsForUser(userId);
            return userPermissions.contains(permissionCode);
        } catch (CustomException ce) {
            throw ce;
        } catch (Exception e) {
            e.printStackTrace();
            throw new CustomException("Error checking permission: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    public List<Permission> getDirectPermissionsForRole(Long roleId) throws CustomException {
        try {
            List<RolePermission> rolePermissions = rolePermissionRepository.findAllByRoleId(roleId);
            return rolePermissions.stream()
                    .map(rp -> rp.getPermission())
                    .filter(p -> p.getDeletedAt() == null)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            e.printStackTrace();
            throw new CustomException("Error fetching direct permissions for role: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    public List<Permission> getAllPermissions() throws CustomException {
        try {
            return permissionRepository.findByDeletedAtIsNull();
        } catch (Exception e) {
            e.printStackTrace();
            throw new CustomException("Error fetching all permissions: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    public Set<String> resolvePermissionsWithHierarchy(Role role) throws CustomException {
        try {
            Set<String> allPermissions = new HashSet<>();
            Set<Long> visitedRoles = new HashSet<>(); // Prevent circular references
            
            // Recursively collect permissions from role and all parent roles
            collectPermissionsRecursive(role, allPermissions, visitedRoles);
            
            return allPermissions;
        } catch (Exception e) {
            e.printStackTrace();
            throw new CustomException("Error resolving permissions with hierarchy: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * Recursively collect permissions from a role and its parent roles
     */
    private void collectPermissionsRecursive(Role role, Set<String> permissions, Set<Long> visitedRoles) {
        if (role == null || visitedRoles.contains(role.getId())) {
            return; // Prevent infinite loops in case of circular references
        }
        
        visitedRoles.add(role.getId());
        
        // Get direct permissions for this role
        List<String> directPermissionCodes = rolePermissionRepository.findPermissionCodesByRoleId(role.getId());
        permissions.addAll(directPermissionCodes);
        
        // Recursively get permissions from parent role
        // Fetch parent role with its own parent to continue the chain
        /*if (role.getParentRole() != null) {
            Optional<Role> parentWithGrandparent = roleRepository.findByIdWithParent(role.getParentRole().getId());
            if (parentWithGrandparent.isPresent()) {
                collectPermissionsRecursive(parentWithGrandparent.get(), permissions, visitedRoles);
            }
        }*/
    }
    
    /**
     * Get allowed department IDs for a user.
     * @return null = global access (SUPER_ADMIN), empty list = no access, populated list = scoped access
     */
    @Override
    public List<Long> getAllowedDepartmentIds(Long userId) throws CustomException {
        try {
            // Get all active role assignments for the user
            List<UserRoleDepartment> userRoles = userRoleDepartmentRepository.findByUser_IdAndDeletedByIsNull(userId);
            
            if (userRoles == null || userRoles.isEmpty()) {
                return new ArrayList<>(); // No assignments = no access
            }
            
            // Check if user has any null department assignment (global access like SUPER_ADMIN)
            boolean hasGlobalAccess = userRoles.stream()
                    .anyMatch(urd -> urd.getDepartmentId() == null);
            
            if (hasGlobalAccess) {
                return null; // null signals global access (no filtering needed)
            }
            
            // Collect all assigned department IDs and their parent departments
            Set<Long> departmentIds = new HashSet<>();
            for (UserRoleDepartment urd : userRoles) {
                if (urd.getDepartmentId() != null) {
                    // Add the directly assigned department
                    Long deptId = urd.getDepartmentId().getId();
                    departmentIds.add(deptId);
                    
                    // If it's a section (has parent department), also add parent department ID
                    if (urd.getDepartmentId().getDepartmentId() != null) {
                        departmentIds.add(urd.getDepartmentId().getDepartmentId().getId());
                    }
                }
            }
            
            return new ArrayList<>(departmentIds);
        } catch (Exception e) {
            e.printStackTrace();
            throw new CustomException("Error fetching allowed department IDs: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
    
    /**
     * Get allowed section IDs for a user.
     * @return null = global access (SUPER_ADMIN), empty list = no access, populated list = scoped access
     */
    @Override
    public List<Long> getAllowedSectionIds(Long userId) throws CustomException {
        try {
            // Get all active role assignments for the user
            List<UserRoleDepartment> userRoles = userRoleDepartmentRepository.findByUser_IdAndDeletedByIsNull(userId);
            
            if (userRoles == null || userRoles.isEmpty()) {
                return new ArrayList<>(); // No assignments = no access
            }
            
            // Check if user has any null department assignment (global access like SUPER_ADMIN)
            boolean hasGlobalAccess = userRoles.stream()
                    .anyMatch(urd -> urd.getDepartmentId() == null);
            
            if (hasGlobalAccess) {
                return null; // null signals global access (no filtering needed)
            }
            
            // Collect all section IDs (departments with parent departments)
            Set<Long> sectionIds = new HashSet<>();
            for (UserRoleDepartment urd : userRoles) {
                if (urd.getDepartmentId() != null) {
                    // Only add if it's a section (has parent department)
                    if (urd.getDepartmentId().getDepartmentId() != null) {
                        sectionIds.add(urd.getDepartmentId().getId());
                    }
                }
            }
            
            return new ArrayList<>(sectionIds);
        } catch (Exception e) {
            e.printStackTrace();
            throw new CustomException("Error fetching allowed section IDs: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
    
    // ===================== Permission Management CRUD - SUPER_ADMIN only =====================
    
    /**
     * Validate that current user is SUPER_ADMIN
     */
    private User validateSuperAdmin() throws CustomException {
        Optional<User> currentUser = utilityService.getCurrentLoggedInUser();
        if (!currentUser.isPresent()) {
            throw new CustomException("User not authenticated", HttpStatus.UNAUTHORIZED);
        }
        
        List<String> userRoles = userRoleDepartmentDAO.getUserByEmailAndUserId(currentUser.get().getId());
        if (userRoles == null || !userRoles.contains(SystemRole.SUPER_ADMIN)) {
            throw new CustomException("Only SUPER_ADMIN can perform this operation", HttpStatus.FORBIDDEN);
        }
        
        return currentUser.get();
    }
    
    @Override
    public ResponseDTO<?> searchPermissions(PermissionDTO permissionDTO) throws CustomException {
        try {
            User currentUser = validateSuperAdmin();
            
            int page = permissionDTO.getCurrentPage() != null ? permissionDTO.getCurrentPage() : 0;
            int size = permissionDTO.getPerPageRecord() != null ? permissionDTO.getPerPageRecord() : 10;
            String search = permissionDTO.getSearch() != null ? permissionDTO.getSearch() : "";
            
            Pageable pageable = PageRequest.of(page, size, Sort.by("name").ascending());
            Page<Permission> permissionsPage = permissionRepository.searchPermissions(search, pageable);
            
            List<PermissionDTO> permissionDTOs = permissionsPage.getContent().stream().map(permission -> {
                PermissionDTO dto = new PermissionDTO();
                dto.setId(permission.getId());
                dto.setName(permission.getName());
                dto.setPermissionCode(permission.getPermissionCode());
                dto.setDescription(permission.getDescription());
                dto.setCreatedAt(permission.getCreatedAt());
                dto.setUpdatedAt(permission.getUpdatedAt());
                
                // Get count of roles using this permission
                Long roleCount = rolePermissionRepository.countRolesUsingPermission(permission.getId());
                dto.setRoleCount(roleCount != null ? roleCount.intValue() : 0);
                
                // Permission is always editable (except permission code in edit mode - handled in frontend)
                dto.setIsEditable(true);
                // Permission is deletable only if no roles are using it
                dto.setIsDeletable(roleCount == null || roleCount == 0);
                
                return dto;
            }).collect(Collectors.toList());
            
            return new ResponseDTO<>(
                "Permissions fetched successfully",
                permissionDTOs,
                permissionsPage.getTotalElements(),
                permissionsPage.getTotalPages(),
                page,
                size
            );
        } catch (CustomException ce) {
            throw ce;
        } catch (Exception e) {
            e.printStackTrace();
            throw new CustomException("Error fetching permissions: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
    
    @Override
    public ResponseDTO<?> getPermission(PermissionDTO permissionDTO) throws CustomException {
        try {
            User currentUser = validateSuperAdmin();
            
            if (permissionDTO.getId() == null) {
                throw new CustomException("Permission ID is required", HttpStatus.BAD_REQUEST);
            }
            
            Optional<Permission> permissionOpt = permissionRepository.findByIdAndDeletedAtIsNull(permissionDTO.getId());
            if (!permissionOpt.isPresent()) {
                throw new CustomException("Permission not found", HttpStatus.NOT_FOUND);
            }
            
            Permission permission = permissionOpt.get();
            PermissionDTO resultDTO = new PermissionDTO();
            resultDTO.setId(permission.getId());
            resultDTO.setName(permission.getName());
            resultDTO.setPermissionCode(permission.getPermissionCode());
            resultDTO.setDescription(permission.getDescription());
            resultDTO.setCreatedAt(permission.getCreatedAt());
            resultDTO.setUpdatedAt(permission.getUpdatedAt());
            
            // Get roles using this permission
            List<RolePermission> rolePermissions = rolePermissionRepository.findByPermissionId(permission.getId());
            List<RoleDTO> rolesUsingPermission = rolePermissions.stream()
                .filter(rp -> rp.getRole().getDeletedAt() == null)  // Only active roles
                .map(rp -> new RoleDTO(rp.getRole().getId(), rp.getRole().getName(), rp.getRole().getRoleCode()))
                .collect(Collectors.toList());
            
            resultDTO.setRoleCount(rolesUsingPermission.size());
            resultDTO.setRoles(rolesUsingPermission);
            resultDTO.setIsEditable(true);
            resultDTO.setIsDeletable(rolesUsingPermission.isEmpty());
            
            return new ResponseDTO<>(true, "Permission fetched successfully", resultDTO);
        } catch (CustomException ce) {
            throw ce;
        } catch (Exception e) {
            e.printStackTrace();
            throw new CustomException("Error fetching permission: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
    
    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseDTO<?> createPermission(PermissionDTO permissionDTO) throws CustomException {
        try {
            User currentUser = validateSuperAdmin();
            
            // Validate required fields
            if (permissionDTO.getName() == null || permissionDTO.getName().trim().isEmpty()) {
                throw new CustomException("Permission name is required", HttpStatus.BAD_REQUEST);
            }
            if (permissionDTO.getPermissionCode() == null || permissionDTO.getPermissionCode().trim().isEmpty()) {
                throw new CustomException("Permission code is required", HttpStatus.BAD_REQUEST);
            }
            
            // Normalize permission code to uppercase
            String permissionCode = permissionDTO.getPermissionCode().trim().toUpperCase().replaceAll("\\s+", "_");
            
            // Validate permission code format
            if (!permissionCode.matches("^[A-Z0-9_]+$")) {
                throw new CustomException("Permission code must contain only uppercase letters, numbers, and underscores", HttpStatus.BAD_REQUEST);
            }
            
            // Check for duplicate permission code
            Optional<Permission> existingPermission = permissionRepository.findByPermissionCodeAndDeletedAtIsNullExcludingId(permissionCode, null);
            if (existingPermission.isPresent()) {
                throw new CustomException("Permission code already exists", HttpStatus.CONFLICT);
            }
            
            // Create new permission
            Permission permission = new Permission();
            permission.setName(permissionDTO.getName().trim());
            permission.setPermissionCode(permissionCode);
            permission.setDescription(permissionDTO.getDescription() != null ? permissionDTO.getDescription().trim() : null);
            permission.setCreatedBy(currentUser);
            permission.setCreatedAt(new Date());
            
            permission = permissionRepository.save(permission);
            
            // Automatically assign new permission to SUPER_ADMIN role
            Long superAdminRoleId = null;
            Optional<Role> superAdminRole = roleRepository.findByRoleCode(SystemRole.SUPER_ADMIN);
            if (superAdminRole.isPresent()) {
                superAdminRoleId = superAdminRole.get().getId();
                RolePermission rolePermission = new RolePermission();
                rolePermission.setRole(superAdminRole.get());
                rolePermission.setPermission(permission);
                rolePermission.setCreatedBy(currentUser);
                rolePermission.setCreatedAt(new Date());
                rolePermissionRepository.save(rolePermission);
            }
            
            // Assign permission to additionally selected roles (if any)
            if (permissionDTO.getRoleIds() != null && !permissionDTO.getRoleIds().isEmpty()) {
                for (Long roleId : permissionDTO.getRoleIds()) {
                    // Skip SUPER_ADMIN as it's already assigned above
                    if (superAdminRoleId != null && roleId.equals(superAdminRoleId)) {
                        continue;
                    }
                    Optional<Role> roleOpt = roleRepository.findById(roleId);
                    if (roleOpt.isPresent()) {
                        RolePermission rp = new RolePermission();
                        rp.setRole(roleOpt.get());
                        rp.setPermission(permission);
                        rp.setCreatedBy(currentUser);
                        rp.setCreatedAt(new Date());
                        rolePermissionRepository.save(rp);
                    }
                }
            }
            
            PermissionDTO resultDTO = new PermissionDTO(permission.getId(), permission.getPermissionCode(), permission.getName(), permission.getDescription());
            return new ResponseDTO<>(true, "Permission created successfully", resultDTO);
        } catch (CustomException ce) {
            throw ce;
        } catch (Exception e) {
            e.printStackTrace();
            throw new CustomException("Error creating permission: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
    
    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseDTO<?> updatePermission(PermissionDTO permissionDTO) throws CustomException {
        try {
            User currentUser = validateSuperAdmin();
            
            // Validate permission ID
            if (permissionDTO.getId() == null) {
                throw new CustomException("Permission ID is required", HttpStatus.BAD_REQUEST);
            }
            
            // Find existing permission
            Optional<Permission> permissionOpt = permissionRepository.findByIdAndDeletedAtIsNull(permissionDTO.getId());
            if (!permissionOpt.isPresent()) {
                throw new CustomException("Permission not found", HttpStatus.NOT_FOUND);
            }
            
            Permission permission = permissionOpt.get();
            
            // NOTE: Permission code CANNOT be changed - ignore any permissionCode in DTO
            // This is enforced here on backend; frontend should also disable the field
            
            // Validate and update name
            if (permissionDTO.getName() != null && !permissionDTO.getName().trim().isEmpty()) {
                permission.setName(permissionDTO.getName().trim());
            }
            
            // Update description (can be null)
            if (permissionDTO.getDescription() != null) {
                permission.setDescription(permissionDTO.getDescription().trim());
            }
            
            permission.setUpdatedBy(currentUser);
            permission.setUpdatedAt(new Date());
            permission = permissionRepository.save(permission);
            
            PermissionDTO resultDTO = new PermissionDTO(permission.getId(), permission.getPermissionCode(), permission.getName(), permission.getDescription());
            return new ResponseDTO<>(true, "Permission updated successfully", resultDTO);
        } catch (CustomException ce) {
            throw ce;
        } catch (Exception e) {
            e.printStackTrace();
            throw new CustomException("Error updating permission: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
    
    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseDTO<?> deletePermission(PermissionDTO permissionDTO) throws CustomException {
        try {
            User currentUser = validateSuperAdmin();
            
            // Validate permission ID
            if (permissionDTO.getId() == null) {
                throw new CustomException("Permission ID is required", HttpStatus.BAD_REQUEST);
            }
            
            // Find existing permission
            Optional<Permission> permissionOpt = permissionRepository.findByIdAndDeletedAtIsNull(permissionDTO.getId());
            if (!permissionOpt.isPresent()) {
                throw new CustomException("Permission not found", HttpStatus.NOT_FOUND);
            }
            
            Permission permission = permissionOpt.get();
            
            // Check if permission is associated with any role
            Long roleCount = rolePermissionRepository.countRolesUsingPermission(permissionDTO.getId());
            if (roleCount != null && roleCount > 0) {
                throw new CustomException(
                    "Cannot delete permission. " + roleCount + " role(s) are using this permission. Please remove it from all roles first.",
                    HttpStatus.CONFLICT
                );
            }
            
            // Soft delete
            permission.setDeletedBy(currentUser);
            permission.setDeletedAt(new Date());
            permissionRepository.save(permission);
            
            return new ResponseDTO<>(true, "Permission deleted successfully");
        } catch (CustomException ce) {
            throw ce;
        } catch (Exception e) {
            e.printStackTrace();
            throw new CustomException("Error deleting permission: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
    
    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseDTO<?> removeRoleFromPermission(Long permissionId, Long roleId) throws CustomException {
        try {
            User currentUser = validateSuperAdmin();
            
            // Validate IDs
            if (permissionId == null) {
                throw new CustomException("Permission ID is required", HttpStatus.BAD_REQUEST);
            }
            if (roleId == null) {
                throw new CustomException("Role ID is required", HttpStatus.BAD_REQUEST);
            }
            
            // Find existing permission
            Optional<Permission> permissionOpt = permissionRepository.findByIdAndDeletedAtIsNull(permissionId);
            if (!permissionOpt.isPresent()) {
                throw new CustomException("Permission not found", HttpStatus.NOT_FOUND);
            }
            
            // Find the role
            Optional<Role> roleOpt = roleRepository.findById(roleId);
            if (!roleOpt.isPresent()) {
                throw new CustomException("Role not found", HttpStatus.NOT_FOUND);
            }
            
            Role role = roleOpt.get();
            
            // Prevent removing SUPER_ADMIN role from any permission
            if (SystemRole.SUPER_ADMIN.equals(role.getRoleCode())) {
                throw new CustomException("Cannot remove SUPER_ADMIN role from permission", HttpStatus.FORBIDDEN);
            }
            
            // Find and delete the role-permission association
            Optional<RolePermission> rpOpt = rolePermissionRepository.findByRoleIdAndPermissionId(roleId, permissionId);
            if (!rpOpt.isPresent()) {
                throw new CustomException("Role is not associated with this permission", HttpStatus.NOT_FOUND);
            }
            
            rolePermissionRepository.delete(rpOpt.get());
            
            return new ResponseDTO<>(true, "Role \"" + role.getName() + "\" removed from permission successfully");
        } catch (CustomException ce) {
            throw ce;
        } catch (Exception e) {
            e.printStackTrace();
            throw new CustomException("Error removing role from permission: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
    
    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseDTO<?> addRolesToPermission(Long permissionId, java.util.List<Long> roleIds) throws CustomException {
        try {
            User currentUser = validateSuperAdmin();
            
            if (permissionId == null) {
                throw new CustomException("Permission ID is required", HttpStatus.BAD_REQUEST);
            }
            if (roleIds == null || roleIds.isEmpty()) {
                return new ResponseDTO<>(true, "No roles selected to add");
            }
            
            Optional<Permission> permissionOpt = permissionRepository.findByIdAndDeletedAtIsNull(permissionId);
            if (!permissionOpt.isPresent()) {
                throw new CustomException("Permission not found", HttpStatus.NOT_FOUND);
            }
            
            Permission permission = permissionOpt.get();
            int added = 0;
            
            for (Long roleId : roleIds) {
                if (roleId == null) continue;
                
                Optional<Role> roleOpt = roleRepository.findById(roleId);
                if (!roleOpt.isPresent()) continue;
                
                Role role = roleOpt.get();
                
                // Skip SUPER_ADMIN role
                if (SystemRole.SUPER_ADMIN.equals(role.getRoleCode())) {
                    continue;
                }
                
                // Check if association already exists
                Optional<RolePermission> existing = rolePermissionRepository.findByRoleIdAndPermissionId(role.getId(), permission.getId());
                if (existing.isPresent()) continue;
                
                // Create new association
                RolePermission rp = new RolePermission();
                rp.setRole(role);
                rp.setPermission(permission);
                rp.setCreatedBy(currentUser);
                rp.setCreatedAt(new Date());
                rolePermissionRepository.save(rp);
                added++;
            }
            
            return new ResponseDTO<>(true, added + " role(s) associated with permission");
        } catch (CustomException ce) {
            throw ce;
        } catch (Exception e) {
            e.printStackTrace();
            throw new CustomException("Error adding roles to permission: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
}

