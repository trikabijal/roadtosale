package com.checkSheet.service;

import com.checkSheet.DTO.AttachmentFileDTO;
import com.checkSheet.DTO.DepartmentDTO;
import com.checkSheet.DTO.UserDTO;
import com.checkSheet.constant.EmailTemplate;
import com.checkSheet.entity.*;
import com.checkSheet.exception.CustomException;
import com.checkSheet.helper.FileStorageUtil;
import com.checkSheet.repository.UserRepository;

import com.checkSheet.service.Email.EmailService;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DateUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class UtilityServiceImpl implements UtilityService {
    @Value("${app.environmentDomain}")
    private String environmentDomain;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private EmailService emailService;
    @Override
    public Optional<User> getCurrentLoggedInUser() throws CustomException {
        try {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication == null) {
                throw new CustomException("No authentication found", HttpStatus.UNAUTHORIZED);
            }

            String username = authentication.getName();
            if (username == null || username.trim().isEmpty()) {
                throw new CustomException("No username found in authentication", HttpStatus.UNAUTHORIZED);
            }

            return userRepository.findByUsernameIgnoreCase(username);
        } catch (CustomException e) {
            throw e;
        } catch (Exception e) {
            throw new CustomException("Error getting current user: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
    @Override
    public DepartmentDTO setDepartmentDTO(Department department) {
        DepartmentDTO departmentDTO = new DepartmentDTO();
        departmentDTO.setId(department.getId());
        departmentDTO.setName(department.getName());
        if(department.getDepartmentId() != null) {
            departmentDTO.setDepartmentId(department.getDepartmentId().getId());
            departmentDTO.setDepartmentName(department.getDepartmentId().getName());
        }
        return departmentDTO;
    }

    @Override
    public UserDTO setUserDTO(User user) {
        UserDTO userDTO = new UserDTO();
        userDTO.setId(user.getId());
        return userDTO;
    }

    @Override
    public String getTruncatedFileName(String originalFilename, int maxLength) {
        if (originalFilename == null || originalFilename.isEmpty()) {
            return "";
        }
        String sanitizedFilename = originalFilename
                .replaceAll(" ", "_")
                .replaceAll(",", "_");

        String extension = "";
        int extensionIndex = sanitizedFilename.lastIndexOf('.');
        if (extensionIndex != -1) {
            extension = sanitizedFilename.substring(extensionIndex);
        }

        int baseLength = Math.min(
                sanitizedFilename.length() - extension.length(),
                maxLength - extension.length()
        );

        String truncatedName = sanitizedFilename.substring(0, baseLength);
        return truncatedName + extension;
    }

    @Override
    public String getCellValueAsString(Cell cell) {
        if (cell == null) return "";

        switch (cell.getCellType()) {
            case STRING:
                return cell.getStringCellValue();
            case NUMERIC:
                if (DateUtil.isCellDateFormatted(cell)) {
                    return cell.getLocalDateTimeCellValue().toString();
                }
                return String.valueOf(cell.getNumericCellValue());
            case BOOLEAN:
                return String.valueOf(cell.getBooleanCellValue());
            case FORMULA:
                return cell.getCellFormula();
            default:
                return "";
        }
    }

    @Override
    public void sendEmail(List<Long> userIds, EmailTemplate status, Checksheet chks, List<Long> senderUserIds) throws CustomException {
        try{
            List<User> users = userRepository.findByIdIn(userIds);
            List<User> senderUsers = userRepository.findByIdIn(senderUserIds);
            List<String> senderUserNames = senderUsers.stream().map(u -> u.getFirstName() + " " + u.getLastName()).collect(Collectors.toList());
            for(User user : users) {
                if(!Objects.equals(user.getEmail(), null) && !Objects.equals(user.getEmail(), "")) {
                    String subject = status.getSubject();
                    subject = subject.replace("[CHKS_NAME]",chks.getName());
                    String content = status.getContent();
                    content = content.replace("[ENVIRONMENT_DOMAIN]",environmentDomain);
                    content = content.replace("[NAME]",user.getFirstName() + " " +user.getLastName());
                    content = content.replace("[SENDER_NAMES]",String.join(",", senderUserNames));
                    emailService.sendTextMail(user.getEmail(), subject, content, null, null);
                }
            }
        } catch (Exception e) {
            throw new CustomException("Error getting current user: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    public void sendUserCreationEmail(List<Long> userIds, EmailTemplate emailTemplate, Department dept, List<Long> senderUserIds) throws CustomException {
        try{
            System.out.println("user id : " + userIds);
            List<User> users = userRepository.findByIdIn(userIds);
            List<User> senderUsers = userRepository.findByIdIn(senderUserIds);
            List<String> senderUserNames = senderUsers.stream().map(u -> u.getFirstName() + " " + u.getLastName()).collect(Collectors.toList());
            for(User user : users) {
                if(!Objects.equals(user.getEmail(), null) && !Objects.equals(user.getEmail(), "")) {
                    String subject = emailTemplate.getSubject();
                    subject = subject.replace("[DEPT_NAME]",dept.getName());
                    String content = emailTemplate.getContent();
                    content = content.replace("[DEPT_NAME]",dept.getName());
                    content = content.replace("[ENVIRONMENT_DOMAIN]",environmentDomain);
                    content = content.replace("[NAME]",user.getFirstName() + " " +user.getLastName());
                    content = content.replace("[SENDER_NAMES]",String.join(",", senderUserNames));
                    emailService.sendTextMail(user.getEmail(), subject, content, null, null);
                }
            }
        } catch (Exception e) {
            throw new CustomException("Error getting current user: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    public void sendSurpriseChksEmail(List<Long> userIds, EmailTemplate emailTemplate, Department srcDept, Department dept, SurpriseChecksheetField surpriseChecksheetField, List<Long> senderUserIds, List<AttachmentFileDTO> imageList) throws CustomException {
        try{
            System.out.println("user id : " + userIds);
            List<User> users = userRepository.findByIdIn(userIds);
            List<User> senderUsers = userRepository.findByIdIn(senderUserIds);
            List<String> senderUserNames = senderUsers.stream().map(u -> u.getFirstName() + " " + u.getLastName()).collect(Collectors.toList());
            SurpriseChecksheet surpriseChks = surpriseChecksheetField.getSurpriseChecksheet();
            for(User user : users) {
                if(!Objects.equals(user.getEmail(), null) && !Objects.equals(user.getEmail(), "")) {
                    String subject = emailTemplate.getSubject();

                    subject = subject.replace("[SURPRISE_CHKS_TITLE]",surpriseChks.getName());
                    String content = emailTemplate.getContent();
                    content = content.replace("[DEPT_NAME]",dept.getName());
                    content = content.replace("[SRC_DEPT_NAME]",srcDept.getName());
                    content = content.replace("[CONCERN]",surpriseChecksheetField.getConcern());
                    if(surpriseChecksheetField.getRemarks() != null) {
                        content = content.replace("[REMARKS]", surpriseChecksheetField.getRemarks());
                    }else{
                        content = content.replace("[REMARKS]", "");
                    }
                    content = content.replace("[SURPRISE_CHKS_TITLE]",surpriseChks.getName());
                    content = content.replace("[NAME]",user.getFirstName() + " " +user.getLastName());
                    content = content.replace("[SENDER_NAMES]",String.join(",", senderUserNames));

                    emailService.sendEmailWithAttachement(user.getEmail(), subject, content, imageList);
                }
            }
        } catch (Exception e) {
            throw new CustomException("Error getting current user: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
}
