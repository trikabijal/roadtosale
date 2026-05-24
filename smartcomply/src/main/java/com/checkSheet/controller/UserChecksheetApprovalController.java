package com.checkSheet.controller;

import com.checkSheet.DTO.ChecksheetApprovalDTO;
import com.checkSheet.DTO.UserChecksheetApprovalDTO;
import com.checkSheet.DTO.response.ResponseDTO;
import com.checkSheet.exception.CustomException;
import com.checkSheet.service.ChecksheetApprovalService;
import com.checkSheet.service.UserChecksheetApprovalService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@CrossOrigin(origins = "*", allowedHeaders = "*")
@RequestMapping("/api/userChecksheetApproval")
public class UserChecksheetApprovalController {

    @Autowired
    private UserChecksheetApprovalService userChecksheetApprovalService;

    @PostMapping("/addUserChecksheetApproval")
    public ResponseEntity<?> addUserChecksheetApproval(@RequestBody UserChecksheetApprovalDTO userChecksheetApprovalDTO) {
        try {
            return ResponseEntity.ok(userChecksheetApprovalService.addUserChecksheetApproval(userChecksheetApprovalDTO));
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    @PostMapping("/getUserChecksheetApproval")
    public ResponseEntity<?> getUserChecksheetApproval(@RequestBody UserChecksheetApprovalDTO userChecksheetApprovalDTO) {
        try {
            return ResponseEntity.ok(userChecksheetApprovalService.getUserChecksheetApproval(userChecksheetApprovalDTO));
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

}
