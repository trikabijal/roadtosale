package com.checkSheet.service;

import com.checkSheet.DTO.DepartmentDTO;
import com.checkSheet.DTO.response.ResponseDTO;
import com.checkSheet.exception.CustomException;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.transaction.annotation.Transactional;

public interface DepartmentService {

    ResponseDTO<?> createOrEditDepartment(DepartmentDTO departmentDTO) throws CustomException;

    @Transactional(rollbackFor = Exception.class)
    ResponseDTO<?> createDepartmentAdmin(DepartmentDTO departmentDTO) throws CustomException;

    @Transactional(rollbackFor = Exception.class)
    ResponseDTO<?> deleteDepartmentAdmin(DepartmentDTO departmentDTO) throws CustomException;

    ResponseDTO<?> editDepartmentAdmin(DepartmentDTO departmentDTO) throws CustomException;

    @Transactional(rollbackFor = Exception.class)
    ResponseDTO<?> createOrEditSectionAndAdmin(DepartmentDTO departmentDTO) throws CustomException;

    ResponseDTO<?> getDepartments(DepartmentDTO departmentDTO) throws CustomException;

    ResponseDTO<?> getSectionHead(DepartmentDTO departmentDTO) throws CustomException;

    ResponseDTO<?> searchDepartments(DepartmentDTO departmentDTO) throws CustomException;

    void downloadDepartments(DepartmentDTO departmentDTO, HttpServletResponse response) throws CustomException;

    ResponseDTO<?> searchSections(DepartmentDTO departmentDTO) throws CustomException;

    void downloadSections(DepartmentDTO departmentDTO, HttpServletResponse response) throws CustomException;

    ResponseDTO<?> searchUsers(DepartmentDTO departmentDTO) throws CustomException;

    void downloadUsers(DepartmentDTO departmentDTO, HttpServletResponse response) throws CustomException;

    ResponseDTO<?> getDepartments() throws CustomException;

    ResponseDTO<?> getUserDepartmentsForList();

    ResponseDTO<?> getUserDepartments();

    ResponseDTO<?> getUserSections(DepartmentDTO departmentDTO);

    ResponseDTO<?> getDeptAdminDepartments();

    ResponseDTO<?> getSections(DepartmentDTO departmentDTO);

    ResponseDTO<?> getUserRoleDetails(Long userId) throws CustomException;

    ResponseDTO<?> getUserRoleDepartment(Long userRoleDepartmentId) throws CustomException;
}
