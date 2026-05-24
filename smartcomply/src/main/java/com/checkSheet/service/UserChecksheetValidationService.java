package com.checkSheet.service;

import com.checkSheet.DTO.UserChecksheetValidationDTO;
import com.checkSheet.DTO.response.ResponseDTO;
import com.checkSheet.exception.CustomException;
import org.springframework.transaction.annotation.Transactional;

public interface UserChecksheetValidationService {

    @Transactional(rollbackFor = Exception.class)
    ResponseDTO<?> addUserChecksheetValidation(UserChecksheetValidationDTO userChecksheetValidationDTO) throws CustomException;

    ResponseDTO<?> getUserChecksheetValidation(UserChecksheetValidationDTO userChecksheetValidationDTO) throws CustomException;

}
