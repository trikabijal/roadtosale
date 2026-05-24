package com.checkSheet.service;

import com.checkSheet.DAO.ChecksheetValidationDAO;
import com.checkSheet.DAO.ChecksheetValidationHistoryDAO;
import com.checkSheet.DTO.ChecksheetValidationDTO;
import com.checkSheet.DTO.ChecksheetValidationHistoryDTO;
import com.checkSheet.DTO.response.ResponseDTO;
import com.checkSheet.constant.ChecksheetStatusType;
import com.checkSheet.constant.ChecksheetValidationStatusType;
import com.checkSheet.constant.EmailTemplate;
import com.checkSheet.entity.Checksheet;
import com.checkSheet.entity.ChecksheetValidation;
import com.checkSheet.entity.ChecksheetValidationHistory;
import com.checkSheet.entity.User;
import com.checkSheet.exception.CustomException;
import com.checkSheet.repository.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class ChecksheetValidationServiceImpl implements ChecksheetValidationService {

    @Autowired
    private ChecksheetRepository checksheetRepository;

    @Autowired
    private UtilityService utilityService;

    @Autowired
    private ChecksheetValidationDAO checksheetValidationDAO;

    @Autowired
    private ChecksheetValidationHistoryDAO checksheetValidationHistoryDAO;

    @Autowired
    private ChecksheetValidationRepository checksheetValidationRepository;

    @Autowired
    private ChecksheetValidationHistoryRepository checksheetValidationHistoryRepository;

    @Autowired
    private ChecksheetApprovalRepository checksheetApprovalRepository;

    @Transactional(rollbackFor = Exception.class)
    @Override
    public ResponseDTO<?> addChecksheetValidation(ChecksheetValidationDTO checksheetValidationDTO) throws CustomException {
        try {
            if (Objects.isNull(checksheetValidationDTO) || Objects.isNull(checksheetValidationDTO.getChecksheetId()) || Objects.isNull(checksheetValidationDTO.getStatus()) || checksheetValidationDTO.getStatus().toString().isEmpty() || Objects.isNull(checksheetValidationDTO.getRemarks()) || checksheetValidationDTO.getRemarks().trim().isEmpty()) {
                throw new CustomException("Please provide checksheetId, status and remarks", HttpStatus.UNPROCESSABLE_ENTITY);
            }
            Optional<User> currentUser = utilityService.getCurrentLoggedInUser();
            if (currentUser.isEmpty()) {
                throw new CustomException("User not found", HttpStatus.UNAUTHORIZED);
            }

            Optional<Checksheet> checksheet = checksheetRepository.findById(checksheetValidationDTO.getChecksheetId());
            if (checksheet.isEmpty()) {
                throw new CustomException("Invalid checksheetId", HttpStatus.UNPROCESSABLE_ENTITY);
            }
            if (!checksheet.get().getValidatorUserIds().contains(currentUser.get().getId())) {
                throw new CustomException("You are not authorized to validate this checksheet", HttpStatus.FORBIDDEN);
            }

            // Check if checksheet status is SUBMITTED_FOR_VALIDATE
            if (!Objects.equals(ChecksheetStatusType.SUBMITTED_FOR_VALIDATE, checksheet.get().getStatus())) {
                throw new CustomException("Checksheet is not submitted for validation", HttpStatus.UNPROCESSABLE_ENTITY);
            }

            // Check if validation already exists for current user
            Optional<ChecksheetValidation> existingValidation = checksheetValidationRepository.findByChecksheet_IdAndValidatorUserId_Id(checksheetValidationDTO.getChecksheetId(), currentUser.get().getId());
            if (existingValidation.isPresent()) {
                throw new CustomException("You have already validated this checksheet", HttpStatus.UNPROCESSABLE_ENTITY);
            }

            // Create new validation record
            ChecksheetValidation validation = new ChecksheetValidation();
            validation.setChecksheet(checksheet.get());
            validation.setStatus(checksheetValidationDTO.getStatus());
            validation.setRemarks(checksheetValidationDTO.getRemarks());
            validation.setValidatorUserId(currentUser.get());
            validation.setValidatedAt(new Date());
            validation.setCreatedBy(currentUser.get());
            checksheetValidationRepository.save(validation);

            ChecksheetValidationHistory validationHistory = new ChecksheetValidationHistory();
            validationHistory.setChecksheet(checksheet.get());
            validationHistory.setStatus(checksheetValidationDTO.getStatus());
            validationHistory.setRemarks(checksheetValidationDTO.getRemarks());
            validationHistory.setValidatorUserId(currentUser.get());
            validationHistory.setValidatedAt(new Date());
            validationHistory.setVersion(checksheet.get().getValidateOrApproveVersion() == null ? 1 : checksheet.get().getValidateOrApproveVersion());
            checksheetValidationHistoryRepository.save(validationHistory);

            if (Objects.equals(checksheetValidationDTO.getStatus(), ChecksheetValidationStatusType.INVALIDATED)) {
                checksheet.get().setStatus(ChecksheetStatusType.INVALIDATED);
                checksheet.get().setWaitingUserIds(List.of(checksheet.get().getPreparerUser().getId()));
                checksheetRepository.save(checksheet.get());
                //Send mail to Preparor
                utilityService.sendEmail(List.of(checksheet.get().getPreparerUser().getId()), EmailTemplate.INVALIDATED, checksheet.get(),List.of(currentUser.get().getId()));
            }

            // Check if all validators have validated
            List<ChecksheetValidation> allValidations = checksheetValidationRepository.findByChecksheet_Id(checksheetValidationDTO.getChecksheetId());

            if (allValidations.size() == checksheet.get().getValidatorUserIds().size()) {
                // All validators have validated - check if any invalidated
                boolean hasInvalidated = allValidations.stream().anyMatch(v -> ChecksheetValidationStatusType.INVALIDATED.equals(v.getStatus()));

                // Update checksheet status
                checksheet.get().setStatus(hasInvalidated ? ChecksheetStatusType.INVALIDATED : ChecksheetStatusType.VALIDATED);
                if(!hasInvalidated) {
                    checksheetApprovalRepository.deleteAllByChecksheet_Id(checksheet.get().getId());
                }
                checksheet.get().setWaitingUserIds(checksheet.get().getApproverUserIds());
                checksheetRepository.save(checksheet.get());
                //Send mail to Approver
                utilityService.sendEmail(checksheet.get().getApproverUserIds(), EmailTemplate.VALIDATED, checksheet.get(), checksheet.get().getValidatorUserIds());
            }else{
                List<Long> waitingApproverUserIds = checksheet.get().getWaitingUserIds();
                waitingApproverUserIds.removeIf(userId -> userId.equals(currentUser.get().getId()));
                checksheet.get().setWaitingUserIds(waitingApproverUserIds);
                checksheetRepository.save(checksheet.get());
            }
            return new ResponseDTO<>(true, "Checksheet validation added successfully");
        } catch (CustomException ce) {
            ce.printStackTrace();
            throw ce;
        } catch (Exception e) {
            e.printStackTrace();
            throw new CustomException("Something went wrong", HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    @Override
    public ResponseDTO<?> getChecksheetValidation(ChecksheetValidationDTO checksheetValidationDTO) throws CustomException {
        try {
            if (Objects.isNull(checksheetValidationDTO) || Objects.isNull(checksheetValidationDTO.getChecksheetId())) {
                throw new CustomException("Please provide checksheetId", HttpStatus.UNPROCESSABLE_ENTITY);
            }
            Optional<Checksheet> checksheet = checksheetRepository.findById(checksheetValidationDTO.getChecksheetId());
            checksheet.orElseThrow(() -> new CustomException("Invalid checksheetId", HttpStatus.UNPROCESSABLE_ENTITY));
//            List<ChecksheetValidationDTO> checksheetValidatorByChecksheetId = `checksheetValidationDAO`.getChecksheetValidatorByChecksheetId(checksheetValidationDTO.getChecksheetId());
            List<ChecksheetValidationHistoryDTO> checksheetValidatorHistoryByChecksheetId = checksheetValidationHistoryDAO.getChecksheetValidatorHistoryByChecksheetId(checksheetValidationDTO.getChecksheetId());
            Optional<User> currentUser = utilityService.getCurrentLoggedInUser();

            Map<String, Object> response = new HashMap<>();
            Map<String, List<ChecksheetValidationHistoryDTO>> groupedHistory = checksheetValidatorHistoryByChecksheetId.stream()
                    .collect(Collectors.groupingBy(
                            history -> history.getFirstName() + " " + history.getLastName(),
                            LinkedHashMap::new,
                            Collectors.toList()
                    ));
            response.put("checksheetValidatorHistory", groupedHistory);
            response.put("checksheetStatus", checksheet.get().getStatus());

            if(checksheet.isPresent() && Objects.equals(checksheet.get().getStatus(), ChecksheetStatusType.SUBMITTED_FOR_VALIDATE)) {

                // Check if current user has a validation record for the current version
                if (checksheetValidatorHistoryByChecksheetId != null && !checksheetValidatorHistoryByChecksheetId.isEmpty()) {
                    Boolean isValidatorComment = false;
                    isValidatorComment = checksheetValidatorHistoryByChecksheetId.stream()
                            .anyMatch(history ->
                                    Objects.equals(history.getValidatorUserId(), currentUser.get().getId()) &&
                                            Objects.equals(history.getVersion(), checksheet.get().getValidateOrApproveVersion())
                            );
                    response.put("isValidatorComment", isValidatorComment);
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
