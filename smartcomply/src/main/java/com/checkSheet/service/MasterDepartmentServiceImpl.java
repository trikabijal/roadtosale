package com.checkSheet.service;

import com.checkSheet.DTO.MasterDepartmentDTO;
import com.checkSheet.DTO.response.ResponseDTO;
import com.checkSheet.entity.Department;
import com.checkSheet.entity.User;
import com.checkSheet.exception.CustomException;
import com.checkSheet.repository.MasterDepartmentRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Service implementation for Master Department Management (pure CRUD operations).
 * This is separate from DepartmentServiceImpl which handles Department Admin assignment.
 */
@Service
@Transactional(readOnly = true)
public class MasterDepartmentServiceImpl implements MasterDepartmentService {

    @Autowired
    private MasterDepartmentRepository masterDepartmentRepository;
    
    @Autowired
    private UtilityService utilityService;
    
    @Autowired
    private PermissionService permissionService;

    /**
     * Get current logged-in user and validate authentication
     */
    private User getCurrentUser() throws CustomException {
        Optional<User> currentUser = utilityService.getCurrentLoggedInUser();
        if (!currentUser.isPresent()) {
            throw new CustomException("User not authenticated", HttpStatus.UNAUTHORIZED);
        }
        return currentUser.get();
    }
    
    /**
     * Check if current user has a specific permission
     */
    private void checkPermission(Long userId, String permissionCode) throws CustomException {
        boolean hasPermission = permissionService.hasPermission(userId, permissionCode);
        if (!hasPermission) {
            throw new CustomException("You don't have permission to perform this action", HttpStatus.FORBIDDEN);
        }
    }

    // ===================== Master Department CRUD =====================

