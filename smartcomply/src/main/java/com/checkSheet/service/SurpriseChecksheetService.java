package com.checkSheet.service;

import com.checkSheet.DTO.SurprizeChecksheetDTO;
import com.checkSheet.DTO.SurprizeChecksheetFieldDTO;
import com.checkSheet.DTO.response.ResponseDTO;
import com.checkSheet.exception.CustomException;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface SurpriseChecksheetService {
    @Transactional(rollbackFor = Exception.class)
    ResponseDTO<?> createSurpriseChecksheets(List<SurprizeChecksheetDTO> surprizeChecksheets) throws CustomException;

    @Transactional(rollbackFor = Exception.class)
    ResponseDTO<?> createSurpriseChecksheetFields(SurprizeChecksheetFieldDTO surprizeChecksheetField) throws CustomException;
}
