package com.checkSheet.service;

import com.checkSheet.DTO.DepartmentDTO;
import com.checkSheet.DTO.RoleDTO;
import com.checkSheet.DTO.UserDTO;
import com.checkSheet.DTO.UserDetails;
import com.checkSheet.DTO.response.ResponseDTO;
import com.checkSheet.exception.CustomException;

public interface RoleService {
    ResponseDTO<?> getAllRoles() throws CustomException;

    ResponseDTO<?> getRolesByDepartments(DepartmentDTO departmentDTO) throws CustomException;
    
    ResponseDTO<?> getRolesByUserDropdownPermissions(RoleDTO roleDTO) throws CustomException;
    
    // Role CRUD operations - SUPER_ADMIN only
    ResponseDTO<?> searchRoles(RoleDTO roleDTO) throws CustomException;
    
    ResponseDTO<?> getRole(RoleDTO roleDTO) throws CustomException;
    
    ResponseDTO<?> createRole(RoleDTO roleDTO) throws CustomException;
    
    ResponseDTO<?> updateRole(RoleDTO roleDTO) throws CustomException;
    
    ResponseDTO<?> deleteRole(RoleDTO roleDTO) throws CustomException;
    
    // Permission management
    ResponseDTO<?> getAllPermissions() throws CustomException;
    
    ResponseDTO<?> getPermissionsByRole(RoleDTO roleDTO) throws CustomException;
}
