package com.checkSheet.service;

import com.checkSheet.DAO.*;
import com.checkSheet.DTO.*;
import com.checkSheet.DTO.response.ResponseDTO;
import com.checkSheet.constant.*;
import com.checkSheet.entity.*;
import com.checkSheet.exception.CustomException;
import com.checkSheet.helper.FileStorageUtil;
import com.checkSheet.repository.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.net.URL;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class ChecksheetServiceImpl implements ChecksheetService {
    @Autowired
    private ChecksheetRepository checksheetRepository;

    @Autowired
    private ChksHeaderDAO chksHeaderDAO;
    @Autowired
    private UserChecksheetRepository userChecksheetRepository;
    @Autowired
    private LovDataDAO lovDataDAO;
    @Autowired
    private ChecksheetDAO checksheetDAO;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserRoleDepartmentRepository userRoleDepartmentRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private UtilityService utilityService;

    @Autowired
    private ChksHeaderDataService chksHeaderDataService;

    @Autowired
    private UserDAO userDAO;

    @Autowired
    private DepartmentRepository departmentRepository;

    @Autowired
    private DepartmentDAO departmentDAO;

    @Autowired
    private ChksHeaderRepository chksHeaderRepository;

    @Autowired
    private ChksHeaderDataRepository chksHeaderDataRepository;

    @Autowired
    private ChksHeaderDataFileRepository chksHeaderDataFileRepository;

    @Autowired
    private ChksQuestionRepository chksQuestionRepository;

    @Autowired
    private ChksQuestionFileRepository chksQuestionFileRepository;

    @Autowired
    private ChksQuestionResultRepository chksQuestionResultRepository;

    @Autowired
    private ChksQuestionResultOptionRepository chksQuestionResultOptionRepository;

    @Autowired
    private ChksQuestionResultMatrixRepository chksQuestionResultMatrixRepository;

    @Autowired
    private ChksGeneralFieldRepository chksGeneralFieldRepository;

    @Autowired
    private AWSS3Service awss3Service;

    @Autowired
    private ChecksheetValidationRepository checksheetValidationRepository;
    @Autowired
    private ChecksheetValidationHistoryRepository checksheetValidationHistoryRepository;

    @Autowired
    private ChecksheetApprovalRepository checksheetApprovalRepository;

    @Autowired
    private ChecksheetApprovalHistoryRepository checksheetApprovalHistoryRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private PermissionService permissionService;

    @Autowired
    private ChecksheetAuditeeTypeRepository checksheetAuditeeTypeRepository;

    @Autowired
    private AuditeeTypeRepository auditeeTypeRepository;

    @Autowired
    private AuditeeLocationTypeRepository auditeeLocationTypeRepository;

    @Autowired
    private AuditeeLocationRepository auditeeLocationRepository;

    @Autowired
    private ChecksheetAssignmentRepository checksheetAssignmentRepository;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseDTO<?> createChecksheet(ChecksheetDTO checksheetDTO) throws CustomException {
        try {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            String username = authentication.getName();
            Optional<User> loginUser = userRepository.findByUsernameIgnoreCase(username);
            Optional<Role> loginUserPreparerRole = roleRepository.findByRoleCode("SUBDEPT_ADMIN");
            Checksheet checksheet = new Checksheet();
            boolean isExist = false;
            boolean isEditable = true;
            Optional<Checksheet> oldChecksheet = Optional.empty();
            if(!Objects.equals(checksheetDTO.getId(), null)) {
                oldChecksheet = checksheetRepository.findById(checksheetDTO.getId());
                if(oldChecksheet.isEmpty()) {
                    throw new CustomException("Please provide valid id", HttpStatus.UNPROCESSABLE_ENTITY);
                } else {
                    checksheet = oldChecksheet.get();
                    isExist = true;
                    isEditable = !Objects.equals(checksheet.getStatus(), ChecksheetStatusType.APPROVED);
                }
            }
            if(Objects.nonNull(checksheetDTO.getId())) {
                Optional<UserRoleDepartment> preparerUserRoleByUserIdDepartment = userRoleDepartmentRepository.findByUser_IdAndRole_IdAndDepartmentId_IdAndDeletedByIsNull(
                        loginUser.get().getId(), loginUserPreparerRole.get().getId(), checksheet.getDepartment().getId());
                if (preparerUserRoleByUserIdDepartment.isPresent()) {
                    if (Objects.isNull(checksheetDTO.getId()) ||
                            (Objects.equals(checksheetDTO.getOperatorUserIds(), null) || checksheetDTO.getOperatorUserIds().isEmpty())) {
                        throw new CustomException("Please provide id, opertorUserIds", HttpStatus.UNPROCESSABLE_ENTITY);
                    }

                    Department section = departmentRepository.findById(oldChecksheet.get().getDepartment().getId()).get();
                    if (Objects.nonNull(checksheetDTO.getOperatorUserIds())) {
                        for (Long operatorUserUsername : checksheetDTO.getOperatorUserIds()) {
                            Optional<User> tempOperatorUser = userRepository.findById(operatorUserUsername);
                            if (tempOperatorUser.isEmpty()) {
                                continue;
                            }
                            Optional<Role> role = roleRepository.findByRoleCode("OPERATOR");
                            if (role.isPresent()) {
                                Optional<UserRoleDepartment> userRoleDepartmentByUserIdAndRoleIdAndDepartmentId = userRoleDepartmentRepository.findByUser_IdAndRole_IdAndDepartment_Id(tempOperatorUser.get().getId(), role.get().getId(),
                                        section.getId());
                                if (userRoleDepartmentByUserIdAndRoleIdAndDepartmentId.isEmpty()) {
                                    UserRoleDepartment userRoleDepartment = new UserRoleDepartment();
                                    userRoleDepartment.setUser(tempOperatorUser.get());
                                    userRoleDepartment.setRole(role.get());
                                    userRoleDepartment.setDepartmentId(section);
                                    userRoleDepartmentRepository.save(userRoleDepartment);
                                } else {
                                    userRoleDepartmentByUserIdAndRoleIdAndDepartmentId.get().setDeletedBy(null);
                                    userRoleDepartmentByUserIdAndRoleIdAndDepartmentId.get().setDeletedAt(null);
                                    userRoleDepartmentRepository.save(userRoleDepartmentByUserIdAndRoleIdAndDepartmentId.get());
                                }
                            }
                        }
                        checksheet.setOperatorUserIds(checksheetDTO.getOperatorUserIds());
                        checksheet.setUpdatedBy(loginUser.get());
                        checksheetRepository.save(checksheet);
                    }
                }
            }
            //                    Objects.equals(checksheetDTO.getDataValidatorUserUsernames(), null) || checksheetDTO.getDataValidatorUserUsernames().isEmpty() ||
            //                    Objects.equals(checksheetDTO.getDataApproverUserUsernames(), null) || checksheetDTO.getDataApproverUserUsernames().isEmpty() ||
            //                    Objects.equals(checksheetDTO.getOperatorUserIds(), null) || checksheetDTO.getOperatorUserIds().isEmpty() ||
            if(Objects.equals(checksheetDTO.getPreparerUserUsername(), null) || checksheetDTO.getPreparerUserUsername().trim().isEmpty() || Objects.equals(checksheetDTO.getDepartmentId(), null) || Objects.equals(checksheetDTO.getValidatorUserUsernames(), null) || checksheetDTO.getValidatorUserUsernames().isEmpty() || Objects.equals(checksheetDTO.getApproverUserUsernames(), null) || checksheetDTO.getApproverUserUsernames().isEmpty() || Objects.equals(checksheetDTO.getEscalateToUserUsernames(), null) || checksheetDTO.getEscalateToUserUsernames().isEmpty() || Objects.equals(checksheetDTO.getAlertToUserUsernames(), null) || checksheetDTO.getAlertToUserUsernames().isEmpty()
            ) {
                throw new CustomException("Please provide departmentId, preparerUserId, validatorUserIds, approverUserIds," +
                        "dataValidatorUserIds, dataApproverUserIds, escalateToUserUsernames, alertToUserUsernames", HttpStatus.UNPROCESSABLE_ENTITY);
            }else if(!checksheetDTO.getFrequencyOfCheck().equals(ChecksheetFrequencyType.UNPLANNED) && (checksheetDTO.getFrequencyOfFreqOfChk() == null || checksheetDTO.getFrequencyOfFreqOfChk() < 1)){
                throw new CustomException("Please, provide valid frequency of frequency.", HttpStatus.UNPROCESSABLE_ENTITY);
            }
            if(Objects.isNull(checksheetDTO.getId()) &&
                    (Objects.equals(checksheetDTO.getName(), null) || checksheetDTO.getName().trim().isEmpty())) {
                throw new CustomException("Please provide name", HttpStatus.UNPROCESSABLE_ENTITY);
            }
            if(!Objects.isNull(checksheetDTO.getId()) &&
                    (Objects.equals(checksheetDTO.getOperatorUserIds(), null) || checksheetDTO.getOperatorUserIds().isEmpty())) {
                throw new CustomException("Please provide opertorUserIds", HttpStatus.UNPROCESSABLE_ENTITY);
            }
            // Check if user has permission to create/edit checksheet
            if(!permissionService.hasPermission(loginUser.get().getId(), "CHECKSHEET_MANAGEMENT_DETAIL_CREATE") &&
               !permissionService.hasPermission(loginUser.get().getId(), "CHECKSHEET_MANAGEMENT_DETAIL_EDIT")) {
                throw new CustomException("You do not have permission to create or edit checksheets", HttpStatus.FORBIDDEN);
            }
            /*Checksheet checksheet = new Checksheet();
            Boolean isExist = false;
            Boolean isEditable = true;
            Optional<Checksheet> oldChecksheet = Optional.empty();
            if(!Objects.equals(checksheetDTO.getId(), null)) {
                oldChecksheet = checksheetRepository.findById(checksheetDTO.getId());
                if(oldChecksheet.isEmpty()) {
                    throw new CustomException("Please provide valid id", HttpStatus.UNPROCESSABLE_ENTITY);
                } else {
                    checksheet = oldChecksheet.get();
                    isExist = true;
                    isEditable = !Objects.equals(checksheet.getStatus(), ChecksheetStatusType.APPROVED);
                }
            }*/
            Department section = departmentRepository.findById(checksheetDTO.getDepartmentId()).get();
            checksheet.setDescription(checksheetDTO.getDescription());
            checksheet.setDepartment(section);
            checksheet.setAssetCode(checksheetDTO.getAssetCode());
            checksheet.setChecksheetType(checksheetDTO.getChecksheetType());
            if(!Objects.equals(checksheetDTO.getId(), null)) {
                checksheet.setUpdatedBy(loginUser.get());
            } else {
                Department department = section.getDepartmentId();
                if(department == null){
                    throw new CustomException("No Department found for the logged in user!!", HttpStatus.UNPROCESSABLE_ENTITY);
                }
                long chksCount = checksheetRepository.countChksInDepartment(department.getId());
                String UID = "IMS-"+department.getName()+"-F" + String.format("%02d", chksCount+1);
                checksheet.setUid(UID);
                checksheet.setCreatedBy(loginUser.get());
                checksheet.setStatus(ChecksheetStatusType.NEW);
            }
            if(isEditable) {
                // Validate uniqueness of checksheet code (modelNo) when provided
                String newModelNo = checksheetDTO.getModelNo();
                if (newModelNo != null && !newModelNo.trim().isEmpty()) {
                    boolean codeTaken = isExist
                        ? checksheetRepository.existsByModelNoIgnoreCaseAndIdNot(newModelNo.trim(), checksheetDTO.getId())
                        : checksheetRepository.existsByModelNoIgnoreCase(newModelNo.trim());
                    if (codeTaken) {
                        throw new CustomException(
                            "Checksheet code '" + newModelNo.trim() + "' is already in use",
                            HttpStatus.CONFLICT);
                    }
                }
                checksheet.setModelNo(checksheetDTO.getModelNo());
                checksheet.setName(checksheetDTO.getName());
                Optional<User> preparorUser = userRepository.findByUsernameIgnoreCase(checksheetDTO.getPreparerUserUsername());
                if (preparorUser.isEmpty()) {
                    throw new CustomException("Please provide valid preparer User", HttpStatus.UNPROCESSABLE_ENTITY);
                }
                Optional<Role> preparerRole = roleRepository.findByRoleCode("SUBDEPT_ADMIN");
                Optional<UserRoleDepartment> preparerUserRoleDepartmentByUserIdAndRoleIdAndDepartmentIdAndDeletedByIsNull = userRoleDepartmentRepository.findByUser_IdAndRole_IdAndDepartment_IdAndDeletedByIsNull(preparorUser.get().getId(), preparerRole.get().getId(),
                        section.getId());
                if (!preparerUserRoleDepartmentByUserIdAndRoleIdAndDepartmentIdAndDeletedByIsNull.isPresent()) {
                    UserRoleDepartment userRoleDepartment = new UserRoleDepartment();
                    userRoleDepartment.setUser(preparorUser.get());
                    userRoleDepartment.setRole(preparerRole.get());
                    userRoleDepartment.setDepartmentId(section);
                    userRoleDepartmentRepository.save(userRoleDepartment);
                }
                checksheet.setPreparerUser(preparorUser.get());

                // Process new validator users
                List<Long> validatorUserIds = new ArrayList<>();
                List<Long> existingValidatorUserIds = checksheet.getValidatorUserIds();
                for (String validatorUserUsername: checksheetDTO.getValidatorUserUsernames()) {
                    Optional<User> tempvalidatorUser = userRepository.findByUsernameIgnoreCase(validatorUserUsername);
                    if(tempvalidatorUser.isEmpty()) {
                        throw new CustomException("Please provide valid validator User", HttpStatus.UNPROCESSABLE_ENTITY);
                    }
                    Optional<Role> role = roleRepository.findByRoleCode("SUBDEPT_ADMIN");
                    if(role.isPresent()) {
                        Optional<UserRoleDepartment> userRoleDepartmentByUserIdAndRoleIdAndDepartmentIdAndDeletedByIsNull = userRoleDepartmentRepository.findByUser_IdAndRole_IdAndDepartment_IdAndDeletedByIsNull(tempvalidatorUser.get().getId(), role.get().getId(),
                                section.getId());
                        if(userRoleDepartmentByUserIdAndRoleIdAndDepartmentIdAndDeletedByIsNull.isEmpty()) {
                            UserRoleDepartment userRoleDepartment = new UserRoleDepartment();
                            userRoleDepartment.setUser(tempvalidatorUser.get());
                            userRoleDepartment.setRole(role.get());
                            userRoleDepartment.setDepartmentId(section);
                            userRoleDepartmentRepository.save(userRoleDepartment);
                        }
                    }
                    validatorUserIds.add(tempvalidatorUser.get().getId());
                }

                // Handle removed validator users if this is an edit operation
                if (isExist && existingValidatorUserIds != null) {
                    for (Long existingValidatorId : existingValidatorUserIds) {
                        if (!validatorUserIds.contains(existingValidatorId)) {
                            if (!isUserAssignedToOtherChecksheets(
                                    existingValidatorId,
                                    section.getId(),
                                    checksheet.getId())) {
                                Optional<Role> validatorRole = roleRepository.findByRoleCode("SUBDEPT_ADMIN");
                                if (validatorRole.isPresent()) {
                                    Optional<UserRoleDepartment> userRoleDepartment = userRoleDepartmentRepository
                                            .findByUser_IdAndRole_IdAndDepartment_Id(
                                                    existingValidatorId,
                                                    validatorRole.get().getId(),
                                                    section.getId()
                                            );

                                    userRoleDepartment.ifPresent(roleDepartment -> userRoleDepartmentRepository.delete(roleDepartment));
                                }
                            }
                        }
                    }
                }
                checksheet.setValidatorUserIds(validatorUserIds);

                // Process new approver users
                List<Long> approverUserIds = new ArrayList<>();
                List<Long> existingApproverUserIds = checksheet.getApproverUserIds();
                for (String approverUserUsername : checksheetDTO.getApproverUserUsernames()) {
                    Optional<User> tempApproverUser = userRepository.findByUsernameIgnoreCase(approverUserUsername);
                    if (!tempApproverUser.isPresent()) {
                        throw new CustomException("Please provide valid approver User", HttpStatus.UNPROCESSABLE_ENTITY);
                    }
                    Optional<Role> role = roleRepository.findByRoleCode("SUBDEPT_ADMIN");
                    if (role.isPresent()) {
                        Optional<UserRoleDepartment> userRoleDepartmentByUserIdAndRoleIdAndDepartmentIdAndDeletedByIsNull =
                                userRoleDepartmentRepository.findByUser_IdAndRole_IdAndDepartment_IdAndDeletedByIsNull(
                                        tempApproverUser.get().getId(),
                                        role.get().getId(),
                                        section.getId()
                                );
                        if (!userRoleDepartmentByUserIdAndRoleIdAndDepartmentIdAndDeletedByIsNull.isPresent()) {
                            UserRoleDepartment userRoleDepartment = new UserRoleDepartment();
                            userRoleDepartment.setUser(tempApproverUser.get());
                            userRoleDepartment.setRole(role.get());
                            userRoleDepartment.setDepartmentId(section);
                            userRoleDepartmentRepository.save(userRoleDepartment);
                        }
                    }
                    approverUserIds.add(tempApproverUser.get().getId());
                }

                // Handle removed approver users if this is an edit operation
                if (isExist && existingApproverUserIds != null) {
                    for (Long existingApproverId : existingApproverUserIds) {
                        if (!approverUserIds.contains(existingApproverId)) {
                            if (!isApproverAssignedToOtherChecksheets(
                                    existingApproverId,
                                    section.getId(),
                                    checksheet.getId())) {
                                Optional<Role> approverRole = roleRepository.findByRoleCode("SUBDEPT_ADMIN");
                                if (approverRole.isPresent()) {
                                    Optional<UserRoleDepartment> userRoleDepartment = userRoleDepartmentRepository
                                            .findByUser_IdAndRole_IdAndDepartment_Id(
                                                    existingApproverId,
                                                    approverRole.get().getId(),
                                                    section.getId()
                                            );

                                    userRoleDepartment.ifPresent(roleDepartment -> userRoleDepartmentRepository.delete(roleDepartment));
                                }
                            }
                        }
                    }
                }
                checksheet.setApproverUserIds(approverUserIds);

            }
            if(Objects.nonNull(checksheetDTO.getOperatorUserIds())) {
                List<User> userByIdIn = userRepository.findByIdIn(checksheetDTO.getOperatorUserIds());
                if (userByIdIn.size() != checksheetDTO.getOperatorUserIds().size()) {
                    throw new CustomException("Please provide valid operator", HttpStatus.UNPROCESSABLE_ENTITY);
                }
                checksheet.setOperatorUserIds(checksheetDTO.getOperatorUserIds());
            } else {
                checksheet.setOperatorUserIds(new ArrayList<>());
            }

            List<Long> dataValidatorUserIds = new ArrayList<>();
            List<Long> existingDataValidatorUserIds = checksheet.getDataValidatorUserIds();

            // Process new data validator users
            for (String dataValidatorUserUsername : checksheetDTO.getDataValidatorUserUsernames()) {
                Optional<User> tempDataValidatorUser = userRepository.findByUsernameIgnoreCase(dataValidatorUserUsername);
                if (tempDataValidatorUser.isEmpty()) {
                    throw new CustomException("Please provide valid data validator User", HttpStatus.UNPROCESSABLE_ENTITY);
                }
                Optional<Role> role = roleRepository.findByRoleCode("DEPT_ADMIN");
                if (role.isPresent()) {
                    Optional<UserRoleDepartment> userRoleDepartmentByUserIdAndRoleIdAndDepartmentIdAndDeletedByIsNull =
                            userRoleDepartmentRepository.findByUser_IdAndRole_IdAndDepartment_IdAndDeletedByIsNull(
                                    tempDataValidatorUser.get().getId(),
                                    role.get().getId(),
                                    section.getId()
                            );
                    if (userRoleDepartmentByUserIdAndRoleIdAndDepartmentIdAndDeletedByIsNull.isEmpty()) {
                        UserRoleDepartment userRoleDepartment = new UserRoleDepartment();
                        userRoleDepartment.setUser(tempDataValidatorUser.get());
                        userRoleDepartment.setRole(role.get());
                        userRoleDepartment.setDepartmentId(section);
                        userRoleDepartmentRepository.save(userRoleDepartment);
                    }
                }
                dataValidatorUserIds.add(tempDataValidatorUser.get().getId());
            }

            // Handle removed data validator users if this is an edit operation
            if (isExist && existingDataValidatorUserIds != null) {
                for (Long existingDataValidatorId : existingDataValidatorUserIds) {
                    if (!dataValidatorUserIds.contains(existingDataValidatorId)) {
                        if (!isDataValidatorAssignedToOtherChecksheets(
                                existingDataValidatorId,
                                section.getId(),
                                checksheet.getId())) {
                            Optional<Role> dataValidatorRole = roleRepository.findByRoleCode("DEPT_ADMIN");
                            if (dataValidatorRole.isPresent()) {
                                Optional<UserRoleDepartment> userRoleDepartment = userRoleDepartmentRepository
                                        .findByUser_IdAndRole_IdAndDepartment_Id(
                                                existingDataValidatorId,
                                                dataValidatorRole.get().getId(),
                                                section.getId()
                                        );

                                userRoleDepartment.ifPresent(roleDepartment -> userRoleDepartmentRepository.delete(roleDepartment));
                            }
                        }
                    }
                }
            }
            checksheet.setDataValidatorUserIds(dataValidatorUserIds);


            List<Long> dataApproverUserIds = new ArrayList<>();
            List<Long> existingDataApproverUserIds = checksheet.getDataApproverUserIds();

            // Process new data approver users
            for (String dataApproverUserUsername : checksheetDTO.getDataApproverUserUsernames()) {
                Optional<User> tempDataApproverUser = userRepository.findByUsernameIgnoreCase(dataApproverUserUsername);
                if (tempDataApproverUser.isEmpty()) {
                    throw new CustomException("Please provide valid data approver User", HttpStatus.UNPROCESSABLE_ENTITY);
                }
                Optional<Role> role = roleRepository.findByRoleCode("DEPT_ADMIN");
                if (role.isPresent()) {
                    Optional<UserRoleDepartment> userRoleDepartmentByUserIdAndRoleIdAndDepartmentIdAndDeletedByIsNull =
                            userRoleDepartmentRepository.findByUser_IdAndRole_IdAndDepartment_IdAndDeletedByIsNull(
                                    tempDataApproverUser.get().getId(),
                                    role.get().getId(),
                                    section.getId()
                            );
                    if (userRoleDepartmentByUserIdAndRoleIdAndDepartmentIdAndDeletedByIsNull.isEmpty()) {
                        UserRoleDepartment userRoleDepartment = new UserRoleDepartment();
                        userRoleDepartment.setUser(tempDataApproverUser.get());
                        userRoleDepartment.setRole(role.get());
                        userRoleDepartment.setDepartmentId(section);
                        userRoleDepartmentRepository.save(userRoleDepartment);
                    }
                }
                dataApproverUserIds.add(tempDataApproverUser.get().getId());
            }

            // Handle removed data approver users if this is an edit operation
            if (isExist && existingDataApproverUserIds != null) {
                for (Long existingDataApproverId : existingDataApproverUserIds) {
                    if (!dataApproverUserIds.contains(existingDataApproverId)) {
                        if (!isDataApproverAssignedToOtherChecksheets(
                                existingDataApproverId,
                                section.getId(),
                                checksheet.getId())) {
                            Optional<Role> dataApproverRole = roleRepository.findByRoleCode("DEPT_ADMIN");
                            if (dataApproverRole.isPresent()) {
                                Optional<UserRoleDepartment> userRoleDepartment = userRoleDepartmentRepository
                                        .findByUser_IdAndRole_IdAndDepartment_Id(
                                                existingDataApproverId,
                                                dataApproverRole.get().getId(),
                                                section.getId()
                                        );

                                userRoleDepartment.ifPresent(roleDepartment -> userRoleDepartmentRepository.delete(roleDepartment));
                            }
                        }
                    }
                }
            }
            checksheet.setDataApproverUserIds(dataApproverUserIds);


            List<Long> operatorUserIds = new ArrayList<>();
            if(Objects.nonNull(checksheetDTO.getOperatorUserIds())) {
                for (Long operatorUserUsername : checksheetDTO.getOperatorUserIds()) {
                    Optional<User> tempOperatorUser = userRepository.findById(operatorUserUsername);
                    if (tempOperatorUser.isEmpty()) {
                        continue;
                    }
                    Optional<Role> role = roleRepository.findByRoleCode("OPERATOR");
                    if (role.isPresent()) {
                        Optional<UserRoleDepartment> userRoleDepartmentByUserIdAndRoleIdAndDepartmentId = userRoleDepartmentRepository.findByUser_IdAndRole_IdAndDepartment_Id(tempOperatorUser.get().getId(), role.get().getId(),
                                section.getId());
                        if (userRoleDepartmentByUserIdAndRoleIdAndDepartmentId.isEmpty()) {
                            UserRoleDepartment userRoleDepartment = new UserRoleDepartment();
                            userRoleDepartment.setUser(tempOperatorUser.get());
                            userRoleDepartment.setRole(role.get());
                            userRoleDepartment.setDepartmentId(section);
                            userRoleDepartmentRepository.save(userRoleDepartment);
                        } else {
                            userRoleDepartmentByUserIdAndRoleIdAndDepartmentId.get().setDeletedBy(null);
                            userRoleDepartmentByUserIdAndRoleIdAndDepartmentId.get().setDeletedAt(null);
                            userRoleDepartmentRepository.save(userRoleDepartmentByUserIdAndRoleIdAndDepartmentId.get());
                        }
                    }
                    operatorUserIds.add(tempOperatorUser.get().getId());
                }
                checksheet.setOperatorUserIds(checksheetDTO.getOperatorUserIds());
            } else {
                checksheet.setOperatorUserIds(new ArrayList<>());
            }
            checksheet.setFrequencyOfCheck(checksheetDTO.getFrequencyOfCheck());
            checksheet.setFrequencyOfFreqOfChk(checksheetDTO.getFrequencyOfFreqOfChk());

            handleEscalateUsers(checksheetDTO, checksheet);
            handleAlertUsers(checksheetDTO, checksheet);
            checksheet.setVersion(0L);
            checksheet.setImplementationDate(checksheetDTO.getImplementationDate());
            checksheet.setEscalationGuidelinesDays(checksheetDTO.getEscalationGuidelinesDays());
            String successMsg;
            if(isExist) {
                successMsg = "Checksheet updated successfully";
            }else{
                checksheet.setWaitingUserIds(List.of(checksheet.getPreparerUser().getId()));
                successMsg = "Checksheet created successfully";
                //Send mail to preparor
                utilityService.sendEmail(List.of(checksheet.getPreparerUser().getId()), EmailTemplate.NEW, checksheet, List.of(loginUser.get().getId()));
            }
            checksheetRepository.save(checksheet);
            saveChecksheetAuditeeTypes(checksheet, checksheetDTO.getAuditeeTypeIds(), loginUser.get());
            return new ResponseDTO<>(true, successMsg);
        } catch (CustomException ce) {
            throw ce;
        } catch (Exception e) {
            e.printStackTrace();
            throw new CustomException(e.getMessage(), HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    private User createNewUserFromUsername(String username) {
        User user = new User();
        String cleanUsername = username.trim().toLowerCase().replaceAll("\\s+", "");
        user.setUsername(username);
        user.setEmail(cleanUsername + "@gmail.com");
        user.setFirstName(username);
        user.setLastName(username);
        user.setPassword(passwordEncoder.encode("12345678"));
        return user;
    }

    @Override
    public byte[] downloadFile(ChecksheetDTO checksheetDTO) throws CustomException {
        try {
            if(Objects.isNull(checksheetDTO) || Objects.isNull(checksheetDTO.getPath())) {
                throw new CustomException("Please provide path", HttpStatus.UNPROCESSABLE_ENTITY);
            }
            return awss3Service.downloadFileFromS3(checksheetDTO.getPath());
        } catch (CustomException ce) {
            throw ce;
        } catch(Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    @Override
    public ResponseDTO<?> getS3FileURL(ChecksheetDTO checksheetDTO) throws CustomException {
        try {
            if(Objects.isNull(checksheetDTO) || Objects.isNull(checksheetDTO.getPath())) {
                throw new CustomException("Please provide path", HttpStatus.UNPROCESSABLE_ENTITY);
            }
            URL docs = awss3Service.getDocs(checksheetDTO.getPath());
            return new ResponseDTO<>(true, "URL has been fetched", docs);
        } catch (CustomException ce) {
            throw ce;
        } catch(Exception e) {
            e.printStackTrace();
            throw new CustomException(e.getMessage(), HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    @Override
    public ResponseDTO<?> getFileURL(ChecksheetDTO checksheetDTO) throws CustomException {
        try {
            if(Objects.isNull(checksheetDTO) || Objects.isNull(checksheetDTO.getPath())) {
                throw new CustomException("Please provide path", HttpStatus.UNPROCESSABLE_ENTITY);
            }
//            URL docs = awss3Service.getDocs(checksheetDTO.getPath());
            return new ResponseDTO<>(true, "URL has been fetched", FileStorageUtil.getFileURL(checksheetDTO.getPath()));
        } catch (CustomException ce) {
            throw ce;
        } catch(Exception e) {
            e.printStackTrace();
            throw new CustomException(e.getMessage(), HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    @Override
    public ResponseDTO<?> getS3FilesURL(ChecksheetDTO checksheetDTO) throws CustomException {
        try {
            if(Objects.isNull(checksheetDTO) || Objects.isNull(checksheetDTO.getPaths()) || checksheetDTO.getPaths().isEmpty()) {
                throw new CustomException("Please provide paths", HttpStatus.UNPROCESSABLE_ENTITY);
            }
            List<String> allPaths = new ArrayList<>();
            for(String path : checksheetDTO.getPaths()) {
                URL docs = awss3Service.getDocs(path);
                allPaths.add(docs.toString());
            }
            return new ResponseDTO<>(true, "URL has been fetched", allPaths);
        } catch (CustomException ce) {
            throw ce;
        } catch(Exception e) {
            e.printStackTrace();
            throw new CustomException(e.getMessage(), HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    @Override
    public ResponseDTO<?> getFilesURL(ChecksheetDTO checksheetDTO) throws CustomException {
        try {
            if(Objects.isNull(checksheetDTO) || Objects.isNull(checksheetDTO.getPaths()) || checksheetDTO.getPaths().isEmpty()) {
                throw new CustomException("Please provide paths", HttpStatus.UNPROCESSABLE_ENTITY);
            }
            List<String> allPaths = new ArrayList<>();
            for(String path : checksheetDTO.getPaths()) {
//                URL docs = awss3Service.getDocs(path);
//                allPaths.add(docs.toString());
                allPaths.add(FileStorageUtil.getFileURL(path));
            }
            return new ResponseDTO<>(true, "URL has been fetched", allPaths);
        } catch (CustomException ce) {
            throw ce;
        } catch(Exception e) {
            e.printStackTrace();
            throw new CustomException(e.getMessage(), HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    @Override
    public ResponseDTO<?> getRespectedChecksheet(ChecksheetDTO checksheetDTO) throws CustomException {
        try {
            if(Objects.equals(checksheetDTO.getCurrentPage(), null) || Objects.equals(checksheetDTO.getCurrentPage(), "") ||
                    Objects.equals(checksheetDTO.getPerPageRecord(), null) || Objects.equals(checksheetDTO.getPerPageRecord(), "")) {
                return new ResponseDTO<>(false, "Please provide currentPage, perPageRecord");
            }
            Optional<User> currentLoggedInUser = utilityService.getCurrentLoggedInUser();
            List<UserRoleDepartment> userRoleByUserIdDepartment = userRoleDepartmentRepository.findByUser_IdAndDeletedByIsNull(currentLoggedInUser.get().getId());
            
            checksheetDTO.setCurrentUserId(currentLoggedInUser.get().getId());
            // Filter by departments based on user's assigned departments (not roles)
            List<Long> userDepartmentIds = userRoleByUserIdDepartment.stream()
                .map(urd -> urd.getDepartmentId().getId())
                .collect(Collectors.toList());
            
            // Check if user has department-level permissions (can see subdepartments)
            if(permissionService.hasPermission(currentLoggedInUser.get().getId(), "SUBDEPARTMENT_LIST") ||
               permissionService.hasPermission(currentLoggedInUser.get().getId(), "DEPARTMENT_LIST")) {
                // Get all subdepartments for the user's departments
                List<Department> subDepartmentList = departmentRepository.findByDepartmentId_IdIn(userDepartmentIds);
                List<Long> subDepartmentIds = subDepartmentList.stream().map(Department::getId).collect(Collectors.toList());
                userDepartmentIds.addAll(subDepartmentIds);
            }
            
            if(!userDepartmentIds.isEmpty()) {
                checksheetDTO.setDepartmentIds(userDepartmentIds);
            }

            Page<ChecksheetDTO> respectedChecksheet = checksheetDAO.getRespectedChecksheet(checksheetDTO, checksheetDTO.getCurrentPage(), checksheetDTO.getPerPageRecord());
            List<ChecksheetDTO> content = respectedChecksheet.getContent();
            for(ChecksheetDTO tempChecksheetDTO: content) {
                if(!Objects.isNull(tempChecksheetDTO.getValidatorUserIds())) {
                    if(tempChecksheetDTO.getValidatorUserIds().contains(currentLoggedInUser.get().getId())) {
                        tempChecksheetDTO.setIsValidator(true);
                    }
                }

                if(!Objects.isNull(tempChecksheetDTO.getApproverUserIds())) {
                    if(tempChecksheetDTO.getApproverUserIds().contains(currentLoggedInUser.get().getId())) {
                        tempChecksheetDTO.setIsApprover(true);
                    }
                }

                if(!Objects.isNull(tempChecksheetDTO.getDataValidatorUserIds())) {
                    if(tempChecksheetDTO.getDataValidatorUserIds().contains(currentLoggedInUser.get().getId())) {
                        tempChecksheetDTO.setIsDataValidator(true);
                    }
                }

                if(!Objects.isNull(tempChecksheetDTO.getDataApproverUserIds())) {
                    if(tempChecksheetDTO.getDataApproverUserIds().contains(currentLoggedInUser.get().getId())) {
                        tempChecksheetDTO.setIsDataApprover(true);
                    }
                }

                if(!Objects.isNull(tempChecksheetDTO.getPreparerUserId())) {
                    if(Objects.equals(tempChecksheetDTO.getPreparerUserId(), currentLoggedInUser.get().getId())) {
                        tempChecksheetDTO.setIsPreparer(true);
                    }
                }
            }

            ResponseDTO<?> responseDTO = new ResponseDTO<>(true, "Data fetch successfully", respectedChecksheet.getContent());
            responseDTO.setCurrentPage(Math.toIntExact(checksheetDTO.getCurrentPage()));
            responseDTO.setPageSize(Math.toIntExact(checksheetDTO.getPerPageRecord()));
            responseDTO.setTotalRecords(respectedChecksheet.getTotalElements());
            responseDTO.setTotalPages(respectedChecksheet.getTotalPages());
            return responseDTO;
        } catch ( Exception e ) {
            e.printStackTrace();
            throw new CustomException(e.getMessage(), HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    @Override
    public ResponseDTO<?> getChecksheetDetail(ChecksheetDTO checksheetDTO) throws CustomException {
        try {
            if(Objects.isNull(checksheetDTO) ||
                    Objects.isNull(checksheetDTO.getId())) {
                throw new CustomException("Please provide id", HttpStatus.UNPROCESSABLE_ENTITY);
            }
            checksheetDTO.setIds(Collections.singletonList(checksheetDTO.getId()));
            Checksheet checksheet = checksheetRepository.findById(checksheetDTO.getId()).orElseThrow(() -> new CustomException("Please provide valid id", HttpStatus.UNPROCESSABLE_ENTITY));;
            ChecksheetDTO checksheetDetail = checksheetDAO.getRespectedChecksheetDetail(checksheetDTO);
            List<Long> auditeeTypeIds = checksheetAuditeeTypeRepository.findByChecksheet_IdAndDeletedByIsNull(checksheet.getId())
                    .stream()
                    .map(checksheetAuditeeType -> checksheetAuditeeType.getAuditeeType().getId())
                    .collect(Collectors.toList());
            checksheetDetail.setAuditeeTypeIds(auditeeTypeIds);
//            if(checksheet.getChecksheets().size() > 0 || !checksheet.getStatus().equals(ChecksheetStatusType.APPROVED)){
//                checksheetDetail.setCanCreateVersion(false);
//            }
            List<Checksheet> tempChecksheet = checksheetRepository.findByChecksheet_Id(checksheet.getId());
            if((!Objects.isNull(tempChecksheet) && !tempChecksheet.isEmpty()) || !checksheet.getStatus().equals(ChecksheetStatusType.APPROVED)) {
                checksheetDetail.setCanCreateVersion(false);
            }

            Optional<User> currentLoggedInUser = utilityService.getCurrentLoggedInUser();
            User currentUser = currentLoggedInUser.get();
            Role loginUserRole = roleRepository.findByRoleCode("SUBDEPT_ADMIN").orElseThrow(() -> new CustomException("Section Head Role is not exist!", HttpStatus.UNPROCESSABLE_ENTITY));
            Optional<UserRoleDepartment> urd = userRoleDepartmentRepository.findByUser_IdAndRole_IdAndDepartment_IdAndDeletedByIsNull(currentUser.getId(),loginUserRole.getId(),checksheet.getDepartment().getId());
            if(urd.isPresent() && !checksheet.getStatus().equals(ChecksheetStatusType.APPROVED)){
                checksheetDetail.setCanDelete(true);
            }

            checksheetDetail.setExpiryDate(checksheet.getExpiryDate());
            if(!Objects.isNull(checksheetDetail.getValidatorUserIds())) {
                List<UserDTO> validatorUsers = new ArrayList<>();
                for (Long validatorUserId: checksheetDetail.getValidatorUserIds()) {
                    UserDTO userById = userDAO.getUserById(UserDTO.builder().id(validatorUserId).build());
                    validatorUsers.add(userById);
                }
                checksheetDetail.setValidatorUsers(validatorUsers);
                if(checksheetDetail.getValidatorUserIds().contains(currentLoggedInUser.get().getId())) {
                    checksheetDetail.setIsValidator(true);
                }
            }

            if(!Objects.isNull(checksheetDetail.getApproverUserIds())) {
                List<UserDTO> approverUsers = new ArrayList<>();
                for (Long approverUserId: checksheetDetail.getApproverUserIds()) {
                    UserDTO userById = userDAO.getUserById(UserDTO.builder().id(approverUserId).build());
                    approverUsers.add(userById);
                }
                checksheetDetail.setApproverUsers(approverUsers);
                if(checksheetDetail.getApproverUserIds().contains(currentLoggedInUser.get().getId())) {
                    checksheetDetail.setIsApprover(true);
                }
            }

            if(!Objects.isNull(checksheetDetail.getDataValidatorUserIds())) {
                List<UserDTO> dataValidatorUsers = new ArrayList<>();
                for (Long dataValidatorUserId: checksheetDetail.getDataValidatorUserIds()) {
                    UserDTO userById = userDAO.getUserById(UserDTO.builder().id(dataValidatorUserId).build());
                    dataValidatorUsers.add(userById);
                }
                checksheetDetail.setDataValidatorUsers(dataValidatorUsers);
                if(checksheetDetail.getDataValidatorUserIds().contains(currentLoggedInUser.get().getId())) {
                    checksheetDetail.setIsDataValidator(true);
                }
            }

            if(!Objects.isNull(checksheetDetail.getDataApproverUserIds())) {
                List<UserDTO> dataApproverUsers = new ArrayList<>();
                for (Long dataApproverUserId: checksheetDetail.getDataApproverUserIds()) {
                    UserDTO userById = userDAO.getUserById(UserDTO.builder().id(dataApproverUserId).build());
                    dataApproverUsers.add(userById);
                }
                checksheetDetail.setDataApproverUsers(dataApproverUsers);
                if(checksheetDetail.getDataApproverUserIds().contains(currentLoggedInUser.get().getId())) {
                    checksheetDetail.setIsDataApprover(true);
                }
            }

            if(!Objects.isNull(checksheetDetail.getPreparerUserId())) {
                UserDTO userById = userDAO.getUserById(UserDTO.builder().id(checksheetDetail.getPreparerUserId()).build());
                checksheetDetail.setPreparerUser(userById);
                if(Objects.equals(checksheetDetail.getPreparerUserId(), currentLoggedInUser.get().getId())) {
                    checksheetDetail.setIsPreparer(true);
                }
            }

            if(!Objects.isNull(checksheetDetail.getOperatorUserIds())) {
                List<UserDTO> operatorUsers = new ArrayList<>();
                for (Long operatorUserId: checksheetDetail.getOperatorUserIds()) {
                    UserDTO userById = userDAO.getUserById(UserDTO.builder().id(operatorUserId).build());
                    operatorUsers.add(userById);
                }
                checksheetDetail.setOperatorUsers(operatorUsers);
            }

            if (!Objects.isNull(checksheetDetail.getAlertToUserIds())) {
                List<UserDTO> alertUsers = new ArrayList<>();
                for (Long alertUserId : checksheetDetail.getAlertToUserIds()) {
                    UserDTO userById = userDAO.getUserById(UserDTO.builder().id(alertUserId).build());
                    alertUsers.add(userById);
                }
                checksheetDetail.setAlertToUsers(alertUsers);
            }

            if (!Objects.isNull(checksheetDetail.getEscalateToUserIds())) {
                List<UserDTO> escalateUsers = new ArrayList<>();
                for (Long escalateUserId : checksheetDetail.getEscalateToUserIds()) {
                    UserDTO userById = userDAO.getUserById(UserDTO.builder().id(escalateUserId).build());
                    escalateUsers.add(userById);
                }
                checksheetDetail.setEscalateToUsers(escalateUsers);
            }

            return new ResponseDTO<>(true, "Checksheet details has been fetched", checksheetDetail);
        } catch (CustomException ce) {
            throw ce;
        } catch (Exception e) {
            e.printStackTrace();
            throw new CustomException("Some thing went wrong", HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public ResponseDTO<?> updateChecksheetStatus(ChecksheetDTO checksheetDTO) throws CustomException {
        try {
            if (Objects.isNull(checksheetDTO) || Objects.isNull(checksheetDTO.getId()) ||
                    Objects.isNull(checksheetDTO.getStatus())) {
                throw new CustomException("Please provide checksheetId, status", HttpStatus.UNPROCESSABLE_ENTITY);
            }

            Optional<Checksheet> checksheet = checksheetRepository.findById(checksheetDTO.getId());
            Checksheet chks;
            if (checksheet.isEmpty()) {
                throw new CustomException("Invalid checksheet id", HttpStatus.UNPROCESSABLE_ENTITY);
            }else{
                chks = checksheet.get();
            }
            List<ChksHeaderDTO> chksHeaderResultTrue = chksHeaderDAO.getChksHeaderByChecksheetId(chks.getId(), true);
            long totalQuestions = chksQuestionRepository.countByChecksheetId(chks.getId());
            long totalQuestionResults = chksQuestionResultRepository.countByChecksheetId(chks.getId());
            if((chksHeaderResultTrue.size() * totalQuestions) != totalQuestionResults){
                throw new CustomException("Please provide all required answer types for each question before submitting.", HttpStatus.UNPROCESSABLE_ENTITY);
            }
            Optional<User> currentUser = utilityService.getCurrentLoggedInUser();

            if (Objects.isNull(checksheet.get().getPreparerUser()) || !Objects.isNull(checksheet.get().getPreparerUser()) &&
                    !Objects.equals(checksheet.get().getPreparerUser().getId(), currentUser.get().getId())) {
                throw new CustomException("You do not have access to this checksheet", HttpStatus.FORBIDDEN);
            }

            if((Objects.equals(chks.getStatus(), ChecksheetStatusType.CREATE_CONTENT) ||
                    Objects.equals(chks.getStatus(), ChecksheetStatusType.INVALIDATED) ||
                    Objects.equals(chks.getStatus(), ChecksheetStatusType.NOT_APPROVED)) &&
                    Objects.equals(checksheetDTO.getStatus(), ChecksheetStatusType.SUBMITTED_FOR_VALIDATE.toString())) {
                chks.setUpdatedBy(currentUser.get());
                chks.setUpdatedAt(new Date());
                chks.setStatus(ChecksheetStatusType.SUBMITTED_FOR_VALIDATE);
                chks.setValidateOrApproveVersion(chks.getValidateOrApproveVersion() + 1);
                chks.setSubmittedAt(new Date());
                chks.setWaitingUserIds(chks.getValidatorUserIds());
                checksheetRepository.save(chks);
                checksheetValidationRepository.deleteAllByChecksheet_Id(chks.getId());
                //Send mail to Validators
                utilityService.sendEmail(chks.getValidatorUserIds(), EmailTemplate.SUBMITTED, chks, List.of(currentUser.get().getId()));
            } else {
                throw new CustomException("You can not update the status", HttpStatus.UNPROCESSABLE_ENTITY);
            }
            return new ResponseDTO<>(true, "Checksheet status updated successfully");
        } catch (CustomException ce) {
            throw ce;
        } catch (Exception e) {
            e.printStackTrace();
            throw new CustomException("Some thing went wrong", HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public ResponseDTO<?> createChecksheetVersion(ChecksheetDTO checksheetDTO) throws CustomException {
        try {
            if (Objects.isNull(checksheetDTO) || Objects.isNull(checksheetDTO.getId())) {
                throw new CustomException("Please provide checksheetId", HttpStatus.UNPROCESSABLE_ENTITY);
            }

            Checksheet oldChks = checksheetRepository.findById(checksheetDTO.getId()).orElseThrow(() -> new CustomException("Invalid checksheet id", HttpStatus.UNPROCESSABLE_ENTITY));
            if(!Objects.equals(oldChks.getStatus(), ChecksheetStatusType.APPROVED)){
                throw new CustomException("Checksheet is not approved.", HttpStatus.UNPROCESSABLE_ENTITY);
            }else if(oldChks.getExpiryDate() != null){
                throw new CustomException("Checksheet is expired.", HttpStatus.UNPROCESSABLE_ENTITY);
            }
//            else if(!oldChks.getChecksheets().isEmpty()){
//                throw new CustomException("Version is already created for the selected checksheet.", HttpStatus.UNPROCESSABLE_ENTITY);
//            }


            List<Checksheet> tempChecksheet = checksheetRepository.findByChecksheet_Id(oldChks.getId());
            if(!Objects.isNull(tempChecksheet) && !tempChecksheet.isEmpty()) {
                throw new CustomException("Version is already created for the selected checksheet.", HttpStatus.UNPROCESSABLE_ENTITY);
            }

            Optional<User> currentUser = utilityService.getCurrentLoggedInUser();

            // Create new checksheet with copied data
            Checksheet newChecksheet = new Checksheet();
            copyChecksheetData(oldChks, newChecksheet, checksheetDTO, currentUser.get());
            checksheetRepository.save(newChecksheet);

            //Send mail to preparor
            utilityService.sendEmail(List.of(newChecksheet.getPreparerUser().getId()), EmailTemplate.NEW, newChecksheet, List.of(currentUser.get().getId()));

            // Clone general fields
            cloneGeneralFields(oldChks, newChecksheet, currentUser.get());

            // Clone headers
            Map<Long, ChksHeader> oldToNewHeaderMap = cloneHeaders(oldChks, newChecksheet, currentUser.get());

            // Clone header data and files
            Map<Long, ChksHeaderData> oldToNewHeaderDataMap = cloneHeaderData(oldChks, newChecksheet, oldToNewHeaderMap, currentUser.get());
            cloneHeaderDataFiles(oldChks, newChecksheet, oldToNewHeaderDataMap, currentUser.get());

            // Clone questions and related data
            Map<Long, ChksQuestion> oldToNewQuestionMap = cloneQuestions(oldChks, newChecksheet, oldToNewHeaderMap, oldToNewHeaderDataMap, currentUser.get());
            cloneQuestionFiles(oldChks, newChecksheet, oldToNewQuestionMap, currentUser.get());
            cloneQuestionResults(oldChks, newChecksheet, oldToNewQuestionMap,oldToNewHeaderMap, currentUser.get());

            return new ResponseDTO<>(true, "Checksheet version created successfully");
        } catch (CustomException ce) {
            throw ce;
        } catch (Exception e) {
            e.printStackTrace();
            throw new CustomException("Error creating checksheet version: " + e.getMessage(), HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    private void copyChecksheetData(Checksheet source, Checksheet target) {
        target.setName(source.getName());
        target.setModelNo(source.getModelNo());
        target.setDescription(source.getDescription());
        target.setDepartment(source.getDepartment());
        target.setPreparerUser(source.getPreparerUser());
        target.setValidatorUserIds(source.getValidatorUserIds());
        target.setApproverUserIds(source.getApproverUserIds());
        target.setDataValidatorUserIds(source.getDataValidatorUserIds());
        target.setDataApproverUserIds(source.getDataApproverUserIds());
        target.setOperatorUserIds(source.getOperatorUserIds());
        target.setFrequencyOfCheck(source.getFrequencyOfCheck());
        target.setFrequencyOfFreqOfChk(source.getFrequencyOfFreqOfChk());
        target.setAlertToUserId(source.getAlertToUserId());
        target.setEscalateToUserId(source.getEscalateToUserId());
        target.setImplementationDate(source.getImplementationDate());
    }

    private void copyChecksheetData(Checksheet source, Checksheet target, ChecksheetDTO checksheetDTO, User currentUser) throws CustomException {
        try {
            if(Objects.equals(checksheetDTO, null) ||
                    Objects.equals(checksheetDTO.getPreparerUserUsername(), null) || checksheetDTO.getPreparerUserUsername().trim().isEmpty() ||
                    Objects.equals(checksheetDTO.getValidatorUserUsernames(), null) || checksheetDTO.getValidatorUserUsernames().isEmpty() ||
                    Objects.equals(checksheetDTO.getApproverUserUsernames(), null) || checksheetDTO.getApproverUserUsernames().isEmpty() ||
                    Objects.equals(checksheetDTO.getDataValidatorUserUsernames(), null) || checksheetDTO.getDataValidatorUserUsernames().isEmpty() ||
                    Objects.equals(checksheetDTO.getDataApproverUserUsernames(), null) || checksheetDTO.getDataApproverUserUsernames().isEmpty() ||
                    Objects.equals(checksheetDTO.getOperatorUserIds(), null) || checksheetDTO.getOperatorUserIds().isEmpty() ||
                    Objects.equals(checksheetDTO.getEscalateToUserUsernames(), null) || checksheetDTO.getEscalateToUserUsernames().isEmpty() ||
                    Objects.equals(checksheetDTO.getAlertToUserUsernames(), null) || checksheetDTO.getAlertToUserUsernames().isEmpty()
            ) {
                throw new CustomException("Please provide preparerUserId, validatorUserIds, approverUserIds," +
                        "dataValidatorUserIds, dataApproverUserIds, operatorUserIds, escalateToUserUsernames, alertToUserUsernames", HttpStatus.UNPROCESSABLE_ENTITY);
            }

//            Optional<Role> loginUserRole = roleRepository.findByRoleCode("SUBDEPT_ADMIN");
//            Optional<UserRoleDepartment> userRoleByUserIdDepartment = userRoleDepartmentRepository.findByUser_IdAndRole_Id(currentUser.getId(), loginUserRole.get().getId());
            Department curUsrSec;
//            if(Objects.equals(userRoleByUserIdDepartment, null) || userRoleByUserIdDepartment.isEmpty()) {
//                throw new CustomException("Invalid role", HttpStatus.UNPROCESSABLE_ENTITY);
//            }else{
//                curUsrSec = userRoleByUserIdDepartment.get().getDepartmentId();
//            }
            curUsrSec = source.getDepartment();
            //Set Preparer
            Optional<User> preparorUser = userRepository.findByUsernameIgnoreCase(checksheetDTO.getPreparerUserUsername());
            if(!preparorUser.isPresent()) {
                throw new CustomException("Please provide valid preparer User", HttpStatus.UNPROCESSABLE_ENTITY);
            }
            saveUserRoleDepartment(preparorUser.get(), "SUBDEPT_ADMIN", curUsrSec, currentUser);
            target.setPreparerUser(preparorUser.get());

            //Set Validators
            List<Long> validatorUserIds = new ArrayList<>();
            for (String validatorUserUsername: checksheetDTO.getValidatorUserUsernames()) {
                Optional<User> tempvalidatorUser = userRepository.findByUsernameIgnoreCase(validatorUserUsername);
                if(!tempvalidatorUser.isPresent()) {
                    throw new CustomException("Please provide valid validator User", HttpStatus.UNPROCESSABLE_ENTITY);
                }
                saveUserRoleDepartment(tempvalidatorUser.get(), "SUBDEPT_ADMIN", curUsrSec, currentUser);
                validatorUserIds.add(tempvalidatorUser.get().getId());
            }
            target.setValidatorUserIds(validatorUserIds);

            //Set approver users
            List<Long> approverUserIds = new ArrayList<>();
            for (String approverUserUsername : checksheetDTO.getApproverUserUsernames()) {
                Optional<User> tempApproverUser = userRepository.findByUsernameIgnoreCase(approverUserUsername);
                if (!tempApproverUser.isPresent()) {
                    throw new CustomException("Please provide valid approver User", HttpStatus.UNPROCESSABLE_ENTITY);
                }
                saveUserRoleDepartment(tempApproverUser.get(), "SUBDEPT_ADMIN", curUsrSec, currentUser);
                approverUserIds.add(tempApproverUser.get().getId());
            }
            target.setApproverUserIds(approverUserIds);

            //Set operators
            List<Long> operatorUserIds = new ArrayList<>();
            for(Long operatorUserUsername: checksheetDTO.getOperatorUserIds()) {
                User tempOperatorUser = userRepository.findById(operatorUserUsername).orElseThrow(() -> new CustomException("Please provide valid Operator User", HttpStatus.UNPROCESSABLE_ENTITY));
                saveUserRoleDepartment(tempOperatorUser, "OPERATOR", curUsrSec, currentUser);
                operatorUserIds.add(tempOperatorUser.getId());
            }
            target.setOperatorUserIds(checksheetDTO.getOperatorUserIds());

            //Set data validator users
            List<Long> dataValidatorUserIds = new ArrayList<>();
            for (String dataValidatorUserUsername : checksheetDTO.getDataValidatorUserUsernames()) {
                User tempDataValidatorUser = userRepository.findByUsernameIgnoreCase(dataValidatorUserUsername).orElseThrow(() ->new CustomException("Please provide valid data validator User", HttpStatus.UNPROCESSABLE_ENTITY));
                saveUserRoleDepartment(tempDataValidatorUser, "DEPT_ADMIN", curUsrSec, currentUser);
                dataValidatorUserIds.add(tempDataValidatorUser.getId());
            }
            target.setDataValidatorUserIds(dataValidatorUserIds);

            //Data approver users
            List<Long> dataApproverUserIds = new ArrayList<>();
            for (String dataApproverUserUsername : checksheetDTO.getDataApproverUserUsernames()) {
                User tempDataApproverUser = userRepository.findByUsernameIgnoreCase(dataApproverUserUsername).orElseThrow(()->new CustomException("Please provide valid data approver User", HttpStatus.UNPROCESSABLE_ENTITY));
                saveUserRoleDepartment(tempDataApproverUser, "DEPT_ADMIN", curUsrSec, currentUser);
                dataApproverUserIds.add(tempDataApproverUser.getId());
            }
            target.setDataApproverUserIds(dataApproverUserIds);

            handleEscalateUsers(checksheetDTO, target);
            handleAlertUsers(checksheetDTO, target);

            target.setModelNo(source.getModelNo());
            target.setAssetCode(source.getAssetCode());
            target.setChecksheetType(source.getChecksheetType());
            target.setName(source.getName());
            target.setModelNo(source.getModelNo());
            target.setDescription(source.getDescription());
            target.setDepartment(source.getDepartment());
            target.setFrequencyOfCheck(checksheetDTO.getFrequencyOfCheck());
            target.setFrequencyOfFreqOfChk(checksheetDTO.getFrequencyOfFreqOfChk());
            target.setChecksheet(source);
            target.setVersion(source.getVersion()+1L);
            target.setImplementationDate(checksheetDTO.getImplementationDate());
            target.setEscalationGuidelinesDays(checksheetDTO.getEscalationGuidelinesDays());
            target.setStatus(ChecksheetStatusType.CREATE_CONTENT);
            target.setVersionRemark(checksheetDTO.getVersionRemark());
            target.setCreatedBy(currentUser);
            target.setUid(source.getUid());
        }catch (Exception e) {
            e.printStackTrace();
            throw new CustomException(e.getMessage(), HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    private void saveUserRoleDepartment(User user, String roleCode, Department department, User currentUser) {
        Optional<Role> role = roleRepository.findByRoleCode(roleCode);
        if (role.isPresent()) {
            Optional<UserRoleDepartment> userRoleDepartmentByUserIdAndRoleIdAndDepartmentId =
                    userRoleDepartmentRepository.findByUser_IdAndRole_IdAndDepartment_Id(
                            user.getId(),
                            role.get().getId(),
                            department.getId()
                    );
            if (userRoleDepartmentByUserIdAndRoleIdAndDepartmentId.isEmpty()) {
                UserRoleDepartment userRoleDepartment = new UserRoleDepartment();
                userRoleDepartment.setUser(user);
                userRoleDepartment.setRole(role.get());
                userRoleDepartment.setDepartmentId(department);
                userRoleDepartment.setCreatedBy(currentUser);
                userRoleDepartmentRepository.save(userRoleDepartment);
            } else {
                userRoleDepartmentByUserIdAndRoleIdAndDepartmentId.get().setDeletedBy(null);
                userRoleDepartmentByUserIdAndRoleIdAndDepartmentId.get().setDeletedAt(null);
                userRoleDepartmentByUserIdAndRoleIdAndDepartmentId.get().setUpdatedBy(currentUser);
                userRoleDepartmentRepository.save(userRoleDepartmentByUserIdAndRoleIdAndDepartmentId.get());
            }
        }
    }

    private Map<Long, ChksHeader> cloneHeaders(Checksheet oldChecksheet, Checksheet newChecksheet, User currentUser) {
        Map<Long, ChksHeader> oldToNewMap = new HashMap<>();
        List<ChksHeader> oldHeaders = chksHeaderRepository.findByChecksheet_IdOrderById(oldChecksheet.getId());

        // First pass: Create all headers
        for (ChksHeader oldHeader : oldHeaders) {
            ChksHeader newHeader = new ChksHeader();
            newHeader.setName(oldHeader.getName());
            newHeader.setChecksheet(newChecksheet);
            newHeader.setIsResultColumn(oldHeader.getIsResultColumn());
            newHeader.setIsTraceable(oldHeader.getIsTraceable());
            newHeader.setSummaryReportLevel(oldHeader.getSummaryReportLevel());
            newHeader.setCreatedBy(currentUser);
//            chksHeaderRepository.save(newHeader);
            oldToNewMap.put(oldHeader.getId(), newHeader);
            if (oldHeader.getChksHeader() != null) {
                ChksHeader newParent = oldToNewMap.get(oldHeader.getChksHeader().getId());
                newHeader.setChksHeader(newParent);
            }
            chksHeaderRepository.save(newHeader);
        }

        // Second pass: Set parent relationships
//        for (ChksHeader oldHeader : oldHeaders) {
//            if (oldHeader.getChksHeader() != null) {
//                ChksHeader newHeader = oldToNewMap.get(oldHeader.getId());
//                ChksHeader newParent = oldToNewMap.get(oldHeader.getChksHeader().getId());
//                newHeader.setChksHeader(newParent);
//                chksHeaderRepository.save(newHeader);
//            }
//        }

        return oldToNewMap;
    }

    private Map<Long, ChksHeaderData> cloneHeaderData(Checksheet oldChecksheet, Checksheet newChecksheet,
                                                      Map<Long, ChksHeader> oldToNewHeaderMap, User currentUser) {
        Map<Long, ChksHeaderData> oldToNewMap = new HashMap<>();
        List<ChksHeaderData> oldHeaderData = chksHeaderDataRepository.findByChecksheet_IdOrderById(oldChecksheet.getId());

        // First pass: Create all header data
        for (ChksHeaderData oldData : oldHeaderData) {
            ChksHeaderData newData = new ChksHeaderData();
            newData.setName(oldData.getName());
            newData.setDescription(oldData.getDescription());
            newData.setChecksheet(newChecksheet);
            newData.setChksHeader(oldToNewHeaderMap.get(oldData.getChksHeader().getId()));
            newData.setCreatedBy(currentUser);
            newData.setLevel(oldData.getLevel());
            newData.setOrderNo(oldData.getOrderNo());
            oldToNewMap.put(oldData.getId(), newData);
            if (oldData.getChksHeaderData() != null) {
                ChksHeaderData newParent = oldToNewMap.get(oldData.getChksHeaderData().getId());
                newData.setChksHeaderData(newParent);
            }
            chksHeaderDataRepository.save(newData);
        }

        // Second pass: Set parent relationships
//        for (ChksHeaderData oldData : oldHeaderData) {
//            if (oldData.getChksHeaderData() != null) {
//                ChksHeaderData newData = oldToNewMap.get(oldData.getId());
//                ChksHeaderData newParent = oldToNewMap.get(oldData.getChksHeaderData().getId());
//                newData.setChksHeaderData(newParent);
//                chksHeaderDataRepository.save(newData);
//            }
//        }

        return oldToNewMap;
    }

    private void cloneHeaderDataFiles(Checksheet oldChecksheet, Checksheet newChecksheet,
                                      Map<Long, ChksHeaderData> oldToNewHeaderDataMap, User currentUser) throws CustomException {
        List<ChksHeaderDataFile> oldFiles = chksHeaderDataFileRepository.findByChecksheet_IdOrderById(oldChecksheet.getId());

        for (ChksHeaderDataFile oldFile : oldFiles) {
            ChksHeaderDataFile newFile = new ChksHeaderDataFile();
            newFile.setChecksheet(newChecksheet);
            newFile.setChksHeaderData(oldToNewHeaderDataMap.get(oldFile.getChksHeaderData().getId()));
            newFile.setCreatedBy(currentUser);
            chksHeaderDataFileRepository.save(newFile);

            // Generate new file path with new ID
            String oldPath = oldFile.getPath();
            String fileName = oldPath.substring(oldPath.lastIndexOf('/') + 1);
            String newFileName = "ChecksheetHeaderData/" + newFile.getId() + "_" +
                    fileName.substring(fileName.indexOf('_') + 1);

            try {
//                awss3Service.copyFile(oldPath, newFileName);
                FileStorageUtil.copyFile(oldPath, newFileName);
                newFile.setPath(newFileName);
                chksHeaderDataFileRepository.save(newFile);
            } catch (Exception e) {
                e.printStackTrace();
                throw new CustomException("Some thing went wrong", HttpStatus.UNPROCESSABLE_ENTITY);
            }
        }
    }

    private Map<Long, ChksQuestion> cloneQuestions(Checksheet oldChecksheet, Checksheet newChecksheet,
                                                   Map<Long, ChksHeader> oldToNewHeaderMap, Map<Long, ChksHeaderData> oldToNewHeaderDataMap, User currentUser) {
        Map<Long, ChksQuestion> oldToNewMap = new HashMap<>();
        List<ChksQuestion> oldQuestions = chksQuestionRepository.findByChecksheet_IdOrderById(oldChecksheet.getId());

        for (ChksQuestion oldQuestion : oldQuestions) {
            ChksQuestion newQuestion = new ChksQuestion();
            newQuestion.setName(oldQuestion.getName());
            newQuestion.setDescription(oldQuestion.getDescription());
            newQuestion.setChecksheet(newChecksheet);
            newQuestion.setChksHeader(oldToNewHeaderMap.get(oldQuestion.getChksHeader().getId()));
            newQuestion.setOrderNo(oldQuestion.getOrderNo());
            if (oldQuestion.getChksHeaderData() != null) {
                newQuestion.setChksHeaderData(oldToNewHeaderDataMap.get(oldQuestion.getChksHeaderData().getId()));
            }
            newQuestion.setCreatedBy(currentUser);
            chksQuestionRepository.save(newQuestion);
            oldToNewMap.put(oldQuestion.getId(), newQuestion);
        }

        return oldToNewMap;
    }

    private void cloneQuestionFiles(Checksheet oldChecksheet, Checksheet newChecksheet,
                                    Map<Long, ChksQuestion> oldToNewQuestionMap, User currentUser) throws CustomException {
        List<ChksQuestionFile> oldFiles = chksQuestionFileRepository.findByChecksheet_IdOrderById(oldChecksheet.getId());

        for (ChksQuestionFile oldFile : oldFiles) {
            ChksQuestionFile newFile = new ChksQuestionFile();
            newFile.setChecksheet(newChecksheet);
            newFile.setChksQuestion(oldToNewQuestionMap.get(oldFile.getChksQuestion().getId()));
            newFile.setPath(oldFile.getPath());
            newFile.setCreatedBy(currentUser);
            chksQuestionFileRepository.save(newFile);

            // Generate new file path with new ID
            String oldPath = oldFile.getPath();
            String fileName = oldPath.substring(oldPath.lastIndexOf('/') + 1);
            String newFileName = "ChecksheetQuestionData/" + newFile.getId() + "_" +
                    fileName.substring(fileName.indexOf('_') + 1);

            try {
//                awss3Service.copyFile(oldPath, newFileName);
                FileStorageUtil.copyFile(oldPath, newFileName);
                newFile.setPath(newFileName);
                chksQuestionFileRepository.save(newFile);
            } catch (Exception e) {
                throw new CustomException("Some thing went wrong", HttpStatus.UNPROCESSABLE_ENTITY);
            }
        }
    }

    private void cloneQuestionResults(Checksheet oldChecksheet, Checksheet newChecksheet,
                                      Map<Long, ChksQuestion> oldToNewQuestionMap, Map<Long, ChksHeader> oldToNewHeaderMap, User currentUser) throws IOException {
        // Clone question results
        List<ChksQuestionResult> oldResults = chksQuestionResultRepository.findByChecksheet_IdOrderById(oldChecksheet.getId());
        Map<Long, ChksQuestionResult> oldToNewResultMap = new HashMap<>();

        for (ChksQuestionResult oldResult : oldResults) {
            ChksQuestionResult newResult = new ChksQuestionResult();
            newResult.setChecksheet(newChecksheet);
            newResult.setChksQuestion(oldToNewQuestionMap.get(oldResult.getChksQuestion().getId()));
//            newResult.setChksHeader(oldToNewQuestionMap.get(oldResult.getChksQuestion().getId()).getChksHeader());
            newResult.setChksHeader(oldToNewHeaderMap.get(oldResult.getChksHeader().getId()));
            newResult.setCreatedBy(currentUser);
            newResult.setAnswerType(oldResult.getAnswerType());
            newResult.setLowerLimit(oldResult.getLowerLimit());
            newResult.setUpperLimit(oldResult.getUpperLimit());
            newResult.setUnit(oldResult.getUnit());
            newResult.setMatrixName(oldResult.getMatrixName());
            newResult.setChksMatrixRowName(oldResult.getChksMatrixRowName());
            newResult.setChksMatrixColName(oldResult.getChksMatrixColName());
            newResult.setMatrixRowHeaderNames(oldResult.getMatrixRowHeaderNames());
            newResult.setMatrixColumnHeaderNames(oldResult.getMatrixColumnHeaderNames());
            newResult.setNoOfRows(oldResult.getNoOfRows());
            newResult.setNoOfColumns(oldResult.getNoOfColumns());
            newResult.setIsOptional(oldResult.getIsOptional());
            newResult.setNoOfResults(oldResult.getNoOfResults());
            newResult.setObjectiveType(oldResult.getObjectiveType());
            chksQuestionResultRepository.save(newResult);
            chksQuestionResultRepository.flush();
            if(oldResult.getMatrixFileLocation() != null && !oldResult.getMatrixFileLocation().isBlank()){
                // Generate new file path with new ID
                String oldPath = oldResult.getMatrixFileLocation();
                String fileName = oldPath.substring(oldPath.lastIndexOf('/') + 1);
                String newFileName = "ChecksheetQuestionResultData/MatrixFiles/" + newResult.getId() + "_" +
                        fileName.substring(fileName.indexOf('_') + 1);
                FileStorageUtil.copyFile(oldPath, newFileName);
                newResult.setMatrixFileLocation(newFileName);
                chksQuestionResultRepository.save(newResult);
            }
            oldToNewResultMap.put(oldResult.getId(), newResult);
        }

        // Clone question result options
        List<ChksQuestionResultOption> oldOptions = chksQuestionResultOptionRepository.findByChecksheet_IdOrderById(oldChecksheet.getId());
        for (ChksQuestionResultOption oldOption : oldOptions) {
            ChksQuestionResultOption newOption = new ChksQuestionResultOption();
            newOption.setChecksheet(newChecksheet);
            newOption.setChksQuestionResult(oldToNewResultMap.get(oldOption.getChksQuestionResult().getId()));
            newOption.setOption(oldOption.getOption());
            newOption.setJudgement(oldOption.getJudgement());
            newOption.setCreatedBy(currentUser);
            chksQuestionResultOptionRepository.save(newOption);
        }

        // Clone question result matrices
        List<ChksQuestionResultMatrix> oldMatrices = chksQuestionResultMatrixRepository.findByChecksheet_IdOrderById(oldChecksheet.getId());
        for (ChksQuestionResultMatrix oldMatrix : oldMatrices) {
            ChksQuestionResultMatrix newMatrix = new ChksQuestionResultMatrix();
            newMatrix.setChecksheet(newChecksheet);
            newMatrix.setChksQuestionResult(oldToNewResultMap.get(oldMatrix.getChksQuestionResult().getId()));
            newMatrix.setChksMatrixRowHdr(oldMatrix.getChksMatrixRowHdr());
            newMatrix.setChksMatrixColHdr(oldMatrix.getChksMatrixColHdr());
            newMatrix.setDate(oldMatrix.getData());
            newMatrix.setComment(oldMatrix.getComment());
            newMatrix.setRowId(oldMatrix.getRowId());
            newMatrix.setColumnId(oldMatrix.getColumnId());
            newMatrix.setCreatedBy(currentUser);
            chksQuestionResultMatrixRepository.save(newMatrix);
        }
    }

    private void cloneGeneralFields(Checksheet oldChecksheet, Checksheet newChecksheet, User currentUser) {
        List<ChksGeneralField> oldGeneralFields = chksGeneralFieldRepository.findByChecksheet_IdOrderById(oldChecksheet.getId());

        for (ChksGeneralField oldField : oldGeneralFields) {
            ChksGeneralField newField = new ChksGeneralField();
            newField.setChecksheet(newChecksheet);
            newField.setName(oldField.getName());
            newField.setCreatedBy(currentUser);
            chksGeneralFieldRepository.save(newField);
        }
    }

    private boolean isUserAssignedToOtherChecksheets(Long userId, Long departmentId, Long currentChecksheetId) {
        return checksheetDAO.isUserAssignedToOtherChecksheets(userId, departmentId, currentChecksheetId, "validator_user_ids");
    }

    private boolean isApproverAssignedToOtherChecksheets(Long userId, Long departmentId, Long currentChecksheetId) {
        return checksheetDAO.isUserAssignedToOtherChecksheets(userId, departmentId, currentChecksheetId, "approver_user_ids");
    }

    private boolean isDataValidatorAssignedToOtherChecksheets(Long userId, Long departmentId, Long currentChecksheetId) {
        return checksheetDAO.isUserAssignedToOtherChecksheets(userId, departmentId, currentChecksheetId, "data_validator_user_ids");
    }

    private boolean isDataApproverAssignedToOtherChecksheets(Long userId, Long departmentId, Long currentChecksheetId) {
        return checksheetDAO.isUserAssignedToOtherChecksheets(userId, departmentId, currentChecksheetId, "data_approver_user_ids");
    }

    @Override
    public ResponseDTO<?> getLovData() throws CustomException {
        try{
            List<LovDataDTO> lovData = lovDataDAO.getAllLovData();
            Map<String, Object> lovs = new HashMap<String, Object>();
            for(LovDataDTO lov:lovData){
                if(lov.getValueType().equals("JSON")){
                    lovs.put(lov.getName(),getJSON(lov.getValue().trim()));
                }else if(lov.getValueType().equals("JSON_Array")){
                    lovs.put(lov.getName(),getJSONArray(lov.getValue().trim()));
                }else{
                    lovs.put(lov.getName(),lov.getValue().trim());
                }
            }
            return new ResponseDTO<>("Data have been fetched successfully", lovs);
        } catch (Exception e) {
            e.printStackTrace();
            throw new CustomException("Some thing went wrong", HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    @Override
    public ResponseDTO<?> getPublicChecksheets() throws CustomException {
        try{
            List<ChecksheetDTO> checksheetDTOS = new ArrayList<>(0);
            List<Checksheet> publicChks = checksheetRepository.findByChecksheetTypeAndStatus(ChecksheetType.PUBLIC,ChecksheetStatusType.APPROVED);
            for(Checksheet publicChk:publicChks){
                ChecksheetDTO checksheetDTO= new ChecksheetDTO();
                checksheetDTO.setId(publicChk.getId());
                checksheetDTO.setName(publicChk.getName());
                checksheetDTO.setPath(publicChk.getPath());
                checksheetDTOS.add(checksheetDTO);
            }
            return new ResponseDTO<>(true, "Public checksheets are fetched successfully!",checksheetDTOS);
        } catch (Exception e) {
            e.printStackTrace();
            throw new CustomException("Some thing went wrong", HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    @Override
    public ResponseDTO<?> getDepartmentChecksheets(DepartmentDTO departmentDTO) throws CustomException {
        try{
            List<ChecksheetDTO> checksheetDTOS = new ArrayList<>(0);
            List<Checksheet> deptChks = checksheetRepository.findByDepartment_IdIn(departmentDTO.getDepartmentIds());
            for(Checksheet deptChk:deptChks){
                ChecksheetDTO checksheetDTO= new ChecksheetDTO();
                checksheetDTO.setId(deptChk.getId());
                checksheetDTO.setName(deptChk.getName());
                checksheetDTOS.add(checksheetDTO);
            }
            return new ResponseDTO<>(true, "Department checksheets are fetched successfully!",checksheetDTOS);
        } catch (Exception e) {
            e.printStackTrace();
            throw new CustomException("Some thing went wrong", HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    @Override
    public ResponseDTO<?> getWaitingCount() throws CustomException {
        try {
            User currentUser = utilityService.getCurrentLoggedInUser().get();
            Map<String, Object> result = new HashMap<>();
            Long checksheetCount = checksheetRepository.getWaitingCount(currentUser.getId());
            Long userChecksheetCount = userChecksheetRepository.getWaitingCount(currentUser.getId());
            result.put("checksheetCount", checksheetCount);
            result.put("userChecksheetCount", userChecksheetCount);
            return new ResponseDTO<>(true, "Count fetched successfully", result);
        } catch (Exception e) {
            e.printStackTrace();
            throw new CustomException("Some thing went wrong", HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseDTO<?> deleteChks(Long chksId) throws CustomException {
        Checksheet chks = checksheetRepository.findById(chksId).orElseThrow(() -> new CustomException("Please, provide valid checksheet ID", HttpStatus.UNPROCESSABLE_ENTITY));
        if(chks.getStatus().equals(ChecksheetStatusType.APPROVED)){
            throw new CustomException("Approved Checksheet can not be deleted!",HttpStatus.UNPROCESSABLE_ENTITY);
        }
        User currentUser = utilityService.getCurrentLoggedInUser().get();
        Role loginUserRole = roleRepository.findByRoleCode("SUBDEPT_ADMIN").orElseThrow(() -> new CustomException("Section Head Role is not exist!", HttpStatus.UNPROCESSABLE_ENTITY));
        userRoleDepartmentRepository.findByUser_IdAndRole_IdAndDepartment_IdAndDeletedByIsNull(currentUser.getId(),loginUserRole.getId(),chks.getDepartment().getId())
                .orElseThrow(() -> new CustomException("You do not have access to delete checksheet!", HttpStatus.UNPROCESSABLE_ENTITY));

        checksheetApprovalRepository.deleteAllByChecksheet_Id(chksId);
        checksheetApprovalHistoryRepository.deleteAllByChecksheet_Id(chksId);
        checksheetValidationHistoryRepository.deleteAllByChecksheet_Id(chksId);
        checksheetValidationRepository.deleteAllByChecksheet_Id(chksId);
        checksheetAuditeeTypeRepository.deleteAllByChecksheet_Id(chksId);
        chksGeneralFieldRepository.deleteByChecksheet_Id(chksId);
        chksHeaderRepository.deleteByChecksheet_Id(chksId);
        chksHeaderDataService.deleteChksData(chksId);
        if(!Objects.isNull(chks.getPath()) && !chks.getPath().isBlank()){
            FileStorageUtil.deleteFile(chks.getPath());
        }
        checksheetRepository.delete(chks);
        return new ResponseDTO<>(true, "Checksheet is deleted successfully!");

    }

    @Override
    public ResponseDTO<?> checkCodeAvailability(String modelNo, Long excludeId) throws CustomException {
        if (modelNo == null || modelNo.trim().isEmpty()) {
            return new ResponseDTO<>(true, "Code is available", Map.of("available", true));
        }
        boolean taken = (excludeId != null)
            ? checksheetRepository.existsByModelNoIgnoreCaseAndIdNot(modelNo.trim(), excludeId)
            : checksheetRepository.existsByModelNoIgnoreCase(modelNo.trim());
        return new ResponseDTO<>(true, "Code availability checked", Map.of("available", !taken));
    }

    @Override
    public ResponseDTO<?> getApprovedNotExpiredChecksheets() throws CustomException {
        try {
            List<Checksheet> checksheets = checksheetRepository.findApprovedAndNotExpired(new Date());
            List<ChecksheetDTO> response = checksheets.stream()
                    .map(checksheet -> new ChecksheetDTO(checksheet.getId(), checksheet.getName()))
                    .collect(Collectors.toList());
            return new ResponseDTO<>(true, "Approved checksheets fetched successfully", response);
        } catch (Exception e) {
            e.printStackTrace();
            throw new CustomException("Some thing went wrong", HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    @Override
    public ResponseDTO<?> getActiveAuditors() throws CustomException {
        try {
            List<User> activeAuditors = userRoleDepartmentRepository.findDistinctActiveUsersByRoleCode("AUDITOR");
            List<Map<String, Object>> response = activeAuditors.stream().map(user -> {
                Map<String, Object> userMap = new HashMap<>();
                userMap.put("id", user.getId());
                userMap.put("firstName", user.getFirstName());
                userMap.put("lastName", user.getLastName());
                userMap.put("usrename", user.getUsername());
                return userMap;
            }).collect(Collectors.toList());
            return new ResponseDTO<>(true, "Active auditors fetched successfully", response);
        } catch (Exception e) {
            e.printStackTrace();
            throw new CustomException("Some thing went wrong", HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    @Override
    public ResponseDTO<?> getActiveAuditeeLocationsByChecksheet(ChecksheetDTO checksheetDTO) throws CustomException {
        try {
            if (Objects.isNull(checksheetDTO) || Objects.isNull(checksheetDTO.getId())) {
                throw new CustomException("Please provide checksheetId", HttpStatus.UNPROCESSABLE_ENTITY);
            }

            if (checksheetRepository.findById(checksheetDTO.getId()).isEmpty()) {
                throw new CustomException("Please provide valid checksheetId", HttpStatus.UNPROCESSABLE_ENTITY);
            }

            List<AuditeeLocation> locations = auditeeLocationTypeRepository.findActiveAuditeeLocationsByChecksheetId(checksheetDTO.getId());
            locations.sort(Comparator.comparing(AuditeeLocation::getId));
            List<Map<String, Object>> response = locations.stream().map(location -> {
                Map<String, Object> locationMap = new HashMap<>();
                locationMap.put("id", location.getId());
                locationMap.put("code", location.getAuditee() != null ? location.getAuditee().getCode() : null);
                locationMap.put("address", location.getAddress());
                locationMap.put("pinCode", location.getPinCode());
                return locationMap;
            }).collect(Collectors.toList());

            return new ResponseDTO<>(true, "Auditee locations fetched successfully", response);
        } catch (CustomException ce) {
            throw ce;
        } catch (Exception e) {
            e.printStackTrace();
            throw new CustomException("Some thing went wrong", HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseDTO<?> createChecksheetAssignment(ChecksheetDTO checksheetDTO) throws CustomException {
        try {
            if (Objects.isNull(checksheetDTO)
                    || Objects.isNull(checksheetDTO.getChecksheetId())
                    || Objects.isNull(checksheetDTO.getAuditorId())
                    || Objects.isNull(checksheetDTO.getAuditeeLocationIds())
                    || checksheetDTO.getAuditeeLocationIds().isEmpty()) {
                throw new CustomException("Please provide checksheetId, auditorId and auditeeLocationIds", HttpStatus.UNPROCESSABLE_ENTITY);
            }

            if (checksheetDTO.getChecksheetId() <= 0
                    || checksheetDTO.getAuditorId() <= 0
                    || checksheetDTO.getAuditeeLocationIds().stream().anyMatch(Objects::isNull)
                    || checksheetDTO.getAuditeeLocationIds().stream().anyMatch(id -> id <= 0)) {
                throw new CustomException("All fields are required with valid values", HttpStatus.UNPROCESSABLE_ENTITY);
            }

            Optional<Checksheet> checksheetOptional = checksheetRepository.findById(checksheetDTO.getChecksheetId());
            if (checksheetOptional.isEmpty()) {
                throw new CustomException("Please provide valid checksheetId", HttpStatus.UNPROCESSABLE_ENTITY);
            }

            Checksheet checksheet = checksheetOptional.get();
            if (!ChecksheetStatusType.APPROVED.equals(checksheet.getStatus())) {
                throw new CustomException("Checksheet must be approved", HttpStatus.UNPROCESSABLE_ENTITY);
            }

            if (checksheet.getExpiryDate() != null) {
                LocalDate expiryDate = checksheet.getExpiryDate().toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
                if (expiryDate.isBefore(LocalDate.now())) {
                    throw new CustomException("Checksheet is expired", HttpStatus.UNPROCESSABLE_ENTITY);
                }
            }

            User auditor = userRepository.findById(checksheetDTO.getAuditorId())
                    .orElseThrow(() -> new CustomException("Please provide valid auditorId", HttpStatus.UNPROCESSABLE_ENTITY));

            if (!"A".equalsIgnoreCase(auditor.getStatus()) || auditor.getDeletedAt() != null) {
                throw new CustomException("Auditor must be active", HttpStatus.UNPROCESSABLE_ENTITY);
            }

            boolean isAuditorRole = userRoleDepartmentRepository.existsActiveUserWithRoleCode(auditor.getId(), "AUDITOR");
            if (!isAuditorRole) {
                throw new CustomException("Selected user is not mapped with AUDITOR role", HttpStatus.UNPROCESSABLE_ENTITY);
            }

            List<Long> distinctLocationIds = new ArrayList<>(new LinkedHashSet<>(checksheetDTO.getAuditeeLocationIds()));
            List<AuditeeLocation> auditeeLocations = auditeeLocationRepository.findAllByIdInAndStatusAndDeletedAtIsNull(distinctLocationIds, "ACTIVE");
            if (auditeeLocations.size() != distinctLocationIds.size()) {
                throw new CustomException("Please provide valid active auditeeLocationIds", HttpStatus.UNPROCESSABLE_ENTITY);
            }

            long validLocationsCount = auditeeLocationTypeRepository.countDistinctValidAuditeeLocationsForChecksheet(
                    checksheet.getId(),
                    distinctLocationIds
            );
            if (validLocationsCount != distinctLocationIds.size()) {
                throw new CustomException("Some selected auditee locations do not match checksheet auditee types", HttpStatus.UNPROCESSABLE_ENTITY);
            }
            checksheetAssignmentRepository.deleteAllByChecksheet_IdAndAuditor_Id(checksheet.getId(), auditor.getId());
            checksheetAssignmentRepository.flush();

            User currentUser = utilityService.getCurrentLoggedInUser()
                    .orElseThrow(() -> new CustomException("Current user not found", HttpStatus.UNPROCESSABLE_ENTITY));

            Map<Long, AuditeeLocation> locationMap = auditeeLocations.stream()
                    .collect(Collectors.toMap(AuditeeLocation::getId, location -> location));



            for (Long auditeeLocationId : distinctLocationIds) {
                ChecksheetAssignment checksheetAssignment = new ChecksheetAssignment();
                checksheetAssignment.setChecksheet(checksheet);
                checksheetAssignment.setAuditor(auditor);
                checksheetAssignment.setAuditeeLocation(locationMap.get(auditeeLocationId));
                checksheetAssignment.setCreatedBy(currentUser);
                checksheetAssignmentRepository.save(checksheetAssignment);
            }

            return new ResponseDTO<>(true, "Checksheet assignment created successfully");
        } catch (CustomException ce) {
            throw ce;
        } catch (Exception e) {
            e.printStackTrace();
            throw new CustomException("Some thing went wrong", HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    public JsonNode getJSON(String value) throws CustomException {
        try {
            ObjectMapper objectMapper = new ObjectMapper();
            return objectMapper.readTree(value);
        } catch (Exception e) {
            e.printStackTrace();
            throw new CustomException("Some thing went wrong", HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    public ArrayNode getJSONArray(String value) throws CustomException {
        try {
            ObjectMapper objectMapper = new ObjectMapper();
            JsonNode jsonNode = objectMapper.readTree(value);
            return (ArrayNode) jsonNode;
        } catch (Exception e) {
            e.printStackTrace();
            throw new CustomException("Some thing went wrong", HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    private void handleEscalateUsers(ChecksheetDTO checksheetDTO, Checksheet checksheet) throws CustomException {
        List<Long> escalateUserIds = new ArrayList<>();
        
        if (checksheetDTO.getEscalateToUserUsernames() != null && !checksheetDTO.getEscalateToUserUsernames().isEmpty()) {
            for (String escalateUserUsername : checksheetDTO.getEscalateToUserUsernames()) {
                Optional<User> escalateUser = userRepository.findByUsernameIgnoreCase(escalateUserUsername);
                if (!escalateUser.isPresent()) {
                    throw new CustomException("Please provide valid escalate User: " + escalateUserUsername, HttpStatus.UNPROCESSABLE_ENTITY);
                }
                escalateUserIds.add(escalateUser.get().getId());
            }
            checksheet.setEscalateToUserIds(escalateUserIds);
        }
    }

    private void handleAlertUsers(ChecksheetDTO checksheetDTO, Checksheet checksheet) throws CustomException {
        List<Long> alertUserIds = new ArrayList<>();
        
        if (checksheetDTO.getAlertToUserUsernames() != null && !checksheetDTO.getAlertToUserUsernames().isEmpty()) {
            for (String alertUserUsername : checksheetDTO.getAlertToUserUsernames()) {
                Optional<User> alertUser = userRepository.findByUsernameIgnoreCase(alertUserUsername);
                if (!alertUser.isPresent()) {
                    throw new CustomException("Please provide valid alert User: " + alertUserUsername, HttpStatus.UNPROCESSABLE_ENTITY);
                }
                alertUserIds.add(alertUser.get().getId());
            }
            checksheet.setAlertToUserIds(alertUserIds);
        }
    }

    private void saveChecksheetAuditeeTypes(Checksheet checksheet, List<Long> auditeeTypeIds, User currentUser) throws CustomException {
        checksheetAuditeeTypeRepository.deleteAllByChecksheet_Id(checksheet.getId());
        checksheetAuditeeTypeRepository.flush();
        if (auditeeTypeIds == null || auditeeTypeIds.isEmpty()) {
            return;
        }

        List<AuditeeType> auditeeTypes = auditeeTypeRepository.findAllById(auditeeTypeIds);
        if (auditeeTypes.size() != new HashSet<>(auditeeTypeIds).size()) {
            throw new CustomException("Please provide valid auditeeTypeIds", HttpStatus.UNPROCESSABLE_ENTITY);
        }

        Map<Long, AuditeeType> auditeeTypeMap = auditeeTypes.stream()
                .collect(Collectors.toMap(AuditeeType::getId, auditeeType -> auditeeType));

        for (Long auditeeTypeId : new LinkedHashSet<>(auditeeTypeIds)) {
            ChecksheetAuditeeType checksheetAuditeeType = new ChecksheetAuditeeType();
            checksheetAuditeeType.setChecksheet(checksheet);
            checksheetAuditeeType.setAuditeeType(auditeeTypeMap.get(auditeeTypeId));
            checksheetAuditeeType.setCreatedBy(currentUser);
            checksheetAuditeeTypeRepository.save(checksheetAuditeeType);
        }
    }
}
