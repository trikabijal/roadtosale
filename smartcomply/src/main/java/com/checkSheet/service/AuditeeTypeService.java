package com.checkSheet.service;

import com.checkSheet.DTO.response.ResponseDTO;
import com.checkSheet.exception.CustomException;

public interface AuditeeTypeService {
    ResponseDTO<?> getAuditeeTypes() throws CustomException;
}
