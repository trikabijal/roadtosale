package com.checkSheet.controller;

import com.checkSheet.DTO.ChksQuestionDTO;
import com.checkSheet.DTO.ChksQuestionFileDTO;
import com.checkSheet.DTO.response.ResponseDTO;
import com.checkSheet.exception.CustomException;
import com.checkSheet.service.ChksQuestionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@CrossOrigin(origins = "*", allowedHeaders = "*")
@RequestMapping("/api/chksQuestion")
public class ChksQuestionController {

    @Autowired
    private ChksQuestionService chksQuestionService;


    @PutMapping("/updateDescription")
    public ResponseEntity<?> updateDescription(@RequestBody ChksQuestionDTO chksQuestionDTO) {
        try {
            return ResponseEntity.ok(chksQuestionService.updateDescription(chksQuestionDTO));
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    @PutMapping("/updateName")
    public ResponseEntity<?> updateName(@RequestBody ChksQuestionDTO chksQuestionDTO) {
        try {
            return ResponseEntity.ok(chksQuestionService.updateName(chksQuestionDTO));
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    @PostMapping("/uploadFiles")
    public ResponseEntity<?> uploadFiles(@ModelAttribute ChksQuestionDTO chksQuestionDTO) {
        try {
            return ResponseEntity.ok(chksQuestionService.uploadFiles(chksQuestionDTO));
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    @PostMapping("/deleteFile")
    public ResponseEntity<?> deleteFile(@RequestBody ChksQuestionFileDTO chksQuestionFileDTO) {
        try {
            return ResponseEntity.ok(chksQuestionService.deleteFile(chksQuestionFileDTO));
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    @PostMapping("/createBulkQuestions")
    public ResponseEntity<?> createBulkQuestions(@RequestBody com.checkSheet.DTO.request.ChksQuestionBulkDTO chksQuestionBulkDTO) {
        try {
            return ResponseEntity.ok(chksQuestionService.createBulkQuestions(chksQuestionBulkDTO));
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    @PostMapping("/deleteQuestions")
    public ResponseEntity<?> deleteQuestions(@RequestBody ChksQuestionDTO chksQuestionDTO) {
        try {
            return ResponseEntity.ok(chksQuestionService.deleteQuestions(chksQuestionDTO));
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }
}
