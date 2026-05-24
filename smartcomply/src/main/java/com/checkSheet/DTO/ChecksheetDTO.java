package com.checkSheet.DTO;

import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

import com.checkSheet.constant.ChecksheetFrequencyType;
import com.checkSheet.constant.ChecksheetType;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;

import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Data
@Getter
@Setter
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChecksheetDTO {
    private Long id;
    private Long checksheetId;
    private Long auditorId;
    private List<Long> auditeeLocationIds;
    private List<Long> ids;
    private List<Long> auditeeTypeIds;
    private String modelNo;
    private String name;
    private String description;
    private String versionRemark;
    private String status;
    private List<Long> validatorUserIds;
    private List<Long> approverUserIds;
    private List<String> validatorUserUsernames;
    private List<String> approverUserUsernames;
    private Long preparerUserId;
    private Long currentUserId;
    private String preparerUserUsername;
    private List<Long> chksUserIds;
    private List<Long> dataValidatorUserIds;
    private List<Long> dataApproverUserIds;
    private List<Long> operatorUserIds;
    private List<String> chksUserUsernames;
    private List<String> dataValidatorUserUsernames;
    private List<String> dataApproverUserUsernames;
    private Long revision;
    private String serialNumber;
    @JsonFormat(pattern = "dd/MM/yyyy")
    private Date implementationDate;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private Date expiryDate;
    private Date createdAt;
    private Date updatedAt;
    private Long alertToUserId;
    private String alertToUserUsername;
    private Long escalateToUserId;
    private String escalateToUserUsername;
    private Long escalationGuidelinesDays;
    private ChecksheetFrequencyType frequencyOfCheck;
    private Short frequencyOfFreqOfChk;
    private Long departmentId;
    private List<Long> departmentIds;
    private Long currentPage;
    private Long perPageRecord;
    private List<UserDTO> validatorUsers;
    private List<UserDTO> approverUsers;
    private List<UserDTO> dataValidatorUsers;
    private List<UserDTO> dataApproverUsers;
    private List<UserDTO> operatorUsers;
    private UserDTO preparerUser;
    private UserDTO alertToUser;
    private UserDTO escalateToUser;
    private Boolean isValidator;
    private Boolean isApprover;
    private Boolean isDataValidator;
    private Boolean isDataApprover;
    private Boolean isPreparer;
    private Boolean waitingChks = false;
    private Boolean waitingUsrChks = false;
    private Boolean canCreateVersion = true;
    private Boolean canDelete = false;
    private String search;
    private String uid;
    private Boolean isFileUpload;
    private String path;
    private List<String> paths;
    private Long validateOrApproveVersion;
    private List<ChksHeaderDTO> chksHeaders;
    private List<ChksHeaderDataDTO> chksHeaderData;
    private List<ChksGeneralFieldDTO> chksGeneralFields;
    private String assetCode;
    private List<UserChecksheetDTO> userChecksheets;
    private ChecksheetType checksheetType;
    private List<Long> alertToUserIds;
    private List<String> alertToUserUsernames;
    private List<Long> escalateToUserIds;
    private List<String> escalateToUserUsernames;
    private List<String> npdDay;
    private List<UserDTO> alertToUsers;
    private List<UserDTO> escalateToUsers;
    private Long operatorId;
    private String operatorFirstName;
    private String operatorLastName;
    private String operatorUsername;
    private String shift;
    private Date startedAt;
    private Date submittedDate;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private Date startDate;
    @JsonFormat(pattern = "yyyy-MM-dd")
    private Date endDate;
    
    // Extra details fields
    private String departmentName;
    private String sectionName;
    private String userRole;

    //getChecksheets
    public ChecksheetDTO(Long id, String name) {
        this.id = id;
        this.name = name;
    }

    public void setModelNo(String modelNo) {
        this.modelNo = modelNo != null ? modelNo.trim() : null;
    }

    public void setName(String name) {
        this.name = name != null ? name.trim() : null;
    }

    public void setDescription(String description) {
        this.description = description != null ? description.trim() : null;
    }

    public void setStatus(String status) {
        this.status = status != null ? status.trim() : null;
    }

    public void setSerialNumber(String serialNumber) {
        this.serialNumber = serialNumber != null ? serialNumber.trim() : null;
    }

    public void setPreparerUserUsername(String preparerUserUsername) {
        this.preparerUserUsername = preparerUserUsername != null ? preparerUserUsername.trim() : null;
    }

    //getRespectedChecksheet
    public ChecksheetDTO(
        Long id,
        String name,
        String approverUserIds,
        Date createdAt,
        String dataApproverUserIds,
        String dataValidatorUserIds,
        String description,
        Date implementationDate,
        Date expiryDate,
        String modelNo,
        Long revision,
        String serialNumber,
        String status,
        String validatorUserIds,
        Long preparerUserId,
        Long escalationGuidelinesDays,
        String alertToUserIds,
        String escalateToUserIds,
        String frequencyOfCheck,
        String uid,
        String operatorUserIds,
        Boolean isFileUpload
    ) {
        this.id = id;
        this.name = name;
        this.approverUserIds = convertStringToList(approverUserIds);
        this.createdAt = createdAt;
        this.dataApproverUserIds = convertStringToList(dataApproverUserIds);
        this.dataValidatorUserIds = convertStringToList(dataValidatorUserIds);
        this.description = description;
        this.implementationDate = implementationDate;
        this.expiryDate = expiryDate;
        this.modelNo = modelNo;
        this.revision = revision;
        this.serialNumber = serialNumber;
        this.status = status;
        this.validatorUserIds = convertStringToList(validatorUserIds);
        this.preparerUserId = preparerUserId;
        this.escalationGuidelinesDays = escalationGuidelinesDays;
        this.alertToUserIds = convertStringToList(alertToUserIds);
        this.escalateToUserIds = convertStringToList(escalateToUserIds);
        this.frequencyOfCheck = frequencyOfCheck != null ? ChecksheetFrequencyType.valueOf(frequencyOfCheck) : null;
        this.uid = uid;
        this.operatorUserIds = convertStringToList(operatorUserIds);
        this.isFileUpload = isFileUpload;
    }

    //getRespectedUserChecksheet
    public ChecksheetDTO(
        Long id,
        String name,
        String approverUserIds,
        Date createdAt,
        String dataApproverUserIds,
        String dataValidatorUserIds,
        String description,
        Date implementationDate,
        String modelNo,
        Long revision,
        String serialNumber,
        String status,
        String validatorUserIds,
        Long preparerUserId,
        Long escalationGuidelinesDays,
        String alertToUserIds,
        String escalateToUserIds,
        String frequencyOfCheck,
        String uid,
        String operatorUserIds,
        Boolean isFileUpload,
        Long operatorId,
        String operatorFirstName,
        String operatorLastName,
        String operatorUsername,
        Date startedAt,
        Date submittedDate,
        String shift
    ) {
        this.id = id;
        this.name = name;
        this.approverUserIds = convertStringToList(approverUserIds);
        this.createdAt = createdAt;
        this.dataApproverUserIds = convertStringToList(dataApproverUserIds);
        this.dataValidatorUserIds = convertStringToList(dataValidatorUserIds);
        this.description = description;
        this.implementationDate = implementationDate;
        this.modelNo = modelNo;
        this.revision = revision;
        this.serialNumber = serialNumber;
        this.status = status;
        this.validatorUserIds = convertStringToList(validatorUserIds);
        this.preparerUserId = preparerUserId;
        this.escalationGuidelinesDays = escalationGuidelinesDays;
        this.alertToUserIds = convertStringToList(alertToUserIds);
        this.escalateToUserIds = convertStringToList(escalateToUserIds);
        this.frequencyOfCheck = frequencyOfCheck != null ? ChecksheetFrequencyType.valueOf(frequencyOfCheck) : null;
        this.uid = uid;
        this.operatorUserIds = convertStringToList(operatorUserIds);
        this.isFileUpload = isFileUpload;
        this.operatorId = operatorId;
        this.operatorFirstName = operatorFirstName;
        this.operatorLastName = operatorLastName;
        this.operatorUsername = operatorUsername;
        this.startedAt = startedAt;
        this.submittedDate = submittedDate;
        this.shift = shift;
    }

    //getRespectedChecksheetDetail
    public ChecksheetDTO(
        Long id,
        String name,
        String approverUserIds,
        Date createdAt,
        String dataApproverUserIds,
        String dataValidatorUserIds,
        String description,
        Date implementationDate,
        Date expiryDate,
        String modelNo,
        Long revision,
        String serialNumber,
        String status,
        String validatorUserIds,
        Long preparerUserId,
        Long escalationGuidelinesDays,
        String alertToUserIds,
        String escalateToUserIds,
        String frequencyOfCheck,
        Short frequencyOfFreqOfChk,
        String operatorUserIds,
        Long departmentId,
        Boolean isFileUpload,
        String assetCode,
        ChecksheetType checksheetType,
        String uid,
        String versionRemark
    ) {
        this.id = id;
        this.name = name;
        this.approverUserIds = convertStringToList(approverUserIds);
        this.createdAt = createdAt;
        this.dataApproverUserIds = convertStringToList(dataApproverUserIds);
        this.dataValidatorUserIds = convertStringToList(dataValidatorUserIds);
        this.description = description;
        this.implementationDate = implementationDate;
        this.expiryDate = expiryDate;
        this.modelNo = modelNo;
        this.revision = revision;
        this.serialNumber = serialNumber;
        this.status = status;
        this.validatorUserIds = convertStringToList(validatorUserIds);
        this.preparerUserId = preparerUserId;
        this.escalationGuidelinesDays = escalationGuidelinesDays;
        this.alertToUserIds = convertStringToList(alertToUserIds);
        this.escalateToUserIds = convertStringToList(escalateToUserIds);
        this.frequencyOfCheck = frequencyOfCheck != null ? ChecksheetFrequencyType.valueOf(frequencyOfCheck) : null;
        this.frequencyOfFreqOfChk = frequencyOfFreqOfChk;
        this.operatorUserIds = convertStringToList(operatorUserIds);
        this.departmentId = departmentId;
        this.isFileUpload = isFileUpload;
        this.assetCode = assetCode;
        this.checksheetType = checksheetType;
        this.uid = uid;
        this.versionRemark= versionRemark;
    }


    public ChecksheetDTO(
            Long id,
            String name,
            String approverUserIds
//            List<Long> chksUserIds,
//            Date createdAt,
//            List<Long> dataApproverUserIds
    ) {
        this.id = id;
        this.name = name;
        this.approverUserIds = convertStringToList(approverUserIds);
//        this.chksUserIds = chksUserIds;
//        this.createdAt = createdAt;
//        this.dataApproverUserIds = dataApproverUserIds;
//        this.dataValidatorUserIds = dataValidatorUserIds;
//        this.description = description;
//        this.implementationDate = implementationDate;
//        this.modelNo = modelNo;
//        this.revision = revision;
//        this.serialNumber = serialNumber;
//        this.status = status;
//        this.validatorUserIds = validatorUserIds;
//        this.preparerUserId = preparerUserId;
//        this.escalationGuidelinesDays = escalationGuidelinesDays;
//        this.alertToUserId = alertToUserId;
//        this.escalateToUserId = escalateToUserId;
//        this.frequencyOfCheck = frequencyOfCheck != null ? ChecksheetFrequencyType.valueOf(frequencyOfCheck) : null;
//        this.operator = operator;
    }

    private List<Long> convertStringToList(String approverUserIds) {
        try {
            if (approverUserIds == null || approverUserIds.isEmpty()) {
                return List.of();
            }
            return Arrays.stream(approverUserIds.replaceAll("[{}\"]", "").trim().split(","))
                    .filter(s -> !s.isEmpty())
                    .map(String::trim)
                    .map(Long::parseLong)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            e.printStackTrace();
            return List.of();
        }
    }

    public ChecksheetDTO(
        Long id,
        String name,
        String modelNo,
        String frequencyOfCheck,
        Short frequencyOfFreqOfChk,
        String uid,
        Date expiryDate
    ) {
        this.id = id;
        this.name = name;
        this.modelNo = modelNo;
        this.uid = uid;
        this.frequencyOfCheck = frequencyOfCheck != null ? ChecksheetFrequencyType.valueOf(frequencyOfCheck) : null;
        this.frequencyOfFreqOfChk = frequencyOfFreqOfChk;
        this.expiryDate = expiryDate;
    }
}
