package com.checkSheet.service;

import com.checkSheet.DTO.UserChecksheetApprovalDTO;
import com.checkSheet.DTO.response.ResponseDTO;
import com.checkSheet.exception.CustomException;
import org.springframework.transaction.annotation.Transactional;

public interface UserChecksheetApprovalService {
    @Transactional(rollbackFor = Exception.class)
    ResponseDTO<?> addUserChecksheetApproval(UserChecksheetApprovalDTO userChecksheetApprovalDTO) throws CustomException;

    ResponseDTO<?> getUserChecksheetApproval(UserChecksheetApprovalDTO userChecksheetApprovalDTO) throws CustomException;
}
