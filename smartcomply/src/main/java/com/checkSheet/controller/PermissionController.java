package com.checkSheet.controller;

import com.checkSheet.DTO.PermissionDTO;
import com.checkSheet.DTO.response.ResponseDTO;
import com.checkSheet.exception.CustomException;
import com.checkSheet.service.PermissionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@CrossOrigin(origins = "*", allowedHeaders = "*")
@RequestMapping("/api/permission")
public class PermissionController {

    @Autowired
    private PermissionService permissionService;

    // ===================== Permission Management Endpoints (SUPER_ADMIN only) =====================
    
    /**
     * Search permissions with pagination - for permission management list
     */
    @PostMapping("/searchPermissions")
    public ResponseEntity<?> searchPermissions(@RequestBody PermissionDTO permissionDTO) {
        try {
            return ResponseEntity.ok(permissionService.searchPermissions(permissionDTO));
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }
    
    /**
     * Get single permission details
     */
    @PostMapping("/getPermission")
    public ResponseEntity<?> getPermission(@RequestBody PermissionDTO permissionDTO) {
        try {
            return ResponseEntity.ok(permissionService.getPermission(permissionDTO));
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }
    
    /**
     * Create a new permission
     */
    @PostMapping("/createPermission")
    public ResponseEntity<?> createPermission(@RequestBody PermissionDTO permissionDTO) {
        try {
            return ResponseEntity.ok(permissionService.createPermission(permissionDTO));
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }
    
    /**
     * Update an existing permission (permission code cannot be changed)
     */
    @PostMapping("/updatePermission")
    public ResponseEntity<?> updatePermission(@RequestBody PermissionDTO permissionDTO) {
        try {
            return ResponseEntity.ok(permissionService.updatePermission(permissionDTO));
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }
    
    /**
     * Delete a permission (only if not associated with any role)
     */
    @PostMapping("/deletePermission")
    public ResponseEntity<?> deletePermission(@RequestBody PermissionDTO permissionDTO) {
        try {
            return ResponseEntity.ok(permissionService.deletePermission(permissionDTO));
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }
    
    /**
     * Remove a role from a permission (de-associate)
     */
    @PostMapping("/removeRoleFromPermission")
    public ResponseEntity<?> removeRoleFromPermission(@RequestBody java.util.Map<String, Long> request) {
        try {
            Long permissionId = request.get("permissionId");
            Long roleId = request.get("roleId");
            return ResponseEntity.ok(permissionService.removeRoleFromPermission(permissionId, roleId));
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }
    
    /**
     * Add roles to a permission (associate)
     */
    @PostMapping("/addRolesToPermission")
    public ResponseEntity<?> addRolesToPermission(@RequestBody PermissionDTO dto) {
        try {
            return ResponseEntity.ok(permissionService.addRolesToPermission(dto.getId(), dto.getRoleIds()));
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }
}
