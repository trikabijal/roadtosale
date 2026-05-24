package com.checkSheet.controller;

import com.checkSheet.DTO.ChecksheetApprovalDTO;
import com.checkSheet.DTO.ChecksheetValidationDTO;
import com.checkSheet.DTO.response.ResponseDTO;
import com.checkSheet.exception.CustomException;
import com.checkSheet.service.ChecksheetApprovalService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@CrossOrigin(origins = "*", allowedHeaders = "*")
@RequestMapping("/api/checksheetApproval")
public class ChecksheetApprovalController {

    @Autowired
    private ChecksheetApprovalService checksheetApprovalService;

    @PostMapping("/createApproval")
    public ResponseEntity<?> addChecksheetApproval(@RequestBody ChecksheetApprovalDTO checksheetApprovalDTO) {
        try {
            return ResponseEntity.ok(checksheetApprovalService.addChecksheetApproval(checksheetApprovalDTO));
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    @PostMapping("/getChecksheetApproval")
    public ResponseEntity<?> getChecksheetApproval(@RequestBody ChecksheetApprovalDTO checksheetApprovalDTO) {
        try {
            return ResponseEntity.ok(checksheetApprovalService.getChecksheetApproval(checksheetApprovalDTO));
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

}
