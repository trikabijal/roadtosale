package com.checkSheet.service;

import com.checkSheet.DAO.ChecksheetApprovalHistoryDAO;
import com.checkSheet.DAO.ChksGeneralFieldDAO;
import com.checkSheet.DAO.ChksHeaderDAO;
import com.checkSheet.DTO.ChecksheetApprovalDTO;
import com.checkSheet.DTO.ChecksheetApprovalHistoryDTO;
import com.checkSheet.DTO.ChecksheetValidationHistoryDTO;
import com.checkSheet.DTO.response.ResponseDTO;
import com.checkSheet.constant.ChecksheetApprovalStatusType;
import com.checkSheet.constant.ChecksheetStatusType;
import com.checkSheet.constant.EmailTemplate;
import com.checkSheet.entity.*;
import com.checkSheet.exception.CustomException;
import com.checkSheet.repository.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class ChecksheetApprovalServiceImpl implements ChecksheetApprovalService {
    @Autowired
    private ChksGeneralFieldRepository chksGeneralFieldRepository;

    @Autowired
    private ChecksheetRepository checksheetRepository;

    @Autowired
    private UtilityService utilityService;

    @Autowired
    private ChksGeneralFieldDAO chksGeneralFieldDAO;

    @Autowired
    private ChksHeaderRepository chksHeaderRepository;

    @Autowired
    private ChksHeaderDAO chksHeaderDAO;

//    @Autowired
//    private ChecksheetValidationRepository checksheetValidationRepository;

    @Autowired
    private ChecksheetApprovalHistoryRepository checksheetApprovalHistoryRepository;

    @Autowired
    private ChecksheetApprovalRepository checksheetApprovalRepository;

    @Autowired
    private ChecksheetApprovalHistoryDAO checksheetApprovalHistoryDAO;


    @Transactional(rollbackFor = Exception.class)
    @Override
    public ResponseDTO<?> addChecksheetApproval(ChecksheetApprovalDTO checksheetApprovalDTO) throws CustomException {
        try {
            if (Objects.isNull(checksheetApprovalDTO) || Objects.isNull(checksheetApprovalDTO.getChecksheetId()) || Objects.isNull(checksheetApprovalDTO.getStatus()) || checksheetApprovalDTO.getStatus().toString().isEmpty() || Objects.isNull(checksheetApprovalDTO.getRemarks()) || checksheetApprovalDTO.getRemarks().trim().isEmpty()) {
                throw new CustomException("Please provide checksheetId, status and remarks", HttpStatus.UNPROCESSABLE_ENTITY);
            }
            Optional<User> currentUser = utilityService.getCurrentLoggedInUser();
            if (currentUser.isEmpty()) {
                throw new CustomException("User not found", HttpStatus.UNAUTHORIZED);
            }

            Optional<Checksheet> checksheet = checksheetRepository.findById(checksheetApprovalDTO.getChecksheetId());
            if (checksheet.isEmpty()) {
                throw new CustomException("Invalid checksheetId", HttpStatus.UNPROCESSABLE_ENTITY);
            }
            if (!checksheet.get().getApproverUserIds().contains(currentUser.get().getId())) {
                throw new CustomException("You are not authorized to approve this checksheet", HttpStatus.FORBIDDEN);
            }

            // Check if checksheet status is SUBMITTED_FOR_VALIDATE
            if (!Objects.equals(ChecksheetStatusType.VALIDATED, checksheet.get().getStatus())) {
                throw new CustomException("Checksheet is not submitted for approval", HttpStatus.UNPROCESSABLE_ENTITY);
            }

            // Check if validation already exists for current user
            Optional<ChecksheetApproval> existingValidation = checksheetApprovalRepository.findByChecksheet_IdAndApproverUserId_Id(checksheetApprovalDTO.getChecksheetId(), currentUser.get().getId());
            if (existingValidation.isPresent()) {
                throw new CustomException("You have already validated this checksheet", HttpStatus.UNPROCESSABLE_ENTITY);
            }

            // Create new validation record
            ChecksheetApproval checksheetApproval = new ChecksheetApproval();
            checksheetApproval.setChecksheet(checksheet.get());
            checksheetApproval.setStatus(checksheetApprovalDTO.getStatus());
            checksheetApproval.setRemarks(checksheetApprovalDTO.getRemarks());
            checksheetApproval.setApproverUserId(currentUser.get());
            checksheetApproval.setApprovedAt(new Date());
            checksheetApproval.setCreatedBy(currentUser.get());
            checksheetApprovalRepository.save(checksheetApproval);

            ChecksheetApprovalHistory checksheetApprovalHistory = new ChecksheetApprovalHistory();
            checksheetApprovalHistory.setChecksheet(checksheet.get());
            checksheetApprovalHistory.setStatus(checksheetApprovalDTO.getStatus());
            checksheetApprovalHistory.setRemarks(checksheetApprovalDTO.getRemarks());
            checksheetApprovalHistory.setApproverUserId(currentUser.get());
            checksheetApprovalHistory.setApprovedAt(new Date());
            checksheetApprovalHistory.setVersion(checksheet.get().getValidateOrApproveVersion() == null ? 1 : checksheet.get().getValidateOrApproveVersion());
            checksheetApprovalHistoryRepository.save(checksheetApprovalHistory);

            if (Objects.equals(checksheetApprovalDTO.getStatus(), ChecksheetApprovalStatusType.NOT_APPROVED)) {
                checksheet.get().setStatus(ChecksheetStatusType.NOT_APPROVED);
                checksheet.get().setWaitingUserIds(List.of(checksheet.get().getPreparerUser().getId()));
                //Send mail to Preparor
                utilityService.sendEmail(List.of(checksheet.get().getPreparerUser().getId()), EmailTemplate.NOT_APPROVED, checksheet.get(),List.of(currentUser.get().getId()));
            }else{
                if(!Objects.equals(checksheetApprovalDTO.getImplementationDate(),null)){
                    Checksheet chks = checksheet.get();
                    chks.setImplementationDate(checksheetApprovalDTO.getImplementationDate());
                    checksheetRepository.save(chks);
                }
            }

            // Check if all validators have validated
            List<ChecksheetApproval> allApprovals = checksheetApprovalRepository.findByChecksheet_Id(checksheetApprovalDTO.getChecksheetId());

            if (allApprovals.size() == checksheet.get().getApproverUserIds().size()) {
                // All validators have validated - check if any invalidated
                boolean hasInvalidated = allApprovals.stream().anyMatch(v -> ChecksheetApprovalStatusType.NOT_APPROVED.equals(v.getStatus()));

                // Update checksheet status
                checksheet.get().setStatus(hasInvalidated ? ChecksheetStatusType.NOT_APPROVED : ChecksheetStatusType.APPROVED);
                checksheet.get().setWaitingUserIds(new ArrayList<>());
                if(checksheet.get().getChecksheet() != null){
                    Checksheet parentChks = checksheet.get().getChecksheet();
                    parentChks.setExpiryDate(checksheetApprovalDTO.getImplementationDate());
                    checksheetRepository.save(parentChks);
                }
                //Send mail to Operators -- we will not send mail to opearator
                List<Long> validatorSectionHeadPreparorIds = new ArrayList<>();
                validatorSectionHeadPreparorIds.addAll(checksheet.get().getValidatorUserIds());
                validatorSectionHeadPreparorIds.addAll(List.of(checksheet.get().getCreatedBy().getId(),checksheet.get().getPreparerUser().getId()));
                utilityService.sendEmail(validatorSectionHeadPreparorIds, EmailTemplate.APPROVED, checksheet.get(),checksheet.get().getApproverUserIds());
            }else{
                List<Long> waitingApproverUserIds = checksheet.get().getWaitingUserIds();
                waitingApproverUserIds.removeIf(userId -> userId.equals(currentUser.get().getId()));
                checksheet.get().setWaitingUserIds(waitingApproverUserIds);
            }
            checksheetRepository.save(checksheet.get());
            return new ResponseDTO<>(true, "Checksheet approval added successfully");
        } catch (CustomException ce) {
            ce.printStackTrace();
            throw ce;
        } catch (Exception e) {
            e.printStackTrace();
            throw new CustomException("Something went wrong", HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    @Override
    public ResponseDTO<?> getChecksheetApproval(ChecksheetApprovalDTO checksheetApprovalDTO) throws CustomException {
        try {
            if (Objects.isNull(checksheetApprovalDTO) || Objects.isNull(checksheetApprovalDTO.getChecksheetId())) {
                throw new CustomException("Please provide checksheetId", HttpStatus.UNPROCESSABLE_ENTITY);
            }
            Optional<Checksheet> checksheet = checksheetRepository.findById(checksheetApprovalDTO.getChecksheetId());
            checksheet.orElseThrow(() -> new CustomException("Invalid checksheetId", HttpStatus.UNPROCESSABLE_ENTITY));
            List<ChecksheetApprovalHistoryDTO> checksheetValidatorHistoryByChecksheetId =
                    checksheetApprovalHistoryDAO.getChecksheetApproverHistoryByChecksheetId(checksheetApprovalDTO.getChecksheetId());

            Optional<User> currentUser = utilityService.getCurrentLoggedInUser();

            Map<String, Object> response = new HashMap<>();
            Map<String, List<ChecksheetApprovalHistoryDTO>> groupedHistory = checksheetValidatorHistoryByChecksheetId.stream()
                    .collect(Collectors.groupingBy(
                            history -> history.getFirstName() + " " + history.getLastName(),
                            LinkedHashMap::new,
                            Collectors.toList()
                    ));
            response.put("checksheetApprovalHistory", groupedHistory);
            response.put("checksheetStatus", checksheet.get().getStatus());
            if(checksheet.isPresent() && Objects.equals(checksheet.get().getStatus(), ChecksheetStatusType.VALIDATED)) {
                // Check if current user has a validation record for the current version
                if (checksheetValidatorHistoryByChecksheetId != null && !checksheetValidatorHistoryByChecksheetId.isEmpty()) {
                    Boolean isApproverComment = false;
                    isApproverComment = checksheetValidatorHistoryByChecksheetId.stream()
                            .anyMatch(history ->
                                    Objects.equals(history.getApproverUserId(), currentUser.get().getId()) &&
                                            Objects.equals(history.getVersion(), checksheet.get().getValidateOrApproveVersion())
                            );
                    response.put("isApproverComment", isApproverComment);
                }
            }


            return new ResponseDTO<>(true, "Checksheet validation fetched successfully", response);
        } catch (CustomException ce) {
            ce.printStackTrace();
            throw ce;
        } catch (Exception e) {
            e.printStackTrace();
            throw new CustomException("Something went wrong", HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

}
