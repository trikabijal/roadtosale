package com.checkSheet.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.checkSheet.DTO.DepartmentDTO;
import com.checkSheet.DTO.response.ResponseDTO;
import com.checkSheet.exception.CustomException;
import com.checkSheet.service.DepartmentService;

import jakarta.servlet.http.HttpServletResponse;

@RestController
@CrossOrigin(origins = "*", allowedHeaders = "*")
@RequestMapping("/api/department")
public class DepartmentController {

    @Autowired
    private DepartmentService departmentService;

    @PostMapping("/createDepartmentAdmin")
    public ResponseEntity<?> createDepartmentAdmin(@RequestBody DepartmentDTO departmentDTO) {
        try {
            return ResponseEntity.ok(departmentService.createDepartmentAdmin(departmentDTO));
//            return ResponseEntity.ok(departmentService.createOrEditDepartment(departmentDTO));
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    @PostMapping("/deleteDepartmentAdmin")
    public ResponseEntity<?> deleteDepartmentAdmin(@RequestBody DepartmentDTO departmentDTO) {
        try {
            return ResponseEntity.ok(departmentService.deleteDepartmentAdmin(departmentDTO));
//            return ResponseEntity.ok(departmentService.createOrEditDepartment(departmentDTO));
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }
    @PostMapping("/editDepartmentAdmin")
    public ResponseEntity<?> editDepartmentAdmin(@RequestBody DepartmentDTO departmentDTO) {
        try {
            return ResponseEntity.ok(departmentService.editDepartmentAdmin(departmentDTO));
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }
    @PostMapping("/createOrEditSectionAndAdmin")
    public ResponseEntity<?> createOrEditSectionAndAdmin(@RequestBody DepartmentDTO departmentDTO) {
        try {
            return ResponseEntity.ok(departmentService.createOrEditSectionAndAdmin(departmentDTO));
//            return ResponseEntity.ok(departmentService.createOrEditDepartment(departmentDTO));
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }
    @PostMapping("/getDepartments")
    public ResponseEntity<?> getDepartments(@RequestBody DepartmentDTO departmentDTO) throws CustomException {
        try {
            return ResponseEntity.ok(departmentService.getDepartments(departmentDTO));
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    @PostMapping("/getSectionHead")
    public ResponseEntity<?> getSectionHead(@RequestBody DepartmentDTO departmentDTO) throws CustomException {
        try {
            return ResponseEntity.ok(departmentService.getSectionHead(departmentDTO));
        } catch (CustomException e) {
            return ResponseEntity
                .status(e.getHttpStatus())
                .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    @PostMapping("/searchDepartments")
    public ResponseEntity<?> searchDepartments(@RequestBody DepartmentDTO departmentDTO) throws CustomException {
        try {
            return ResponseEntity.ok(departmentService.searchDepartments(departmentDTO));
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    @PostMapping("/downloadDepartments")
    public ResponseEntity<?> downloadDepartments(@RequestBody DepartmentDTO departmentDTO, HttpServletResponse response) throws CustomException {
        try {
//            response.setContentType("application/octet-stream");
            response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

            String headerKey = "Content-Disposition";
            String headerValue = "attachment;filename=Department.xlsx";
            response.setHeader(headerKey, headerValue);

            departmentService.downloadDepartments(departmentDTO, response);
            return ResponseEntity.ok().build();
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
//            throw new CustomException(e.getMessage(), e);
        }
    }

    @PostMapping("/searchSections")
    public ResponseEntity<?> searchSections(@RequestBody DepartmentDTO departmentDTO) throws CustomException {
        try {
            return ResponseEntity.ok(departmentService.searchSections(departmentDTO));
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    @PostMapping("/downloadSections")
    public ResponseEntity<?> downloadSections(@RequestBody DepartmentDTO departmentDTO, 
                                            HttpServletResponse response) {
        try {
            // Set response headers
            response.setContentType("application/vnd.ms-excel");
            response.setHeader("Content-Disposition", "attachment; filename=Sections.xlsx");
            response.setHeader("Pragma", "public");
            response.setHeader("Cache-Control", "no-store");
            response.addHeader("Cache-Control", "max-age=0");
            
            departmentService.downloadSections(departmentDTO, response);
            return ResponseEntity.ok().build();
        } catch (CustomException e) {
            e.printStackTrace();
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity
                    .status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ResponseDTO<>(false, "Error generating excel file: " + e.getMessage()));
        }
    }

    @PostMapping("/searchUsers")
    public ResponseEntity<?> searchUsers(@RequestBody DepartmentDTO departmentDTO) {
        try {
            return ResponseEntity.ok(departmentService.searchUsers(departmentDTO));
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    @PostMapping("/downloadUsers")
    public ResponseEntity<?> downloadUsers(@RequestBody DepartmentDTO departmentDTO, HttpServletResponse response){
        try {
            response.setContentType("application/octet-stream");

            String headerKey = "Content-Disposition";
            String headerValue = "attachment;filename=Users.xlsx";
            response.setHeader(headerKey, headerValue);

            departmentService.downloadUsers(departmentDTO, response);
            return ResponseEntity.ok().build();
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    @GetMapping("getDepartments")
    public ResponseEntity<?> getDepartmnts() {
        try {
            return ResponseEntity.ok(departmentService.getDepartments());
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    @GetMapping("getUserDepartmentsOrSections")
    public ResponseEntity<?> getUserDepartmentsOrSections() {
        return ResponseEntity.ok(departmentService.getUserDepartmentsForList());
    }

    @GetMapping("getUserDepartmentsForList")
    public ResponseEntity<?> getUserDepartmentsForList() {
        return ResponseEntity.ok(departmentService.getUserDepartmentsForList());
    }

    @GetMapping("getUserDepartments")
    public ResponseEntity<?> getUserDepartments() {
        return ResponseEntity.ok(departmentService.getUserDepartments());
    }

    @GetMapping("getDeptAdminDepartments")
    public ResponseEntity<?> getDeptAdminDepartments() {
        return ResponseEntity.ok(departmentService.getDeptAdminDepartments());
    }

    @PostMapping("getSections")
    public ResponseEntity<?> getSections(@RequestBody DepartmentDTO departmentDTO){
        return ResponseEntity.ok(departmentService.getSections(departmentDTO));
    }

    @PostMapping("getUserSections")
    public ResponseEntity<?> getUserSections(@RequestBody DepartmentDTO departmentDTO){
        return ResponseEntity.ok(departmentService.getUserSections(departmentDTO));
    }

    @GetMapping("getUserRoleDepartment/{userRoleDepartmentId}")
    public ResponseEntity<?> getUserRoleDepartment(@PathVariable Long userRoleDepartmentId) {
        try {
            return ResponseEntity.ok(departmentService.getUserRoleDepartment(userRoleDepartmentId));
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    // Get user details API
    @GetMapping("/user-details/{userId}")
    public ResponseEntity<?> getUserRoleDetails(@PathVariable Long userId) {
        try {
            return ResponseEntity.ok(departmentService.getUserRoleDetails(userId));
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }
}
