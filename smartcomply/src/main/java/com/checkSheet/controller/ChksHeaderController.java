package com.checkSheet.controller;

import com.checkSheet.DTO.ChksGeneralFieldDTO;
import com.checkSheet.DTO.ChksHdrSummaryReportLevelDTO;
import com.checkSheet.DTO.ChksHeaderDTO;
import com.checkSheet.DTO.response.ResponseDTO;
import com.checkSheet.exception.CustomException;
import com.checkSheet.service.ChksGeneralFieldService;
import com.checkSheet.service.ChksHeaderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@CrossOrigin(origins = "*", allowedHeaders = "*")
@RequestMapping("/api/chksHeader")
public class ChksHeaderController {

    @Autowired
    private ChksHeaderService chksHeaderService;

    @PostMapping("/createChksHeader")
    public ResponseEntity<?> createChksGeneralField(@RequestBody ChksHeaderDTO chksHeaderDTO) throws CustomException {
        try {
            return ResponseEntity.ok(chksHeaderService.createChksHeader(chksHeaderDTO));
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    @PostMapping("/getChksHeaderData")
    public ResponseEntity<?> getChksHeaderData(@RequestBody ChksHeaderDTO chksHeaderDTO) throws CustomException {
        try {
            return ResponseEntity.ok(chksHeaderService.getChksHeaderData(chksHeaderDTO));
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    @PostMapping("/deleteChksHeaderData")
    public ResponseEntity<?> deleteChksHeaderData(@RequestBody ChksHeaderDTO chksHeaderDTO) throws CustomException {
        try {
            return ResponseEntity.ok(chksHeaderService.deleteChksHeaderData(chksHeaderDTO));
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    @PostMapping("/createChksHdrSummaryReportLevel")
    public ResponseEntity<?> createChksHdrSummaryReportLevel(@RequestBody ChksHdrSummaryReportLevelDTO chksHdrSummaryReportLevelDTO) throws CustomException {
        try {
            return ResponseEntity.ok(chksHeaderService.createChksHdrSummaryReportLevel(chksHdrSummaryReportLevelDTO));
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    @PostMapping("/getChksHeaderSummaryReportLevel")
    public ResponseEntity<?> getChksHeaderSummaryReportLevel(@RequestBody ChksHeaderDTO chksHeaderDTO) throws CustomException {
        try {
            return ResponseEntity.ok(chksHeaderService.getChksHeaderSummaryReportLevel(chksHeaderDTO));
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }
}
