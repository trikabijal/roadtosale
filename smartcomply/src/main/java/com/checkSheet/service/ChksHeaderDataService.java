package com.checkSheet.service;

import com.checkSheet.DTO.ChksHeaderDataDTO;
import com.checkSheet.DTO.ChksHeaderDataFileDTO;
import com.checkSheet.DTO.response.ResponseDTO;
import com.checkSheet.exception.CustomException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

public interface ChksHeaderDataService {

    ResponseDTO<?> validateCheckSheet(MultipartFile file, Long checksheetId, Boolean isAppend) throws CustomException;

    ResponseDTO<?> validateAndProcessExcelData(MultipartFile file, Long checksheetId, Boolean isAppend) throws CustomException;

    ResponseDTO<?> getChecksheetData(Long checksheetId, boolean withResultData) throws CustomException;

    @Transactional(rollbackFor = Exception.class)
    ResponseDTO<?> updateDescription(ChksHeaderDataDTO chksHeaderDataDTO) throws CustomException;

    @Transactional(rollbackFor = Exception.class)
    ResponseDTO<?> updateName(ChksHeaderDataDTO chksHeaderDataDTO) throws CustomException;

    @Transactional(rollbackFor = Exception.class)
    ResponseDTO<?> resetChecksheetData(ChksHeaderDataDTO chksHeaderDataDTO) throws CustomException;

    @Transactional(rollbackFor = Exception.class)
    void deleteChksData(Long checksheetId) throws CustomException;

    @Transactional(rollbackFor = Exception.class)
    ResponseDTO<?> uploadFiles(ChksHeaderDataDTO chksHeaderDataDTO) throws CustomException;

    ResponseDTO<?> deleteFile(ChksHeaderDataFileDTO chksHeaderDataFileDTO) throws CustomException;

    @Transactional(rollbackFor = Exception.class)
    ResponseDTO<?> createHierarchicalChecksheetData(ChksHeaderDataDTO rootHeaderDataDTO) throws CustomException;

    @Transactional(rollbackFor = Exception.class)
    ResponseDTO<?> createPartialHierarchicalData(ChksHeaderDataDTO headerDataDTO) throws CustomException;

    @Transactional(rollbackFor = Exception.class)
    ResponseDTO<?> deleteChildHeaderData(ChksHeaderDataDTO chksHeaderDataDTO) throws CustomException;
}
