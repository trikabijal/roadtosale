package com.checkSheet.service;

import com.checkSheet.DAO.RoleDAO;
import com.checkSheet.DAO.UserRoleDepartmentDAO;
import com.checkSheet.DTO.DepartmentDTO;
import com.checkSheet.DTO.PermissionDTO;
import com.checkSheet.DTO.RoleDTO;
import com.checkSheet.DTO.response.ResponseDTO;
import com.checkSheet.constant.UserDropdownPermissionGroups;
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
public class RoleServiceImpl implements RoleService {

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private UserRoleDepartmentRepository userRoleDepartmentRepository;

    @Autowired
    private UserRoleDepartmentDAO userRoleDepartmentDAO;

    @Autowired
    private RoleDAO roleDAO;

    @Autowired
    private UtilityService utilityService;
    
    @Autowired
    private PermissionRepository permissionRepository;
    
    @Autowired
    private RolePermissionRepository rolePermissionRepository;

    @Override
    public ResponseDTO<?> getAllRoles() throws CustomException {
        try {
            // Get current logged-in user
            Optional<User> currentUser = utilityService.getCurrentLoggedInUser();
            if (!currentUser.isPresent()) {
                throw new CustomException("User not authenticated", HttpStatus.UNAUTHORIZED);
            }

            // Get user's roles
            List<String> userRoles = userRoleDepartmentDAO.getUserByEmailAndUserId(currentUser.get().getId());
            if (userRoles == null || userRoles.isEmpty()) {
                throw new CustomException("User has no roles assigned", HttpStatus.FORBIDDEN);
            }

            // Determine the highest role in hierarchy for filtering
            String highestRoleCode = getHighestRoleInHierarchy(userRoles);
            
            List<RoleDTO> roles;
            
            if (SystemRole.SUPER_ADMIN.equals(highestRoleCode)) {
                // Super Admin can see all roles
                roles = roleDAO.getRoles();
            } else {
                // For other roles, get child roles based on parent_role_id from database
                Optional<Role> userHighestRole = roleRepository.findByRoleCode(highestRoleCode);
                if (userHighestRole.isPresent()) {
                    // Get all roles that are children of this role (recursively using parent_role_id)
                    roles = roleDAO.getRolesByParentRoleIdRecursive(userHighestRole.get().getId());
                } else {
                    roles = new ArrayList<>();
                }
            }
            
            return new ResponseDTO<>("Roles fetched successfully", roles);
        } catch (CustomException ce) {
            throw ce;
        } catch (Exception e) {
            e.printStackTrace();
            throw new CustomException("Error fetching roles: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * Get the highest role in hierarchy from user's roles.
     * Uses parent_role_id from database to determine hierarchy.
     * SUPER_ADMIN is always the highest if present.
     */
    private String getHighestRoleInHierarchy(List<String> userRoleCodes) {
        // SUPER_ADMIN is always highest
        if (userRoleCodes.contains(SystemRole.SUPER_ADMIN)) {
            return SystemRole.SUPER_ADMIN;
        }
        
        // For other roles, find the one with the highest position in hierarchy
        // (closest to root - parent_role_id is null or has fewer ancestors)
        String highestRole = null;
        int minDepth = Integer.MAX_VALUE;
        
        for (String roleCode : userRoleCodes) {
            Optional<Role> roleOpt = roleRepository.findByRoleCode(roleCode);
            if (roleOpt.isPresent()) {
                int depth = getRoleDepth(roleOpt.get());
                if (depth < minDepth) {
                    minDepth = depth;
                    highestRole = roleCode;
                }
            }
        }
        
        return highestRole != null ? highestRole : (userRoleCodes.isEmpty() ? null : userRoleCodes.get(0));
    }
    
    /**
     * Calculate the depth of a role in the hierarchy tree.
     * Root roles (no parent) have depth 0.
     */
    private int getRoleDepth(Role role) {
        int depth = 0;
        Role current = role;
        while (current.getParentRole() != null) {
            depth++;
            current = current.getParentRole();
            // Prevent infinite loop in case of circular reference
            if (depth > 100) break;
        }
        return depth;
    }

    @Override
    public ResponseDTO<?> getRolesByDepartments(DepartmentDTO departmentDTO) throws CustomException {
        try {
            List<UserRoleDepartment> userRoleByUserIdDepartment = userRoleDepartmentRepository.findByDepartmentId_IdIn(departmentDTO.getDepartmentIds());
            List<Role> roles = userRoleByUserIdDepartment.stream().map(UserRoleDepartment::getRole).distinct().filter(role -> !role.getRoleCode().equals("SUBDEPT_ADMIN")).collect(Collectors.toList());
            List<RoleDTO> roleDTOs = new ArrayList<>();
            for(Role role:roles){
                RoleDTO roleDTO = new RoleDTO();
                roleDTO.setId(role.getId());
                roleDTO.setName(role.getName());
                roleDTO.setRoleCode(role.getRoleCode());
                roleDTOs.add(roleDTO);
            }
            return new ResponseDTO<>("Roles fetched sucessfully", roleDTOs);
        } catch (Exception e) {
            e.printStackTrace();
            throw new CustomException(e.getMessage(), HttpStatus.UNAUTHORIZED);
        }
    }

    @Override
    public ResponseDTO<?> getRolesByUserDropdownPermissions(RoleDTO roleDTO) throws CustomException {
        try {
            if (Objects.isNull(roleDTO) || Objects.isNull(roleDTO.getDropdownKey()) || roleDTO.getDropdownKey().trim().isEmpty()) {
                throw new CustomException("Please provide dropdownKey", HttpStatus.BAD_REQUEST);
            }

            List<String> requiredPermissions = getPermissionGroupByDropdownKey(roleDTO.getDropdownKey().trim());
            if (requiredPermissions.isEmpty()) {
                throw new CustomException("Invalid dropdownKey", HttpStatus.BAD_REQUEST);
            }

            List<String> normalizedRequiredPermissions = requiredPermissions.stream()
                    .filter(Objects::nonNull)
                    .map(String::trim)
                    .filter(permission -> !permission.isEmpty())
                    .map(String::toUpperCase)
                    .distinct()
                    .collect(Collectors.toList());

            System.out.println("getRolesByUserDropdownPermissions dropdownKey => " + roleDTO.getDropdownKey().trim());
            System.out.println("getRolesByUserDropdownPermissions requiredPermissions => " + normalizedRequiredPermissions);

            List<RoleDTO> filteredRoles = roleDAO.getRolesByAllPermissionCodes(normalizedRequiredPermissions);

            return new ResponseDTO<>(true, "Roles fetched successfully", filteredRoles);
        } catch (CustomException ce) {
            throw ce;
        } catch (Exception e) {
            e.printStackTrace();
            throw new CustomException("Error fetching roles by permissions", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private List<String> getPermissionGroupByDropdownKey(String dropdownKey) {
        switch (dropdownKey) {
            case "preparer":
                return UserDropdownPermissionGroups.CHECKSHEET_PREPARER;
            case "validator":
                return UserDropdownPermissionGroups.CHECKSHEET_VALIDATOR;
            case "approver":
                return UserDropdownPermissionGroups.CHECKSHEET_APPROVER;
            case "dataValidator":
                return UserDropdownPermissionGroups.DATA_VALIDATOR;
            case "dataApprover":
                return UserDropdownPermissionGroups.DATA_APPROVER;
            case "operator":
                return UserDropdownPermissionGroups.OPERATOR;
            default:
                return Collections.emptyList();
        }
    }

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
    
    /**
     * Check if role is a system role (non-editable/non-deletable)
     */
    private boolean isSystemRole(String roleCode) {
        return SystemRole.SUPER_ADMIN.equals(roleCode);
    }
    
    @Override
    public ResponseDTO<?> searchRoles(RoleDTO roleDTO) throws CustomException {
        try {
            User currentUser = validateSuperAdmin();
            
            int page = roleDTO.getCurrentPage() != null ? roleDTO.getCurrentPage() : 0;
            int size = roleDTO.getPerPageRecord() != null ? roleDTO.getPerPageRecord() : 10;
            String search = roleDTO.getSearch() != null ? roleDTO.getSearch() : "";
            
            Pageable pageable = PageRequest.of(page, size, Sort.by("name").ascending());
            Page<Role> rolesPage = roleRepository.searchRoles(search, pageable);
            
            List<RoleDTO> roleDTOs = rolesPage.getContent().stream().map(role -> {
                RoleDTO dto = new RoleDTO();
                dto.setId(role.getId());
                dto.setName(role.getName());
                dto.setRoleCode(role.getRoleCode());
                dto.setCreatedAt(role.getCreatedAt());
                dto.setUpdatedAt(role.getUpdatedAt());
                
                // Set flags - SUPER_ADMIN role cannot be edited or deleted
                boolean isSuperAdminRole = isSystemRole(role.getRoleCode());
                dto.setIsEditable(!isSuperAdminRole);
                dto.setIsDeletable(!isSuperAdminRole);
                
                // Get permission count or list
                List<RolePermission> rolePermissions = rolePermissionRepository.findByRoleId(role.getId());
                List<PermissionDTO> permissionDTOs = rolePermissions.stream()
                    .map(rp -> new PermissionDTO(
                        rp.getPermission().getId(),
                        rp.getPermission().getPermissionCode(),
                        rp.getPermission().getName()
                    ))
                    .collect(Collectors.toList());
                dto.setPermissions(permissionDTOs);
                
                return dto;
            }).collect(Collectors.toList());
            
            return new ResponseDTO<>(
                "Roles fetched successfully",
                roleDTOs,
                rolesPage.getTotalElements(),
                rolesPage.getTotalPages(),
                page,
                size
            );
        } catch (CustomException ce) {
            throw ce;
        } catch (Exception e) {
            e.printStackTrace();
            throw new CustomException("Error fetching roles: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
    
    @Override
    public ResponseDTO<?> getRole(RoleDTO roleDTO) throws CustomException {
        try {
            User currentUser = validateSuperAdmin();
            
            if (roleDTO.getId() == null) {
                throw new CustomException("Role ID is required", HttpStatus.BAD_REQUEST);
            }
            
            Optional<Role> roleOpt = roleRepository.findByIdAndDeletedAtIsNull(roleDTO.getId());
            if (!roleOpt.isPresent()) {
                throw new CustomException("Role not found", HttpStatus.NOT_FOUND);
            }
            
            Role role = roleOpt.get();
            RoleDTO resultDTO = new RoleDTO();
            resultDTO.setId(role.getId());
            resultDTO.setName(role.getName());
            resultDTO.setRoleCode(role.getRoleCode());
            resultDTO.setCreatedAt(role.getCreatedAt());
            resultDTO.setUpdatedAt(role.getUpdatedAt());
            resultDTO.setParentRoleId(role.getParentRole() != null ? role.getParentRole().getId() : null);
            
            // Set flags
            boolean isSuperAdminRole = isSystemRole(role.getRoleCode());
            resultDTO.setIsEditable(!isSuperAdminRole);
            resultDTO.setIsDeletable(!isSuperAdminRole);
            
            // Get associated permissions
            List<RolePermission> rolePermissions = rolePermissionRepository.findByRoleId(role.getId());
            List<PermissionDTO> permissionDTOs = rolePermissions.stream()
                .map(rp -> new PermissionDTO(
                    rp.getPermission().getId(),
                    rp.getPermission().getPermissionCode(),
                    rp.getPermission().getName(),
                    rp.getPermission().getDescription()
                ))
                .collect(Collectors.toList());
            resultDTO.setPermissions(permissionDTOs);
            resultDTO.setPermissionIds(permissionDTOs.stream().map(PermissionDTO::getId).collect(Collectors.toList()));
            
            return new ResponseDTO<>(true, "Role fetched successfully", resultDTO);
        } catch (CustomException ce) {
            throw ce;
        } catch (Exception e) {
            e.printStackTrace();
            throw new CustomException("Error fetching role: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
    
    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseDTO<?> createRole(RoleDTO roleDTO) throws CustomException {
        try {
            User currentUser = validateSuperAdmin();
            
            // Validate required fields
            if (roleDTO.getName() == null || roleDTO.getName().trim().isEmpty()) {
                throw new CustomException("Role name is required", HttpStatus.BAD_REQUEST);
            }
            if (roleDTO.getRoleCode() == null || roleDTO.getRoleCode().trim().isEmpty()) {
                throw new CustomException("Role code is required", HttpStatus.BAD_REQUEST);
            }
            
            // Normalize role code to uppercase
            String roleCode = roleDTO.getRoleCode().trim().toUpperCase().replaceAll("\\s+", "_");
            
            // Check for duplicate role code
            Optional<Role> existingRole = roleRepository.findByRoleCodeAndDeletedAtIsNullExcludingId(roleCode, null);
            if (existingRole.isPresent()) {
                throw new CustomException("Role code already exists", HttpStatus.CONFLICT);
            }
            
            // Create new role
            Role role = new Role();
            role.setName(roleDTO.getName().trim());
            role.setRoleCode(roleCode);
            role.setCreatedBy(currentUser);
            role.setCreatedAt(new Date());
            
            // Set parent role if provided
            if (roleDTO.getParentRoleId() != null) {
                Optional<Role> parentRole = roleRepository.findByIdAndDeletedAtIsNull(roleDTO.getParentRoleId());
                if (parentRole.isPresent()) {
                    role.setParentRole(parentRole.get());
                }
            }
            
            role = roleRepository.save(role);
            
            // Assign permissions if provided
            if (roleDTO.getPermissionIds() != null && !roleDTO.getPermissionIds().isEmpty()) {
                assignPermissionsToRole(role, roleDTO.getPermissionIds(), currentUser);
            }
            
            RoleDTO resultDTO = new RoleDTO(role.getId(), role.getName(), role.getRoleCode());
            return new ResponseDTO<>(true, "Role created successfully", resultDTO);
        } catch (CustomException ce) {
            throw ce;
        } catch (Exception e) {
            e.printStackTrace();
            throw new CustomException("Error creating role: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
    
    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseDTO<?> updateRole(RoleDTO roleDTO) throws CustomException {
        try {
            User currentUser = validateSuperAdmin();
            
            // Validate role ID
            if (roleDTO.getId() == null) {
                throw new CustomException("Role ID is required", HttpStatus.BAD_REQUEST);
            }
            
            // Find existing role
            Optional<Role> roleOpt = roleRepository.findByIdAndDeletedAtIsNull(roleDTO.getId());
            if (!roleOpt.isPresent()) {
                throw new CustomException("Role not found", HttpStatus.NOT_FOUND);
            }
            
            Role role = roleOpt.get();
            
            // Prevent editing SUPER_ADMIN role
            if (isSystemRole(role.getRoleCode())) {
                throw new CustomException("Cannot modify system role", HttpStatus.FORBIDDEN);
            }
            
            // Self-demotion check: prevent user from modifying their own role assignment
            List<UserRoleDepartment> currentUserRoles = userRoleDepartmentRepository.findByUser_IdAndDeletedByIsNull(currentUser.getId());
            boolean isModifyingOwnRole = currentUserRoles.stream()
                .anyMatch(urd -> urd.getRole().getId().equals(roleDTO.getId()));
            if (isModifyingOwnRole) {
                throw new CustomException("You cannot modify your own role", HttpStatus.FORBIDDEN);
            }
            
            // Validate and update name
            if (roleDTO.getName() != null && !roleDTO.getName().trim().isEmpty()) {
                role.setName(roleDTO.getName().trim());
            }
            
            // Validate and update role code (if provided)
            if (roleDTO.getRoleCode() != null && !roleDTO.getRoleCode().trim().isEmpty()) {
                String newRoleCode = roleDTO.getRoleCode().trim().toUpperCase().replaceAll("\\s+", "_");
                
                // Check for duplicate (excluding current role)
                Optional<Role> existingRole = roleRepository.findByRoleCodeAndDeletedAtIsNullExcludingId(newRoleCode, role.getId());
                if (existingRole.isPresent()) {
                    throw new CustomException("Role code already exists", HttpStatus.CONFLICT);
                }
                role.setRoleCode(newRoleCode);
            }
            
            // Update parent role if provided
            if (roleDTO.getParentRoleId() != null) {
                if (roleDTO.getParentRoleId().equals(role.getId())) {
                    throw new CustomException("Role cannot be its own parent", HttpStatus.BAD_REQUEST);
                }
                Optional<Role> parentRole = roleRepository.findByIdAndDeletedAtIsNull(roleDTO.getParentRoleId());
                if (parentRole.isPresent()) {
                    role.setParentRole(parentRole.get());
                }
            }
            
            role.setUpdatedBy(currentUser);
            role.setUpdatedAt(new Date());
            role = roleRepository.save(role);
            
            // Update permissions if provided
            if (roleDTO.getPermissionIds() != null) {
                // Get existing permission IDs
                List<RolePermission> existingPermissions = rolePermissionRepository.findByRoleId(role.getId());
                Set<Long> existingPermissionIds = existingPermissions.stream()
                    .map(rp -> rp.getPermission().getId())
                    .collect(java.util.stream.Collectors.toSet());
                
                Set<Long> newPermissionIds = new HashSet<>(roleDTO.getPermissionIds());
                
                // Only update if there's a difference
                if (!existingPermissionIds.equals(newPermissionIds)) {
                    // Remove existing permissions
                    rolePermissionRepository.deleteAll(existingPermissions);
                    rolePermissionRepository.flush(); // Ensure deletes are executed before inserts
                    
                    // Assign new permissions
                    if (!roleDTO.getPermissionIds().isEmpty()) {
                        assignPermissionsToRole(role, roleDTO.getPermissionIds(), currentUser);
                    }
                }
            }
            
            RoleDTO resultDTO = new RoleDTO(role.getId(), role.getName(), role.getRoleCode());
            return new ResponseDTO<>(true, "Role updated successfully", resultDTO);
        } catch (CustomException ce) {
            throw ce;
        } catch (Exception e) {
            e.printStackTrace();
            throw new CustomException("Error updating role: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
    
    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseDTO<?> deleteRole(RoleDTO roleDTO) throws CustomException {
        try {
            User currentUser = validateSuperAdmin();
            
            // Validate role ID
            if (roleDTO.getId() == null) {
                throw new CustomException("Role ID is required", HttpStatus.BAD_REQUEST);
            }
            
            // Find existing role
            Optional<Role> roleOpt = roleRepository.findByIdAndDeletedAtIsNull(roleDTO.getId());
            if (!roleOpt.isPresent()) {
                throw new CustomException("Role not found", HttpStatus.NOT_FOUND);
            }
            
            Role role = roleOpt.get();
            
            // Prevent deleting SUPER_ADMIN role
            if (isSystemRole(role.getRoleCode())) {
                throw new CustomException("Cannot delete system role", HttpStatus.FORBIDDEN);
            }
            
            // Self-demotion check: prevent user from deleting their own role
            List<UserRoleDepartment> currentUserRoles = userRoleDepartmentRepository.findByUser_IdAndDeletedByIsNull(currentUser.getId());
            boolean isDeletingOwnRole = currentUserRoles.stream()
                .anyMatch(urd -> urd.getRole().getId().equals(roleDTO.getId()));
            if (isDeletingOwnRole) {
                throw new CustomException("You cannot delete your own role", HttpStatus.FORBIDDEN);
            }
            
            // Check if users are assigned to this role
            Long userCount = userRoleDepartmentRepository.countUsersAssignedToRole(roleDTO.getId());
            if (userCount != null && userCount > 0) {
                throw new CustomException(
                    "Cannot delete role. " + userCount + " user(s) are assigned to this role. Please reassign them first.",
                    HttpStatus.CONFLICT
                );
            }
            
            // Soft delete
            role.setDeletedBy(currentUser);
            role.setDeletedAt(new Date());
            roleRepository.save(role);
            
            return new ResponseDTO<>(true, "Role deleted successfully");
        } catch (CustomException ce) {
            throw ce;
        } catch (Exception e) {
            e.printStackTrace();
            throw new CustomException("Error deleting role: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
    
    @Override
    public ResponseDTO<?> getAllPermissions() throws CustomException {
        try {
            User currentUser = validateSuperAdmin();
            
            List<Permission> permissions = permissionRepository.findByDeletedAtIsNull();
            List<PermissionDTO> permissionDTOs = permissions.stream()
                .map(p -> new PermissionDTO(
                    p.getId(),
                    p.getPermissionCode(),
                    p.getName(),
                    p.getDescription()
                ))
                .sorted(Comparator.comparing(PermissionDTO::getName))
                .collect(Collectors.toList());
            
            return new ResponseDTO<>("Permissions fetched successfully", permissionDTOs);
        } catch (CustomException ce) {
            throw ce;
        } catch (Exception e) {
            e.printStackTrace();
            throw new CustomException("Error fetching permissions: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
    
    @Override
    public ResponseDTO<?> getPermissionsByRole(RoleDTO roleDTO) throws CustomException {
        try {
            User currentUser = validateSuperAdmin();
            
            if (roleDTO.getId() == null) {
                throw new CustomException("Role ID is required", HttpStatus.BAD_REQUEST);
            }
            
            List<RolePermission> rolePermissions = rolePermissionRepository.findByRoleId(roleDTO.getId());
            List<PermissionDTO> permissionDTOs = rolePermissions.stream()
                .map(rp -> new PermissionDTO(
                    rp.getPermission().getId(),
                    rp.getPermission().getPermissionCode(),
                    rp.getPermission().getName(),
                    rp.getPermission().getDescription()
                ))
                .collect(Collectors.toList());
            
            return new ResponseDTO<>("Permissions fetched successfully", permissionDTOs);
        } catch (CustomException ce) {
            throw ce;
        } catch (Exception e) {
            e.printStackTrace();
            throw new CustomException("Error fetching permissions: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
    
    /**
     * Helper method to assign permissions to a role
     */
    private void assignPermissionsToRole(Role role, List<Long> permissionIds, User currentUser) {
        for (Long permissionId : permissionIds) {
            Optional<Permission> permissionOpt = permissionRepository.findById(permissionId);
            if (permissionOpt.isPresent()) {
                RolePermission rolePermission = new RolePermission();
                rolePermission.setRole(role);
                rolePermission.setPermission(permissionOpt.get());
                rolePermission.setCreatedBy(currentUser);
                rolePermission.setCreatedAt(new Date());
                rolePermissionRepository.save(rolePermission);
            }
        }
    }

}
