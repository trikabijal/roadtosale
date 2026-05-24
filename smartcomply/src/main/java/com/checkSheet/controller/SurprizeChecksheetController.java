package com.checkSheet.controller;

import com.checkSheet.DTO.SurprizeChecksheetDTO;
import com.checkSheet.DTO.SurprizeChecksheetFieldDTO;
import com.checkSheet.DTO.response.ResponseDTO;
import com.checkSheet.exception.CustomException;
import com.checkSheet.service.SurpriseChecksheetService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@CrossOrigin(origins = "*", allowedHeaders = "*")
@RequestMapping("/api/surpriseChecksheet")
public class SurprizeChecksheetController {
    @Autowired
    private SurpriseChecksheetService surpriseChecksheetService;

    @PostMapping("createSurpriseChecksheets")
    public ResponseEntity<?> createSurpriseChecksheets(@RequestBody List<SurprizeChecksheetDTO> surprizeChecksheets) throws CustomException {
        try {
            return ResponseEntity.ok(surpriseChecksheetService.createSurpriseChecksheets(surprizeChecksheets));
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    @PostMapping("createSurpriseChecksheetFields")
    public ResponseEntity<?> createSurpriseChecksheetFields(@ModelAttribute SurprizeChecksheetFieldDTO surprizeChecksheetField) throws CustomException {
        try {
            return ResponseEntity.ok(surpriseChecksheetService.createSurpriseChecksheetFields(surprizeChecksheetField));
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }
}
