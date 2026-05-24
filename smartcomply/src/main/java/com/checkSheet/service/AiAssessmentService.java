package com.checkSheet.service;

import com.checkSheet.DTO.response.ResponseDTO;
import com.checkSheet.exception.CustomException;
import org.springframework.web.multipart.MultipartFile;

public interface AiAssessmentService {

    ResponseDTO<?> assessPhoto(MultipartFile photo, Long userChecksheetId, Long chksQuestionResultId) throws CustomException;

    ResponseDTO<?> getAssessment(Long userChecksheetId, Long chksQuestionResultId) throws CustomException;

    ResponseDTO<?> getAssessmentsByChecksheet(Long userChecksheetId) throws CustomException;
}
