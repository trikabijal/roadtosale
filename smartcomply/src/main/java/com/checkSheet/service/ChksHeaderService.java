package com.checkSheet.service;

import com.checkSheet.DTO.ChksGeneralFieldDTO;
import com.checkSheet.DTO.ChksHdrSummaryReportLevelDTO;
import com.checkSheet.DTO.ChksHeaderDTO;
import com.checkSheet.DTO.response.ResponseDTO;
import com.checkSheet.exception.CustomException;
import org.springframework.transaction.annotation.Transactional;

public interface ChksHeaderService {
    @Transactional(rollbackFor = Exception.class)
    ResponseDTO<?> createChksHeader(ChksHeaderDTO chksHeaderDTO) throws CustomException;

    ResponseDTO<?> getChksHeaderData(ChksHeaderDTO chksHeaderDTO) throws CustomException;

    ResponseDTO<?> deleteChksHeaderData(ChksHeaderDTO chksHeaderDTO) throws CustomException;

    @Transactional(rollbackFor = Exception.class)
    ResponseDTO<?> createChksHdrSummaryReportLevel(ChksHdrSummaryReportLevelDTO chksHdrSummaryReportLevelDTO) throws CustomException;

    ResponseDTO<?> getChksHeaderSummaryReportLevel(ChksHeaderDTO chksHeaderDTO) throws CustomException;
}
