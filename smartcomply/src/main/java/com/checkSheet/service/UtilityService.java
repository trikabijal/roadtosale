package com.checkSheet.service;

import java.util.List;
import java.util.Optional;

import com.checkSheet.DTO.AttachmentFileDTO;
import com.checkSheet.entity.SurpriseChecksheetField;
import org.apache.poi.ss.usermodel.Cell;

import com.checkSheet.DTO.DepartmentDTO;
import com.checkSheet.DTO.UserDTO;
import com.checkSheet.constant.EmailTemplate;
import com.checkSheet.entity.Checksheet;
import com.checkSheet.entity.Department;
import com.checkSheet.entity.User;
import com.checkSheet.exception.CustomException;

public interface UtilityService {
    Optional<User> getCurrentLoggedInUser() throws CustomException;

    DepartmentDTO setDepartmentDTO(Department department);

    UserDTO setUserDTO(User user);

    String getTruncatedFileName(String originalFilename, int maxLength);

    String getCellValueAsString(Cell cell);

    void sendEmail(List<Long> userIds, EmailTemplate status, Checksheet chks, List<Long> senderUserIds) throws CustomException;
    void sendUserCreationEmail(List<Long> userIds, EmailTemplate emailTemplate, Department dept, List<Long> senderUserIds) throws CustomException;

    void sendSurpriseChksEmail(List<Long> userIds, EmailTemplate emailTemplate,Department srcDept, Department dept, SurpriseChecksheetField surpriseChecksheetField, List<Long> senderUserIds, List<AttachmentFileDTO> imageList) throws CustomException;
}