    @Override
    public ResponseDTO<?> searchMasterDepartments(MasterDepartmentDTO dto) throws CustomException {
        try {
            User currentUser = getCurrentUser();
            checkPermission(currentUser.getId(), "MASTER_DEPARTMENT_LIST");
            
            int page = dto.getCurrentPage() != null ? dto.getCurrentPage() : 0;
            int size = dto.getPerPageRecord() != null ? dto.getPerPageRecord() : 10;
            String search = dto.getSearch() != null ? dto.getSearch() : "";
            
            Pageable pageable = PageRequest.of(page, size, Sort.by("name").ascending());
            Page<Department> departmentsPage = masterDepartmentRepository.searchMasterDepartments(search, pageable);
            
            List<MasterDepartmentDTO> departmentDTOs = departmentsPage.getContent().stream().map(dept -> {
                MasterDepartmentDTO deptDTO = new MasterDepartmentDTO();
                deptDTO.setId(dept.getId());
                deptDTO.setName(dept.getName());
                deptDTO.setCreatedAt(dept.getCreatedAt());
                deptDTO.setUpdatedAt(dept.getUpdatedAt());
                
                // Get section count
                Long sectionCount = masterDepartmentRepository.countSectionsByMasterDepartmentId(dept.getId());
                deptDTO.setSectionCount(sectionCount != null ? sectionCount.intValue() : 0);
                
                // Set UI flags
                deptDTO.setIsEditable(true);
                // Cannot delete if sections exist
                deptDTO.setIsDeletable(sectionCount == null || sectionCount == 0);
                
                return deptDTO;
            }).collect(Collectors.toList());
            
            return new ResponseDTO<>(
                "Master departments fetched successfully",
                departmentDTOs,
                departmentsPage.getTotalElements(),
                departmentsPage.getTotalPages(),
                page,
                size
            );
        } catch (CustomException ce) {
            throw ce;
        } catch (Exception e) {
            e.printStackTrace();
            throw new CustomException("Error fetching master departments: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    public ResponseDTO<?> getMasterDepartment(MasterDepartmentDTO dto) throws CustomException {
        try {
            User currentUser = getCurrentUser();
            checkPermission(currentUser.getId(), "MASTER_DEPARTMENT_VIEW");
            
            if (dto.getId() == null) {
                throw new CustomException("Master Department ID is required", HttpStatus.BAD_REQUEST);
            }
            
            Optional<Department> deptOpt = masterDepartmentRepository.findMasterDepartmentById(dto.getId());
            if (!deptOpt.isPresent()) {
                throw new CustomException("Master Department not found", HttpStatus.NOT_FOUND);
            }
            
            Department dept = deptOpt.get();
            MasterDepartmentDTO resultDTO = new MasterDepartmentDTO();
            resultDTO.setId(dept.getId());
            resultDTO.setName(dept.getName());
            resultDTO.setCreatedAt(dept.getCreatedAt());
            resultDTO.setUpdatedAt(dept.getUpdatedAt());
            
            if (dept.getCreatedBy() != null) {
                resultDTO.setCreatedById(dept.getCreatedBy().getId());
                resultDTO.setCreatedByName(dept.getCreatedBy().getFirstName() + 
                    (dept.getCreatedBy().getLastName() != null ? " " + dept.getCreatedBy().getLastName() : ""));
            }
            
            // Get sections under this master department
            List<Department> sections = masterDepartmentRepository.findSectionsByMasterDepartmentId(dept.getId());
            List<MasterDepartmentDTO.MasterDepartmentSectionDTO> sectionDTOs = sections.stream().map(section -> {
                MasterDepartmentDTO.MasterDepartmentSectionDTO sectionDTO = new MasterDepartmentDTO.MasterDepartmentSectionDTO();
                sectionDTO.setId(section.getId());
                sectionDTO.setName(section.getName());
                sectionDTO.setCreatedAt(section.getCreatedAt());
                sectionDTO.setUpdatedAt(section.getUpdatedAt());
                sectionDTO.setIsEditable(true);
                
                // Check if section can be deleted (no users or checksheets assigned)
                Long userCount = masterDepartmentRepository.countActiveUserRoleDepartmentsByDepartmentId(section.getId());
                Long checksheetCount = masterDepartmentRepository.countActiveChecksheetsByDepartmentId(section.getId());
                sectionDTO.setIsDeletable((userCount == null || userCount == 0) && (checksheetCount == null || checksheetCount == 0));
                
                return sectionDTO;
            }).collect(Collectors.toList());
            
            resultDTO.setSections(sectionDTOs);
            resultDTO.setSectionCount(sectionDTOs.size());
            resultDTO.setIsEditable(true);
            resultDTO.setIsDeletable(sectionDTOs.isEmpty());
            
            return new ResponseDTO<>("Master Department fetched successfully", resultDTO);
        } catch (CustomException ce) {
            throw ce;
        } catch (Exception e) {
            e.printStackTrace();
            throw new CustomException("Error fetching master department: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    @Transactional
    public ResponseDTO<?> createMasterDepartment(MasterDepartmentDTO dto) throws CustomException {
        try {
            User currentUser = getCurrentUser();
            checkPermission(currentUser.getId(), "MASTER_DEPARTMENT_CREATE");
            
            if (dto.getName() == null || dto.getName().trim().isEmpty()) {
                throw new CustomException("Department name is required", HttpStatus.BAD_REQUEST);
            }
            
            // Check for duplicate name
            Optional<Department> existingDept = masterDepartmentRepository.findByNameAndDepartmentIdIsNullExcludingId(
                dto.getName().trim(), null);
            if (existingDept.isPresent()) {
                throw new CustomException("A department with this name already exists", HttpStatus.CONFLICT);
            }
            
            Department newDept = new Department();
            newDept.setName(dto.getName().trim());
            newDept.setDepartmentId(null); // This makes it a master department
            newDept.setCreatedBy(currentUser);
            newDept.setCreatedAt(new Date());
            
            Department savedDept = masterDepartmentRepository.save(newDept);
            
            MasterDepartmentDTO resultDTO = new MasterDepartmentDTO();
            resultDTO.setId(savedDept.getId());
            resultDTO.setName(savedDept.getName());
            resultDTO.setCreatedAt(savedDept.getCreatedAt());
            resultDTO.setSectionCount(0);
            resultDTO.setIsEditable(true);
            resultDTO.setIsDeletable(true);
            
            return new ResponseDTO<>("Master Department created successfully", resultDTO);
        } catch (CustomException ce) {
            throw ce;
        } catch (Exception e) {
            e.printStackTrace();
            throw new CustomException("Error creating master department: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    @Transactional
    public ResponseDTO<?> updateMasterDepartment(MasterDepartmentDTO dto) throws CustomException {
        try {
            User currentUser = getCurrentUser();
            checkPermission(currentUser.getId(), "MASTER_DEPARTMENT_UPDATE");
            
            if (dto.getId() == null) {
                throw new CustomException("Master Department ID is required", HttpStatus.BAD_REQUEST);
            }
            
            if (dto.getName() == null || dto.getName().trim().isEmpty()) {
                throw new CustomException("Department name is required", HttpStatus.BAD_REQUEST);
            }
            
            Optional<Department> deptOpt = masterDepartmentRepository.findMasterDepartmentById(dto.getId());
            if (!deptOpt.isPresent()) {
                throw new CustomException("Master Department not found", HttpStatus.NOT_FOUND);
            }
            
            // Check for duplicate name (excluding current department)
            Optional<Department> existingDept = masterDepartmentRepository.findByNameAndDepartmentIdIsNullExcludingId(
                dto.getName().trim(), dto.getId());
            if (existingDept.isPresent()) {
                throw new CustomException("A department with this name already exists", HttpStatus.CONFLICT);
            }
            
            Department dept = deptOpt.get();
            dept.setName(dto.getName().trim());
            dept.setUpdatedBy(currentUser);
            dept.setUpdatedAt(new Date());
            
            Department savedDept = masterDepartmentRepository.save(dept);
            
            MasterDepartmentDTO resultDTO = new MasterDepartmentDTO();
            resultDTO.setId(savedDept.getId());
            resultDTO.setName(savedDept.getName());
            resultDTO.setCreatedAt(savedDept.getCreatedAt());
            resultDTO.setUpdatedAt(savedDept.getUpdatedAt());
            resultDTO.setIsEditable(true);
            
            Long sectionCount = masterDepartmentRepository.countSectionsByMasterDepartmentId(savedDept.getId());
            resultDTO.setSectionCount(sectionCount != null ? sectionCount.intValue() : 0);
            resultDTO.setIsDeletable(sectionCount == null || sectionCount == 0);
            
            return new ResponseDTO<>("Master Department updated successfully", resultDTO);
        } catch (CustomException ce) {
            throw ce;
        } catch (Exception e) {
            e.printStackTrace();
            throw new CustomException("Error updating master department: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    @Transactional
    public ResponseDTO<?> deleteMasterDepartment(MasterDepartmentDTO dto) throws CustomException {
        try {
            User currentUser = getCurrentUser();
            checkPermission(currentUser.getId(), "MASTER_DEPARTMENT_DELETE");
            
            if (dto.getId() == null) {
                throw new CustomException("Master Department ID is required", HttpStatus.BAD_REQUEST);
            }
            
            Optional<Department> deptOpt = masterDepartmentRepository.findMasterDepartmentById(dto.getId());
            if (!deptOpt.isPresent()) {
                throw new CustomException("Master Department not found", HttpStatus.NOT_FOUND);
            }
            
            // Block deletion if sections exist
            Long sectionCount = masterDepartmentRepository.countSectionsByMasterDepartmentId(dto.getId());
            if (sectionCount != null && sectionCount > 0) {
                throw new CustomException("Cannot delete department. Please delete all sections (" + sectionCount + ") first.", HttpStatus.BAD_REQUEST);
            }
            
            // Also check for any user assignments or checksheets directly on this department
            Long userCount = masterDepartmentRepository.countActiveUserRoleDepartmentsByDepartmentId(dto.getId());
            if (userCount != null && userCount > 0) {
                throw new CustomException("Cannot delete department. " + userCount + " user(s) are assigned to this department.", HttpStatus.BAD_REQUEST);
            }
            
            Long checksheetCount = masterDepartmentRepository.countActiveChecksheetsByDepartmentId(dto.getId());
            if (checksheetCount != null && checksheetCount > 0) {
                throw new CustomException("Cannot delete department. " + checksheetCount + " checksheet(s) are assigned to this department.", HttpStatus.BAD_REQUEST);
            }
            
            // Soft delete
            Department dept = deptOpt.get();
            dept.setDeletedAt(new Date());
            dept.setDeletedBy(currentUser);
            masterDepartmentRepository.save(dept);
            
            return new ResponseDTO<>(true, "Master Department deleted successfully");
        } catch (CustomException ce) {
            throw ce;
        } catch (Exception e) {
            e.printStackTrace();
            throw new CustomException("Error deleting master department: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    // ===================== Section Management within Master Department =====================

    @Override
    @Transactional
    public ResponseDTO<?> createSection(Long masterDepartmentId, MasterDepartmentDTO.MasterDepartmentSectionDTO sectionDTO) throws CustomException {
        try {
            User currentUser = getCurrentUser();
            checkPermission(currentUser.getId(), "MASTER_DEPARTMENT_UPDATE");
            
            if (masterDepartmentId == null) {
                throw new CustomException("Master Department ID is required", HttpStatus.BAD_REQUEST);
            }
            
            if (sectionDTO.getName() == null || sectionDTO.getName().trim().isEmpty()) {
                throw new CustomException("Section name is required", HttpStatus.BAD_REQUEST);
            }
            
            // Verify master department exists
            Optional<Department> masterDeptOpt = masterDepartmentRepository.findMasterDepartmentById(masterDepartmentId);
            if (!masterDeptOpt.isPresent()) {
                throw new CustomException("Master Department not found", HttpStatus.NOT_FOUND);
            }
            
            // Check for duplicate section name within this master department
            Optional<Department> existingSection = masterDepartmentRepository.findSectionByNameAndMasterDepartmentIdExcludingId(
                sectionDTO.getName().trim(), masterDepartmentId, null);
            if (existingSection.isPresent()) {
                throw new CustomException("A section with this name already exists in this department", HttpStatus.CONFLICT);
            }
            
            Department newSection = new Department();
            newSection.setName(sectionDTO.getName().trim());
            newSection.setDepartmentId(masterDeptOpt.get()); // Set parent department
            newSection.setCreatedBy(currentUser);
            newSection.setCreatedAt(new Date());
            
            Department savedSection = masterDepartmentRepository.save(newSection);
            
            MasterDepartmentDTO.MasterDepartmentSectionDTO resultDTO = new MasterDepartmentDTO.MasterDepartmentSectionDTO();
            resultDTO.setId(savedSection.getId());
            resultDTO.setName(savedSection.getName());
            resultDTO.setCreatedAt(savedSection.getCreatedAt());
            resultDTO.setIsEditable(true);
            resultDTO.setIsDeletable(true);
            
            return new ResponseDTO<>("Section created successfully", resultDTO);
        } catch (CustomException ce) {
            throw ce;
        } catch (Exception e) {
            e.printStackTrace();
            throw new CustomException("Error creating section: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    @Transactional
    public ResponseDTO<?> updateSection(MasterDepartmentDTO.MasterDepartmentSectionDTO sectionDTO) throws CustomException {
        try {
            User currentUser = getCurrentUser();
            checkPermission(currentUser.getId(), "MASTER_DEPARTMENT_UPDATE");
            
            if (sectionDTO.getId() == null) {
                throw new CustomException("Section ID is required", HttpStatus.BAD_REQUEST);
            }
            
            if (sectionDTO.getName() == null || sectionDTO.getName().trim().isEmpty()) {
                throw new CustomException("Section name is required", HttpStatus.BAD_REQUEST);
            }
            
            Optional<Department> sectionOpt = masterDepartmentRepository.findSectionById(sectionDTO.getId());
            if (!sectionOpt.isPresent()) {
                throw new CustomException("Section not found", HttpStatus.NOT_FOUND);
            }
            
            Department section = sectionOpt.get();
            Long masterDepartmentId = section.getDepartmentId().getId();
            
            // Check for duplicate section name within this master department
            Optional<Department> existingSection = masterDepartmentRepository.findSectionByNameAndMasterDepartmentIdExcludingId(
                sectionDTO.getName().trim(), masterDepartmentId, sectionDTO.getId());
            if (existingSection.isPresent()) {
                throw new CustomException("A section with this name already exists in this department", HttpStatus.CONFLICT);
            }
            
            section.setName(sectionDTO.getName().trim());
            section.setUpdatedBy(currentUser);
            section.setUpdatedAt(new Date());
            
            Department savedSection = masterDepartmentRepository.save(section);
            
            MasterDepartmentDTO.MasterDepartmentSectionDTO resultDTO = new MasterDepartmentDTO.MasterDepartmentSectionDTO();
            resultDTO.setId(savedSection.getId());
            resultDTO.setName(savedSection.getName());
            resultDTO.setCreatedAt(savedSection.getCreatedAt());
            resultDTO.setUpdatedAt(savedSection.getUpdatedAt());
            resultDTO.setIsEditable(true);
            
            // Check if section can be deleted
            Long userCount = masterDepartmentRepository.countActiveUserRoleDepartmentsByDepartmentId(savedSection.getId());
            Long checksheetCount = masterDepartmentRepository.countActiveChecksheetsByDepartmentId(savedSection.getId());
            resultDTO.setIsDeletable((userCount == null || userCount == 0) && (checksheetCount == null || checksheetCount == 0));
            
            return new ResponseDTO<>("Section updated successfully", resultDTO);
        } catch (CustomException ce) {
            throw ce;
        } catch (Exception e) {
            e.printStackTrace();
            throw new CustomException("Error updating section: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    @Transactional
    public ResponseDTO<?> deleteSection(Long sectionId) throws CustomException {
        try {
            User currentUser = getCurrentUser();
            checkPermission(currentUser.getId(), "MASTER_DEPARTMENT_UPDATE");
            
            if (sectionId == null) {
                throw new CustomException("Section ID is required", HttpStatus.BAD_REQUEST);
            }
            
            Optional<Department> sectionOpt = masterDepartmentRepository.findSectionById(sectionId);
            if (!sectionOpt.isPresent()) {
                throw new CustomException("Section not found", HttpStatus.NOT_FOUND);
            }
            
            // Check for any user assignments
            Long userCount = masterDepartmentRepository.countActiveUserRoleDepartmentsByDepartmentId(sectionId);
            if (userCount != null && userCount > 0) {
                throw new CustomException("Cannot delete section. " + userCount + " user(s) are assigned to this section.", HttpStatus.BAD_REQUEST);
            }
            
            // Check for any checksheets
            Long checksheetCount = masterDepartmentRepository.countActiveChecksheetsByDepartmentId(sectionId);
            if (checksheetCount != null && checksheetCount > 0) {
                throw new CustomException("Cannot delete section. " + checksheetCount + " checksheet(s) are assigned to this section.", HttpStatus.BAD_REQUEST);
            }
            
            // Soft delete
            Department section = sectionOpt.get();
            section.setDeletedAt(new Date());
            section.setDeletedBy(currentUser);
            masterDepartmentRepository.save(section);
            
            return new ResponseDTO<>(true, "Section deleted successfully");
        } catch (CustomException ce) {
            throw ce;
        } catch (Exception e) {
            e.printStackTrace();
            throw new CustomException("Error deleting section: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    public ResponseDTO<?> getAllMasterDepartmentsForDropdown() throws CustomException {
        try {
            User currentUser = getCurrentUser();
            
            Page<Department> departments = masterDepartmentRepository.searchMasterDepartments("", 
                PageRequest.of(0, 1000, Sort.by("name").ascending()));
            
            List<MasterDepartmentDTO> departmentDTOs = departments.getContent().stream()
                .map(dept -> new MasterDepartmentDTO(dept.getId(), dept.getName()))
                .collect(Collectors.toList());
            
            return new ResponseDTO<>("Master departments fetched successfully", departmentDTOs);
        } catch (CustomException ce) {
            throw ce;
        } catch (Exception e) {
            e.printStackTrace();
            throw new CustomException("Error fetching master departments: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
}
