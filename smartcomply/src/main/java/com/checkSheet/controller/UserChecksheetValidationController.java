package com.checkSheet.controller;

import com.checkSheet.DTO.ChecksheetValidationDTO;
import com.checkSheet.DTO.UserChecksheetValidationDTO;
import com.checkSheet.DTO.response.ResponseDTO;
import com.checkSheet.exception.CustomException;
import com.checkSheet.service.ChecksheetValidationService;
import com.checkSheet.service.UserChecksheetValidationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@CrossOrigin(origins = "*", allowedHeaders = "*")
@RequestMapping("/api/userChecksheetValidation")
public class UserChecksheetValidationController {

    @Autowired
    private UserChecksheetValidationService userChecksheetValidationService;

    @PostMapping("/addUserChecksheetValidation")
    public ResponseEntity<?> validateChecksheet(@RequestBody UserChecksheetValidationDTO userChecksheetValidationDTO) {
        try {
            return ResponseEntity.ok(userChecksheetValidationService.addUserChecksheetValidation(userChecksheetValidationDTO));
        } catch (CustomException e) {
            return ResponseEntity
                .status(e.getHttpStatus())
                .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    @PostMapping("/getUserChecksheetValidation")
    public ResponseEntity<?> getChecksheetValidation(@RequestBody UserChecksheetValidationDTO userChecksheetValidationDTO) {
        try {
            return ResponseEntity.ok(userChecksheetValidationService.getUserChecksheetValidation(userChecksheetValidationDTO));
        } catch (CustomException e) {
            return ResponseEntity
                .status(e.getHttpStatus())
                .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }
}
