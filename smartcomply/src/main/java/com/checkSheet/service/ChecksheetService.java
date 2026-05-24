package com.checkSheet.service;

import com.checkSheet.DTO.ChecksheetDTO;
import com.checkSheet.DTO.DepartmentDTO;
import com.checkSheet.DTO.response.ResponseDTO;
import com.checkSheet.exception.CustomException;
import org.springframework.transaction.annotation.Transactional;

public interface ChecksheetService {

    @Transactional(rollbackFor = Exception.class)
    ResponseDTO<?> createChecksheet(ChecksheetDTO checksheetDTO) throws CustomException;

    byte[] downloadFile(ChecksheetDTO checksheetDTO) throws CustomException;

    ResponseDTO<?> getS3FileURL(ChecksheetDTO checksheetDTO) throws CustomException;

    ResponseDTO<?> getFileURL(ChecksheetDTO checksheetDTO) throws CustomException;

    ResponseDTO<?> getS3FilesURL(ChecksheetDTO checksheetDTO) throws CustomException;

    ResponseDTO<?> getFilesURL(ChecksheetDTO checksheetDTO) throws CustomException;

    ResponseDTO<?> getRespectedChecksheet(ChecksheetDTO checksheetDTO) throws CustomException;

    ResponseDTO<?> getChecksheetDetail(ChecksheetDTO checksheetDTO) throws CustomException;

    @Transactional(rollbackFor = Exception.class)
    ResponseDTO<?> updateChecksheetStatus(ChecksheetDTO checksheetDTO) throws CustomException;

    @Transactional(rollbackFor = Exception.class)
    ResponseDTO<?> createChecksheetVersion(ChecksheetDTO checksheetDTO) throws CustomException;

    ResponseDTO<?> getLovData() throws CustomException;

    ResponseDTO<?> getPublicChecksheets() throws CustomException;

    ResponseDTO<?> getDepartmentChecksheets(DepartmentDTO departmentDTO) throws CustomException;

    ResponseDTO<?> getWaitingCount() throws CustomException;

    @Transactional(rollbackFor = Exception.class)
    ResponseDTO<?> deleteChks(Long chksId) throws CustomException;

    ResponseDTO<?> getApprovedNotExpiredChecksheets() throws CustomException;

    ResponseDTO<?> getActiveAuditors() throws CustomException;

    ResponseDTO<?> getActiveAuditeeLocationsByChecksheet(ChecksheetDTO checksheetDTO) throws CustomException;

    @Transactional(rollbackFor = Exception.class)
    ResponseDTO<?> createChecksheetAssignment(ChecksheetDTO checksheetDTO) throws CustomException;

    ResponseDTO<?> checkCodeAvailability(String modelNo, Long excludeId) throws CustomException;
}
