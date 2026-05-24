package com.checkSheet.service;

import com.checkSheet.DTO.ChecksheetValidationDTO;
import com.checkSheet.DTO.response.ResponseDTO;
import com.checkSheet.exception.CustomException;
import org.springframework.transaction.annotation.Transactional;

public interface ChecksheetValidationService {

    @Transactional(rollbackFor = Exception.class)
    ResponseDTO<?> addChecksheetValidation(ChecksheetValidationDTO checksheetValidationDTO) throws CustomException;

    ResponseDTO<?> getChecksheetValidation(ChecksheetValidationDTO checksheetValidationDTO) throws CustomException;
}
