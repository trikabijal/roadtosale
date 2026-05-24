package com.checkSheet.DTO;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import com.checkSheet.DTO.RoleDTO;

import com.fasterxml.jackson.annotation.JsonInclude;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Builder
@Data
@AllArgsConstructor
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DepartmentDTO {
    private Long id;
    private String name;
    private Date createdAt;
    private Date updatedAt;
    private Long departmentId;
    private Long sectiontId;
    private String username;
    private Long currentPage;
    private Long perPageRecord;
    private Long roleId;
    private Long userId;
    private String firstName;
    private String lastName;
    private String roleName;
    private String roleCode;
    private String search;
    private String departmentName;
    private String sectionName;
    private String email;
    private String mobile;
    private String frequencyOfCheck;
    private Boolean isSubDepartment;
    private Boolean isEditable;
    private Boolean isDeletable;
    private List<String> checksheets;
    private Long userRoleDepartmentId;
    private List<Long> departmentIds;
    private List<Long> roleIds;
    private List<Long> checksheetIds;
    private List<RoleDTO> roles;
    private List<DepartmentDTO> departments;
    private List<DepartmentDTO> sections;

    //getAllDepartments
    public DepartmentDTO(Long id, String name, Long departmentId) {
        this.id = id;
        this.name = name;
        this.departmentId = departmentId;
    }

    //searchDepartments
    public DepartmentDTO(Long id, String name, Long userId, String firstName, String lastName, String email, String username,
                         Long roleId, String roleName, String roleCode, Date createdAt, Long userRoleDepartmentId, String deptName) {
        this.id = id;
        this.name = name;
        this.userId = userId;
        this.firstName = firstName;
        this.lastName = lastName;
        this.email = email;
        this.username = username;
        this.roleId = roleId;
        this.roleName = roleName;
        this.roleCode = roleCode;
        this.createdAt = createdAt;
        this.userRoleDepartmentId = userRoleDepartmentId;
        this.departmentName = deptName;
    }

    //searchDepartmentsForDownload
    public DepartmentDTO(Long id, String name, Long userId, String firstName, String lastName, String email, String username,
                         Long roleId, String roleName, String roleCode) {
        super();
        this.id = id;
        this.name = name;
        this.userId = userId;
        this.firstName = firstName;
        this.lastName = lastName;
        this.email = email;
        this.username = username;
        this.roleId = roleId;
        this.roleName = roleName;
        this.roleCode = roleCode;
    }

    //getAllDepartments
    public DepartmentDTO(Long id, String name, Long userId, String firstName, String lastName, String email, String username, Date createdAt,Long departmentId) {
        this.id = id;
        this.name = name;
        this.userId = userId;
        this.firstName = firstName;
        this.lastName = lastName;
        this.email = email;
        this.username = username;
        this.createdAt = createdAt;
        this.departmentId = departmentId;
    }

    public void setName(String name) {
        this.name = name != null ? name.trim() : null;
    }

    public void setUsername(String username) {
        this.username = username != null ? username.trim() : null;
    }

    public void setFirstName(String firstName) {
        this.firstName = firstName != null ? firstName.trim() : null;
    }

    public void setLastName(String lastName) {
        this.lastName = lastName != null ? lastName.trim() : null;
    }

    public void setRoleName(String roleName) {
        this.roleName = roleName != null ? roleName.trim() : null;
    }

    public void setSearch(String search) {
        this.search = search != null ? search.trim() : null;
    }

    public void setEmail(String email) {
        this.email = email != null ? email.trim() : null;
    }

    public void setMobile(String mobile) {
        this.mobile = mobile != null ? mobile.trim() : null;
    }

    private String roleIdsStr;
    private String departmentIdsStr;
    private String sectionIdsStr;
    
    private List<com.checkSheet.DTO.ChecksheetDTO> checksheetDetails;
    
    // Constructor for searchUniqueUsers mapping
    public DepartmentDTO(Long userId, String firstName, String lastName, String email, String username, 
                         String roleIdsStr, String roleName, String roleCode, 
                         String departmentIdsStr, String departmentName, String sectionIdsStr, String sectionName) {
        this.userId = userId;
        this.firstName = firstName;
        this.lastName = lastName;
        this.email = email;
        this.username = username;
        this.roleIdsStr = roleIdsStr;
        this.roleName = roleName;
        this.roleCode = roleCode;
        this.departmentIdsStr = departmentIdsStr;
        this.departmentName = departmentName;
        this.sectionIdsStr = sectionIdsStr;
        this.sectionName = sectionName;
    }

}
