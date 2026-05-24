package com.checkSheet.controller;

import com.checkSheet.DTO.response.ResponseDTO;
import com.checkSheet.exception.CustomException;
import com.checkSheet.service.AiAssessmentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@CrossOrigin(origins = "*", allowedHeaders = "*")
@RequestMapping("/api/ai")
@ConditionalOnProperty(name = "ai.photo-assessment.enabled", havingValue = "true")
public class AiAssessmentController {

    @Autowired
    private AiAssessmentService aiAssessmentService;

    @PostMapping("assess")
    public ResponseEntity<?> assessPhoto(
            @RequestParam("photo") MultipartFile photo,
            @RequestParam("userChecksheetId") Long userChecksheetId,
            @RequestParam("chksQuestionResultId") Long chksQuestionResultId) {
        try {
            return ResponseEntity.ok(aiAssessmentService.assessPhoto(photo, userChecksheetId, chksQuestionResultId));
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    @GetMapping("assessment")
    public ResponseEntity<?> getAssessment(
            @RequestParam("userChecksheetId") Long userChecksheetId,
            @RequestParam("chksQuestionResultId") Long chksQuestionResultId) {
        try {
            return ResponseEntity.ok(aiAssessmentService.getAssessment(userChecksheetId, chksQuestionResultId));
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    @GetMapping("assessments")
    public ResponseEntity<?> getAssessmentsByChecksheet(
            @RequestParam("userChecksheetId") Long userChecksheetId) {
        try {
            return ResponseEntity.ok(aiAssessmentService.getAssessmentsByChecksheet(userChecksheetId));
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }
}
