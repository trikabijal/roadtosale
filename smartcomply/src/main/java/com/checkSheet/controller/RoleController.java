package com.checkSheet.controller;

import com.checkSheet.DTO.DepartmentDTO;
import com.checkSheet.DTO.RoleDTO;
import com.checkSheet.DTO.UserDTO;
import com.checkSheet.DTO.response.ResponseDTO;
import com.checkSheet.exception.CustomException;
import com.checkSheet.service.DepartmentService;
import com.checkSheet.service.RoleService;
import com.checkSheet.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@CrossOrigin(origins = "*", allowedHeaders = "*")
@RequestMapping("/api/role")
public class RoleController {

    @Autowired
    private RoleService roleService;

    @GetMapping("/getRoles")
    public ResponseEntity<?> register() {
        try {
            return ResponseEntity.ok(roleService.getAllRoles());
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    @PostMapping("/getRolesByDepartments")
    public ResponseEntity<?> getRolesByDepartments(@RequestBody DepartmentDTO departmentDTO) {
        try {
            return ResponseEntity.ok(roleService.getRolesByDepartments(departmentDTO));
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    @PostMapping("/getRolesByUserDropdownPermissions")
    public ResponseEntity<?> getRolesByUserDropdownPermissions(@RequestBody RoleDTO roleDTO) {
        try {
            return ResponseEntity.ok(roleService.getRolesByUserDropdownPermissions(roleDTO));
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }
    
    // ===================== Role Management Endpoints (SUPER_ADMIN only) =====================
    
    /**
     * Search roles with pagination - for role management list
     */
    @PostMapping("/searchRoles")
    public ResponseEntity<?> searchRoles(@RequestBody RoleDTO roleDTO) {
        try {
            return ResponseEntity.ok(roleService.searchRoles(roleDTO));
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }
    
    /**
     * Get single role details
     */
    @PostMapping("/getRole")
    public ResponseEntity<?> getRole(@RequestBody RoleDTO roleDTO) {
        try {
            return ResponseEntity.ok(roleService.getRole(roleDTO));
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }
    
    /**
     * Create a new role
     */
    @PostMapping("/createRole")
    public ResponseEntity<?> createRole(@RequestBody RoleDTO roleDTO) {
        try {
            return ResponseEntity.ok(roleService.createRole(roleDTO));
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }
    
    /**
     * Update an existing role
     */
    @PostMapping("/updateRole")
    public ResponseEntity<?> updateRole(@RequestBody RoleDTO roleDTO) {
        try {
            return ResponseEntity.ok(roleService.updateRole(roleDTO));
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }
    
    /**
     * Delete a role (soft delete)
     */
    @PostMapping("/deleteRole")
    public ResponseEntity<?> deleteRole(@RequestBody RoleDTO roleDTO) {
        try {
            return ResponseEntity.ok(roleService.deleteRole(roleDTO));
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }
    
    /**
     * Get all permissions - for role permission assignment
     */
    @GetMapping("/getAllPermissions")
    public ResponseEntity<?> getAllPermissions() {
        try {
            return ResponseEntity.ok(roleService.getAllPermissions());
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }
    
    /**
     * Get permissions assigned to a specific role
     */
    @PostMapping("/getPermissionsByRole")
    public ResponseEntity<?> getPermissionsByRole(@RequestBody RoleDTO roleDTO) {
        try {
            return ResponseEntity.ok(roleService.getPermissionsByRole(roleDTO));
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }
}
