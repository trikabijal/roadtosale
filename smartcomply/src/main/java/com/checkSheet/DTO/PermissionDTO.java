package com.checkSheet.DTO;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;

import java.util.Date;

@Getter
@Setter
@Builder
@Data
@AllArgsConstructor
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PermissionDTO {
    private Long id;
    private String permissionCode;
    private String name;
    private String description;
    
    private Date createdAt;
    private Date updatedAt;
    
    // Pagination fields
    private Integer currentPage;
    private Integer perPageRecord;
    private String search;
    
    // UI flags
    private Boolean isEditable;
    private Boolean isDeletable;
    private Integer roleCount;  // Number of roles using this permission
    private java.util.List<RoleDTO> roles;  // List of roles using this permission
    
    // For create/update operations - role IDs to associate with this permission
    private java.util.List<Long> roleIds;
    
    public void setPermissionCode(String permissionCode) {
        this.permissionCode = permissionCode != null ? permissionCode.trim() : null;
    }
    
    public void setName(String name) {
        this.name = name != null ? name.trim() : null;
    }
    
    // Constructor for getAllPermissions query
    public PermissionDTO(Long id, String permissionCode, String name, String description) {
        this.id = id;
        this.permissionCode = permissionCode;
        this.name = name;
        this.description = description;
    }
    
    // Simple constructor for dropdown lists
    public PermissionDTO(Long id, String permissionCode, String name) {
        this.id = id;
        this.permissionCode = permissionCode;
        this.name = name;
    }
}
