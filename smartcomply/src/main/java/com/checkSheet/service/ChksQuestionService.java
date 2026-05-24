package com.checkSheet.service;

import com.checkSheet.DTO.ChksQuestionDTO;
import com.checkSheet.DTO.ChksQuestionFileDTO;
import com.checkSheet.DTO.response.ResponseDTO;
import com.checkSheet.exception.CustomException;
import org.springframework.transaction.annotation.Transactional;

public interface ChksQuestionService {

    @Transactional(rollbackFor = Exception.class)
    ResponseDTO<?> updateDescription(ChksQuestionDTO chksQuestionDTO) throws CustomException;

    @Transactional(rollbackFor = Exception.class)
    ResponseDTO<?> updateName(ChksQuestionDTO chksQuestionDTO) throws CustomException;

    @Transactional(rollbackFor = Exception.class)
    ResponseDTO<?> uploadFiles(ChksQuestionDTO chksQuestionDTO) throws CustomException;

    ResponseDTO<?> deleteFile(ChksQuestionFileDTO chksHeaderDataFileDTO) throws CustomException;
    
    @Transactional(rollbackFor = Exception.class)
    ResponseDTO<?> createBulkQuestions(com.checkSheet.DTO.request.ChksQuestionBulkDTO chksQuestionBulkDTO) throws CustomException;
    
    @Transactional(rollbackFor = Exception.class)
    ResponseDTO<?> deleteQuestions(ChksQuestionDTO chksQuestionDeleteDTO) throws CustomException;
}
