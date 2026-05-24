package com.checkSheet.service;

import com.checkSheet.DTO.MasterDepartmentDTO;
import com.checkSheet.DTO.response.ResponseDTO;
import com.checkSheet.exception.CustomException;

/**
 * Service interface for Master Department Management (pure CRUD operations).
 * This is separate from DepartmentService which handles Department Admin assignment.
 */
public interface MasterDepartmentService {

    // ===================== Master Department CRUD =====================
    
    /**
     * Search master departments with pagination
     * @param dto DTO containing search criteria and pagination info
     * @return Paginated list of master departments
     */
    ResponseDTO<?> searchMasterDepartments(MasterDepartmentDTO dto) throws CustomException;
    
    /**
     * Get a single master department by ID with its sections
     * @param dto DTO containing master department ID
     * @return Master department details with sections
     */
    ResponseDTO<?> getMasterDepartment(MasterDepartmentDTO dto) throws CustomException;
    
    /**
     * Create a new master department
     * @param dto DTO containing master department name
     * @return Created master department
     */
    ResponseDTO<?> createMasterDepartment(MasterDepartmentDTO dto) throws CustomException;
    
    /**
     * Update an existing master department name
     * @param dto DTO containing master department ID and new name
     * @return Updated master department
     */
    ResponseDTO<?> updateMasterDepartment(MasterDepartmentDTO dto) throws CustomException;
    
    /**
     * Delete a master department (blocked if sections exist)
     * @param dto DTO containing master department ID
     * @return Success message or error
     */
    ResponseDTO<?> deleteMasterDepartment(MasterDepartmentDTO dto) throws CustomException;
    
    // ===================== Section Management within Master Department =====================
    
    /**
     * Create a new section under a master department
     * @param masterDepartmentId The parent master department ID
     * @param sectionDTO DTO containing section name
     * @return Created section
     */
    ResponseDTO<?> createSection(Long masterDepartmentId, MasterDepartmentDTO.MasterDepartmentSectionDTO sectionDTO) throws CustomException;
    
    /**
     * Update an existing section name
     * @param sectionDTO DTO containing section ID and new name
     * @return Updated section
     */
    ResponseDTO<?> updateSection(MasterDepartmentDTO.MasterDepartmentSectionDTO sectionDTO) throws CustomException;
    
    /**
     * Delete a section (blocked if users or checksheets are assigned)
     * @param sectionId The section ID to delete
     * @return Success message or error
     */
    ResponseDTO<?> deleteSection(Long sectionId) throws CustomException;
    
    /**
     * Get all master departments for dropdown (simple list without pagination)
     * @return List of master departments (id and name only)
     */
    ResponseDTO<?> getAllMasterDepartmentsForDropdown() throws CustomException;
}
