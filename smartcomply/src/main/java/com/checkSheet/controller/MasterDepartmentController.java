package com.checkSheet.controller;

import com.checkSheet.DTO.MasterDepartmentDTO;
import com.checkSheet.DTO.response.ResponseDTO;
import com.checkSheet.exception.CustomException;
import com.checkSheet.service.MasterDepartmentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * REST Controller for Master Department Management (pure CRUD operations).
 * This is separate from DepartmentController which handles Department Admin assignment.
 * 
 * Base path: /api/master-department
 */
@RestController
@CrossOrigin(origins = "*", allowedHeaders = "*")
@RequestMapping("/api/master-department")
public class MasterDepartmentController {

    @Autowired
    private MasterDepartmentService masterDepartmentService;

    // ===================== Master Department CRUD Endpoints =====================

    /**
     * Search master departments with pagination
     * POST /api/master-department/search
     */
    @PostMapping("/search")
    public ResponseEntity<?> searchMasterDepartments(@RequestBody MasterDepartmentDTO dto) {
        try {
            return ResponseEntity.ok(masterDepartmentService.searchMasterDepartments(dto));
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    /**
     * Get single master department details with its sections
     * POST /api/master-department/get
     */
    @PostMapping("/get")
    public ResponseEntity<?> getMasterDepartment(@RequestBody MasterDepartmentDTO dto) {
        try {
            return ResponseEntity.ok(masterDepartmentService.getMasterDepartment(dto));
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    /**
     * Create a new master department
     * POST /api/master-department/create
     */
    @PostMapping("/create")
    public ResponseEntity<?> createMasterDepartment(@RequestBody MasterDepartmentDTO dto) {
        try {
            return ResponseEntity.ok(masterDepartmentService.createMasterDepartment(dto));
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    /**
     * Update an existing master department name
     * POST /api/master-department/update
     */
    @PostMapping("/update")
    public ResponseEntity<?> updateMasterDepartment(@RequestBody MasterDepartmentDTO dto) {
        try {
            return ResponseEntity.ok(masterDepartmentService.updateMasterDepartment(dto));
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    /**
     * Delete a master department (blocked if sections exist)
     * POST /api/master-department/delete
     */
    @PostMapping("/delete")
    public ResponseEntity<?> deleteMasterDepartment(@RequestBody MasterDepartmentDTO dto) {
        try {
            return ResponseEntity.ok(masterDepartmentService.deleteMasterDepartment(dto));
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    /**
     * Get all master departments for dropdown (simple list without pagination)
     * GET /api/master-department/dropdown
     */
    @GetMapping("/dropdown")
    public ResponseEntity<?> getAllMasterDepartmentsForDropdown() {
        try {
            return ResponseEntity.ok(masterDepartmentService.getAllMasterDepartmentsForDropdown());
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    // ===================== Section Management Endpoints (within Master Department) =====================

    /**
     * Create a new section under a master department
     * POST /api/master-department/{masterDepartmentId}/section/create
     */
    @PostMapping("/{masterDepartmentId}/section/create")
    public ResponseEntity<?> createSection(
            @PathVariable Long masterDepartmentId,
            @RequestBody MasterDepartmentDTO.MasterDepartmentSectionDTO sectionDTO) {
        try {
            return ResponseEntity.ok(masterDepartmentService.createSection(masterDepartmentId, sectionDTO));
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    /**
     * Update an existing section name
     * POST /api/master-department/section/update
     */
    @PostMapping("/section/update")
    public ResponseEntity<?> updateSection(@RequestBody MasterDepartmentDTO.MasterDepartmentSectionDTO sectionDTO) {
        try {
            return ResponseEntity.ok(masterDepartmentService.updateSection(sectionDTO));
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    /**
     * Delete a section (blocked if users or checksheets are assigned)
     * POST /api/master-department/section/delete
     */
    @PostMapping("/section/delete")
    public ResponseEntity<?> deleteSection(@RequestBody Map<String, Long> request) {
        try {
            Long sectionId = request.get("id");
            return ResponseEntity.ok(masterDepartmentService.deleteSection(sectionId));
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }
}
