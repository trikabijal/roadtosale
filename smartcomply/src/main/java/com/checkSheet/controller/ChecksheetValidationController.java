package com.checkSheet.controller;

import com.checkSheet.DTO.ChecksheetValidationDTO;
import com.checkSheet.DTO.response.ResponseDTO;
import com.checkSheet.exception.CustomException;
import com.checkSheet.service.ChecksheetValidationService;
import com.checkSheet.service.ChksHeaderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@CrossOrigin(origins = "*", allowedHeaders = "*")
@RequestMapping("/api/checksheetValidation")
public class ChecksheetValidationController {

    @Autowired
    private ChecksheetValidationService checksheetValidationService;

    @PostMapping("/createValidation")
    public ResponseEntity<?> validateChecksheet(@RequestBody ChecksheetValidationDTO checksheetValidationDTO) {
        try {
            return ResponseEntity.ok(checksheetValidationService.addChecksheetValidation(checksheetValidationDTO));
        } catch (CustomException e) {
            return ResponseEntity
                .status(e.getHttpStatus())
                .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    @PostMapping("/getChecksheetValidation")
    public ResponseEntity<?> getChecksheetValidation(@RequestBody ChecksheetValidationDTO checksheetValidationDTO) {
        try {
            return ResponseEntity.ok(checksheetValidationService.getChecksheetValidation(checksheetValidationDTO));
        } catch (CustomException e) {
            return ResponseEntity
                .status(e.getHttpStatus())
                .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }
}
