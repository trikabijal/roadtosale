package com.checkSheet.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.*;

@Configuration
public class ApiEndpointConfig {
    
    @Bean
    public Map<String, List<String>> endpointPermissionMap() {
        Map<String, List<String>> permissionMap = new HashMap<>();
        
        // Department Management
        permissionMap.put("/api/department/createDepartment", 
            Arrays.asList("DEPARTMENT_CREATE", "SUBDEPARTMENT_CREATE"));
        permissionMap.put("/api/department/searchDepartments", 
            Arrays.asList("DEPARTMENT_LIST", "SUBDEPARTMENT_LIST"));
        permissionMap.put("/api/department/downloadDepartments", 
            Arrays.asList("DEPARTMENT_DOWNLOAD"));
        permissionMap.put("/api/department/getDepartments", 
            Arrays.asList("DEPARTMENT_LIST", "SUBDEPARTMENT_LIST", "USER_LIST", "CHECKSHEET_FILL_LISTING"));
        permissionMap.put("/api/department/createSections", 
            Arrays.asList("SUBDEPARTMENT_CREATE"));
        permissionMap.put("/api/department/searchSections", 
            Arrays.asList("SUBDEPARTMENT_LIST"));
        permissionMap.put("/api/department/downloadSections", 
            Arrays.asList("SUBDEPARTMENT_DOWNLOAD"));
        
        // User Management
        permissionMap.put("/api/department/searchUsers", 
            Arrays.asList("USER_LIST"));
        permissionMap.put("/api/department/downloadUsers", 
            Arrays.asList("USER_DOWNLOAD"));
        permissionMap.put("/api/user/createOrEditOperator", 
            Arrays.asList("USER_CREATE", "USER_EDIT"));
        permissionMap.put("/api/user/deleteUser", 
            Arrays.asList("USER_DELETE"));
        
        // Role Management - existing
        permissionMap.put("/api/role/getRoles", 
            Arrays.asList("USER_LIST", "USER_CREATE", "USER_EDIT"));
        
        // Role Management - CRUD (SUPER_ADMIN only - these permissions are only assigned to SUPER_ADMIN)
        permissionMap.put("/api/role/searchRoles", 
            Arrays.asList("ROLE_LIST"));
        permissionMap.put("/api/role/getRole", 
            Arrays.asList("ROLE_VIEW", "ROLE_EDIT"));
        permissionMap.put("/api/role/createRole", 
            Arrays.asList("ROLE_CREATE"));
        permissionMap.put("/api/role/updateRole", 
            Arrays.asList("ROLE_UPDATE"));
        permissionMap.put("/api/role/deleteRole", 
            Arrays.asList("ROLE_DELETE"));
        permissionMap.put("/api/role/getAllPermissions", 
            Arrays.asList("ROLE_CREATE", "ROLE_UPDATE", "ROLE_ASSIGN_PERMISSION"));
        permissionMap.put("/api/role/getPermissionsByRole", 
            Arrays.asList("ROLE_VIEW", "ROLE_EDIT", "ROLE_ASSIGN_PERMISSION"));
        
        // Permission Management - CRUD (SUPER_ADMIN only)
        permissionMap.put("/api/permission/searchPermissions", 
            Arrays.asList("PERMISSION_LIST"));
        permissionMap.put("/api/permission/getPermission", 
            Arrays.asList("PERMISSION_VIEW", "PERMISSION_UPDATE"));
        permissionMap.put("/api/permission/createPermission", 
            Arrays.asList("PERMISSION_CREATE"));
        permissionMap.put("/api/permission/updatePermission", 
            Arrays.asList("PERMISSION_UPDATE"));
        permissionMap.put("/api/permission/deletePermission", 
            Arrays.asList("PERMISSION_DELETE"));
        permissionMap.put("/api/permission/removeRoleFromPermission", 
            Arrays.asList("PERMISSION_UPDATE"));
        permissionMap.put("/api/permission/addRolesToPermission", 
            Arrays.asList("PERMISSION_UPDATE"));
        
        // Checksheet Management
        permissionMap.put("/api/checksheet/createChecksheetVersion", 
            Arrays.asList("CHECKSHEET_MANAGEMENT_DETAIL_CREATE", "CHECKSHEET_MANAGEMENT_DETAIL_EDIT", 
                         "CHECKSHEET_MANAGEMENT_TEMPLATE_CREATE", "CHECKSHEET_MANAGEMENT_TEMPLATE_EDIT"));
        permissionMap.put("/api/checksheet/getApprovedNotExpiredChecksheets",
            Arrays.asList("SUBDEPARTMENT_LIST", "DEPARTMENT_LIST", "CHECKSHEET_FILL_LISTING"));
        permissionMap.put("/api/checksheet/getActiveAuditors",
            Arrays.asList("SUBDEPARTMENT_LIST", "DEPARTMENT_LIST", "USER_LIST"));
        permissionMap.put("/api/checksheet/getActiveAuditeeLocationsByChecksheet",
            Arrays.asList("SUBDEPARTMENT_LIST", "DEPARTMENT_LIST", "CHECKSHEET_FILL_LISTING"));
        permissionMap.put("/api/checksheet/createChecksheetAssignment",
            Arrays.asList("SUBDEPARTMENT_LIST", "DEPARTMENT_LIST", "CHECKSHEET_MANAGEMENT_DETAIL_EDIT", "CHECKSHEET_MANAGEMENT_DETAIL_CREATE"));
        permissionMap.put("/api/chksGeneralField/createChksGeneralField", 
            Arrays.asList("CHECKSHEET_MANAGEMENT_TEMPLATE_CREATE", "CHECKSHEET_MANAGEMENT_TEMPLATE_EDIT"));
        permissionMap.put("/api/chksGeneralField/deleteChksGeneralFieldData", 
            Arrays.asList("CHECKSHEET_MANAGEMENT_TEMPLATE_EDIT"));
        permissionMap.put("/api/chksHeader/createChksHeader", 
            Arrays.asList("CHECKSHEET_MANAGEMENT_TEMPLATE_CREATE", "CHECKSHEET_MANAGEMENT_TEMPLATE_EDIT"));
        permissionMap.put("/api/chksHeader/deleteChksHeaderData", 
            Arrays.asList("CHECKSHEET_MANAGEMENT_TEMPLATE_EDIT"));
        permissionMap.put("/api/chksHeaderData/updateDescription", 
            Arrays.asList("CHECKSHEET_MANAGEMENT_TEMPLATE_EDIT"));
        permissionMap.put("/api/chksQuestion/updateDescription", 
            Arrays.asList("CHECKSHEET_MANAGEMENT_TEMPLATE_EDIT"));
        permissionMap.put("/api/chksQuestion/resetChecksheetData", 
            Arrays.asList("CHECKSHEET_MANAGEMENT_TEMPLATE_EDIT"));
        permissionMap.put("/api/chksQuestion/uploadFiles", 
            Arrays.asList("CHECKSHEET_MANAGEMENT_TEMPLATE_CREATE", "CHECKSHEET_MANAGEMENT_TEMPLATE_EDIT"));
        permissionMap.put("/api/chksQuestion/deleteFile", 
            Arrays.asList("CHECKSHEET_MANAGEMENT_TEMPLATE_EDIT"));
        permissionMap.put("/api/chksQuestion/uploadExcel", 
            Arrays.asList("CHECKSHEET_MANAGEMENT_TEMPLATE_CREATE", "CHECKSHEET_MANAGEMENT_TEMPLATE_EDIT"));
        
        return permissionMap;
    }
    
    @Bean
    @Deprecated
    public Map<String, List<String>> endpointRoleMap() {
        // Kept for backward compatibility during migration
        // Will be removed in future release
        return new HashMap<>();
    }
}