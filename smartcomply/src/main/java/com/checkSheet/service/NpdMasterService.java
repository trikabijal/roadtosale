package com.checkSheet.service;

import com.checkSheet.DTO.NpdMasterDTO;
import com.checkSheet.DTO.response.ResponseDTO;
import com.checkSheet.exception.CustomException;


public interface NpdMasterService {
    ResponseDTO<?> create(NpdMasterDTO dto) throws CustomException;
    ResponseDTO<?> createBulk(NpdMasterDTO dto) throws CustomException;
    ResponseDTO<?> update(NpdMasterDTO dto) throws CustomException;
    ResponseDTO<?> delete(NpdMasterDTO dto) throws CustomException;
    ResponseDTO<?> getById(Long id) throws CustomException;
    ResponseDTO<?> search(NpdMasterDTO dto) throws CustomException;
    ResponseDTO<?> runDailyNpd() throws CustomException;
    ResponseDTO<?> detailByChecksheetId(NpdMasterDTO dto) throws CustomException;
}


