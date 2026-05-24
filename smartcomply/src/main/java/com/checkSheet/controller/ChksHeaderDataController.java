package com.checkSheet.controller;

import com.checkSheet.DTO.ChksHeaderDataDTO;
import com.checkSheet.DTO.ChksHeaderDataFileDTO;
import com.checkSheet.DTO.response.ResponseDTO;
import com.checkSheet.exception.CustomException;
import com.checkSheet.service.ChksHeaderDataService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@CrossOrigin(origins = "*", allowedHeaders = "*")
@RequestMapping("/api/chksHeaderData")
public class ChksHeaderDataController {

    @Autowired
    private ChksHeaderDataService chksHeaderDataService;


    @PostMapping("/uploadExcel")
    public ResponseEntity<?> uploadExcel(@RequestParam(value = "file", required = false) MultipartFile file,
                                    @RequestParam(value = "checksheetId", required = false) Long checksheetId,
                                    @RequestParam(value = "isAppend", required = false) Boolean isAppend) {
        try {
            return ResponseEntity.ok(chksHeaderDataService.validateCheckSheet(file, checksheetId, isAppend));
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    @GetMapping("/{checksheetId}")
    public ResponseEntity<?> getChksHeaderData(@PathVariable Long checksheetId) {
        try {
            return ResponseEntity.ok(chksHeaderDataService.getChecksheetData(checksheetId,false));
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    @PutMapping("/updateDescription")
    public ResponseEntity<?> updateDescription(@RequestBody ChksHeaderDataDTO chksHeaderDataDTO) {
        try {
            return ResponseEntity.ok(chksHeaderDataService.updateDescription(chksHeaderDataDTO));
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    @PutMapping("/updateName")
    public ResponseEntity<?> updateName(@RequestBody ChksHeaderDataDTO chksHeaderDataDTO) {
        try {
            return ResponseEntity.ok(chksHeaderDataService.updateName(chksHeaderDataDTO));
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    @PostMapping("/resetChecksheetData")
    public ResponseEntity<?> resetChecksheetData(@RequestBody ChksHeaderDataDTO chksHeaderDataDTO) throws CustomException {
        try {
            return ResponseEntity.ok(chksHeaderDataService.resetChecksheetData(chksHeaderDataDTO));
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    @PostMapping("/uploadFiles")
    public ResponseEntity<?> uploadFiles(@Validated @ModelAttribute ChksHeaderDataDTO chksHeaderDataDTO) throws CustomException {
        try {
            return ResponseEntity.ok(chksHeaderDataService.uploadFiles(chksHeaderDataDTO));
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    @PostMapping("/deleteFile")
    public ResponseEntity<?> deleteFile(@RequestBody ChksHeaderDataFileDTO chksHeaderDataFileDTO) throws CustomException {
        try {
            return ResponseEntity.ok(chksHeaderDataService.deleteFile(chksHeaderDataFileDTO));
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    /*@PostMapping("/createHierarchical")
    public ResponseEntity<?> createHierarchicalHeaderData(@RequestBody ChksHeaderDataDTO rootHeaderDataDTO) {
        try {
            return ResponseEntity.ok(chksHeaderDataService.createHierarchicalChecksheetData(rootHeaderDataDTO));
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }*/

    @PostMapping("/createHeaderDataHierarchical")
    public ResponseEntity<?> createPartialHierarchicalData(@RequestBody ChksHeaderDataDTO headerDataDTO) {
        try {
            return ResponseEntity.ok(chksHeaderDataService.createPartialHierarchicalData(headerDataDTO));
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    @PostMapping("/deleteChildHeaderData")
    public ResponseEntity<?> deleteChildHeaderData(@RequestBody ChksHeaderDataDTO chksHeaderDataDTO) {
        try {
            return ResponseEntity.ok(chksHeaderDataService.deleteChildHeaderData(chksHeaderDataDTO));
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }
}
