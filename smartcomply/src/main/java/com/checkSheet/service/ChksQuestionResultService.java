package com.checkSheet.service;


import com.checkSheet.DTO.ChksQuestionResultDTO;
import com.checkSheet.DTO.response.ResponseDTO;
import com.checkSheet.exception.CustomException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import com.checkSheet.DTO.BulkChksQuestionResultDTO;

public interface ChksQuestionResultService {
    ResponseDTO<?> setQuestionResult(ChksQuestionResultDTO chksQuestionResultDTO) throws CustomException;

    byte[] downloadQuestionResultMatrixFile(ChksQuestionResultDTO chksQuestionResultDTO) throws CustomException;
    ResponseEntity<Resource> downloadQuestionResultMatrixFile(ChksQuestionResultDTO chksQuestionResultDTO, HttpServletRequest request) throws CustomException;

    ResponseDTO<?> getResultData(ChksQuestionResultDTO chksQuestionResultDTO) throws CustomException;

    ResponseDTO<?> cloneQuestionResult(ChksQuestionResultDTO chksQuestionResultDTO) throws CustomException;

    ResponseDTO<?> bulkSetQuestionResult(BulkChksQuestionResultDTO bulkDTO) throws CustomException;
}
