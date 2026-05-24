package com.checkSheet.service;

import com.checkSheet.DTO.ChksGeneralFieldDTO;
import com.checkSheet.DTO.response.ResponseDTO;
import com.checkSheet.exception.CustomException;
import org.springframework.transaction.annotation.Transactional;

public interface ChksGeneralFieldService {

    @Transactional(rollbackFor = Exception.class)
    ResponseDTO<?> createChksGeneralField(ChksGeneralFieldDTO chksGeneralFieldDTO) throws CustomException;

    ResponseDTO<?> getChksGeneralFieldData(ChksGeneralFieldDTO chksGeneralFieldDTO) throws CustomException;

    ResponseDTO<?> deleteChksGeneralFieldData(ChksGeneralFieldDTO chksGeneralFieldDTO) throws CustomException;
}
