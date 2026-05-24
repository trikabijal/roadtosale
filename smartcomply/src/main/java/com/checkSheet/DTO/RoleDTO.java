package com.checkSheet.DTO;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;

import java.util.Date;
import java.util.List;

@Getter
@Setter
@Builder
@Data
@AllArgsConstructor
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RoleDTO {
    private Long id;
    private String roleCode;
    private String name;
    private Long parentRoleId;
    
    // For permission assignment
    private List<Long> permissionIds;
    
    // For response - list of associated permissions
    private List<PermissionDTO> permissions;
    
    // Flags for UI to control edit/delete
    private Boolean isEditable;
    private Boolean isDeletable;
    
    // Pagination request fields
    private Integer currentPage;
    private Integer perPageRecord;
    private String search;
    private String dropdownKey;

    private Date createdAt;
    private Date updatedAt;

    public void setRoleCode(String roleCode) {
        this.roleCode = roleCode != null ? roleCode.trim() : null;
    }
    
    public void setName(String name) {
        this.name = name != null ? name.trim() : null;
    }

    //getAllRoles
    public RoleDTO(Long id, String name, String roleCode) {
        this.id = id;
        this.name = name;
        this.roleCode = roleCode;
    }
    
    // Constructor for search results with flags
    public RoleDTO(Long id, String name, String roleCode, Boolean isEditable, Boolean isDeletable) {
        this.id = id;
        this.name = name;
        this.roleCode = roleCode;
        this.isEditable = isEditable;
        this.isDeletable = isDeletable;
    }
}
