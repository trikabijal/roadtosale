package com.checkSheet.service;

import com.checkSheet.DTO.ChecksheetApprovalDTO;
import com.checkSheet.DTO.response.ResponseDTO;
import com.checkSheet.exception.CustomException;
import org.springframework.transaction.annotation.Transactional;

public interface ChecksheetApprovalService {

    @Transactional(rollbackFor = Exception.class)
    ResponseDTO<?> addChecksheetApproval(ChecksheetApprovalDTO checksheetApprovalDTO) throws CustomException;

    ResponseDTO<?> getChecksheetApproval(ChecksheetApprovalDTO checksheetApprovalDTO) throws CustomException;
}
