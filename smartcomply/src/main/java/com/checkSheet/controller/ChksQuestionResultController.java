package com.checkSheet.controller;

import com.checkSheet.DTO.BulkChksQuestionResultDTO;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.checkSheet.DTO.ChksQuestionResultDTO;
import com.checkSheet.DTO.response.ResponseDTO;
import com.checkSheet.exception.CustomException;
import com.checkSheet.service.ChksQuestionResultService;

@RestController
@CrossOrigin(origins = "*", allowedHeaders = "*")
@RequestMapping("/api/chksQuestionResult")
public class ChksQuestionResultController {

    @Autowired
    private ChksQuestionResultService chksQuestionResultService;

    @PostMapping("/setQuestionResult")
    public ResponseEntity<?> setQuestionResult(@ModelAttribute ChksQuestionResultDTO chksQuestionResultDTO) {
        try {
            return ResponseEntity.ok(chksQuestionResultService.setQuestionResult(chksQuestionResultDTO));
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    @PostMapping("/downloadQuestionResultMatrixFile")
    public ResponseEntity<Resource> downloadQuestionResultMatrixFile(@RequestBody ChksQuestionResultDTO chksQuestionResultDTO,
                                                                     HttpServletRequest request) throws CustomException{
//        try {
//            return ResponseEntity.ok(chksQuestionResultService.downloadQuestionResultMatrixFile(chksQuestionResultDTO));
            return chksQuestionResultService.downloadQuestionResultMatrixFile(chksQuestionResultDTO,request);
//        } catch (CustomException e) {
//            return ResponseEntity
//                    .status(e.getHttpStatus())
//                    .body(new ResponseDTO<>(false, e.getMessage()));
//        }
    }

    @PostMapping("/getResultData")
    public ResponseEntity<?> getResultData(@RequestBody ChksQuestionResultDTO chksQuestionResultDTO) {
        try {
            return ResponseEntity.ok(chksQuestionResultService.getResultData(chksQuestionResultDTO));
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    @PostMapping("/cloneQuestionResult")
    public ResponseEntity<?> cloneQuestionResult(@RequestBody ChksQuestionResultDTO chksQuestionResultDTO) {
        try {
            return ResponseEntity.ok(chksQuestionResultService.cloneQuestionResult(chksQuestionResultDTO));
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    @PostMapping("/bulkSetQuestionResult")
    public ResponseEntity<?> bulkSetQuestionResult(@ModelAttribute BulkChksQuestionResultDTO bulkDTO) {
        try {
            return ResponseEntity.ok(chksQuestionResultService.bulkSetQuestionResult(bulkDTO));
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }
}
