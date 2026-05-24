package com.checkSheet.service;

import com.checkSheet.DAO.UserChecksheetValidationHistoryDAO;
import com.checkSheet.DTO.UserChecksheetValidationDTO;
import com.checkSheet.DTO.UserChecksheetValidationHistoryDTO;
import com.checkSheet.DTO.response.ResponseDTO;
import com.checkSheet.constant.ChecksheetDataValidationStatusType;
import com.checkSheet.constant.UserChecksheetStatusType;
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
public class UserChecksheetValidationServiceImpl implements UserChecksheetValidationService {

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private UserChecksheetValidationRepository userChecksheetValidationRepository;

    @Autowired
    private UserChecksheetValidationHistoryRepository userChecksheetValidationHistoryRepository;

    @Autowired
    private UtilityService utilityService;

    @Autowired
    private UserChecksheetRepository userChecksheetRepository;

    @Autowired
    private UserChecksheetValidationHistoryDAO userChecksheetValidationHistoryDAO;

    @Autowired
    private ChecksheetRepository checksheetRepository;

    @Transactional(rollbackFor = Exception.class)
    @Override
    public ResponseDTO<?> addUserChecksheetValidation(UserChecksheetValidationDTO userChecksheetValidationDTO) throws CustomException {
        try {
            if (Objects.isNull(userChecksheetValidationDTO) || 
                Objects.isNull(userChecksheetValidationDTO.getInspectionId()) || 
                Objects.isNull(userChecksheetValidationDTO.getStatus()) || 
                userChecksheetValidationDTO.getStatus().toString().isEmpty() || 
                Objects.isNull(userChecksheetValidationDTO.getRemarks()) || 
                userChecksheetValidationDTO.getRemarks().trim().isEmpty()) {
                throw new CustomException("Please provide userChecksheetId, status and remarks", HttpStatus.UNPROCESSABLE_ENTITY);
            }

            Optional<User> currentUser = utilityService.getCurrentLoggedInUser();
            if (currentUser.isEmpty()) {
                throw new CustomException("User not found", HttpStatus.UNAUTHORIZED);
            }

            Optional<Inspection> userChecksheet = userChecksheetRepository.findById(userChecksheetValidationDTO.getInspectionId());
            if (!userChecksheet.isPresent()) {
                throw new CustomException("Invalid userChecksheetId", HttpStatus.UNPROCESSABLE_ENTITY);
            }
            // Check if checksheet status is VALIDATED
            if (!Objects.equals("SUBMITTED", userChecksheet.get().getStatus())) {
                throw new CustomException("User checksheet is not submitted for approval", HttpStatus.UNPROCESSABLE_ENTITY);
            }
            Inspection usrChks = userChecksheet.get();
            Checksheet chks = usrChks.getChecksheet();
//            List<UserChecksheetValidationHistoryDTO> validationHist = userChecksheetValidationHistoryDAO
//                    .getUserChecksheetValidatorHistoryByInspectionId(userChecksheet.get().getId());
            List<UserChecksheetValidationHistory> validationHist = userChecksheetValidationHistoryRepository.findByInspection_IdAndVersion(usrChks.getId(),usrChks.getSubmissionVersion());
            if (validationHist != null && !validationHist.isEmpty()) {
                Boolean isValidatorComment = false;
                isValidatorComment = validationHist.stream()
                        .anyMatch(history ->
                                Objects.equals(history.getDataValidatorUserId(), currentUser.get().getId())
                        );
                if(isValidatorComment){
                    throw new CustomException("You have already validated this user checksheet", HttpStatus.UNPROCESSABLE_ENTITY);
                }
            }

            UserChecksheetValidation validation;
            // Post-V1.28: review state anchors on the INSPECTION (a single
            // submission), not on the underlying template. Keying by
            // checksheet_id collides when two inspections of the same template
            // are reviewed by the same validator. C1 fix: key on inspection_id.
            Optional<UserChecksheetValidation> existingValidation = userChecksheetValidationRepository
                    .findByInspection_IdAndDataValidatorUserId_IdAndDeletedAtIsNull(usrChks.getId(), currentUser.get().getId());
            if (existingValidation.isPresent()) {
                validation = existingValidation.get();
            }else{
                validation = new UserChecksheetValidation();
            }
            // Create new validation record
            validation.setInspection(usrChks);
            validation.setChecksheet(chks);
            validation.setStatus(userChecksheetValidationDTO.getStatus());
            validation.setRemarks(userChecksheetValidationDTO.getRemarks());
            validation.setDataValidatorUserId(currentUser.get());
            validation.setValidatedAt(new Date());
            validation.setCreatedBy(currentUser.get());
            userChecksheetValidationRepository.save(validation);

            UserChecksheetValidationHistory validationHistory = new UserChecksheetValidationHistory();
            validationHistory.setChecksheet(chks);
            validationHistory.setInspection(usrChks);
            validationHistory.setStatus(userChecksheetValidationDTO.getStatus());
            validationHistory.setRemarks(userChecksheetValidationDTO.getRemarks());
            validationHistory.setDataValidatorUserId(currentUser.get());
            validationHistory.setValidatedAt(new Date());
            validationHistory.setVersion(userChecksheet.get().getSubmissionVersion() == null ? 1 :
                                       userChecksheet.get().getSubmissionVersion());
            userChecksheetValidationHistoryRepository.save(validationHistory);
            userChecksheetValidationHistoryRepository.flush();

            validationHist = userChecksheetValidationHistoryRepository.findByInspection_IdAndVersion(usrChks.getId(),usrChks.getSubmissionVersion());
            if(Objects.equals(userChecksheetValidationDTO.getStatus(), ChecksheetDataValidationStatusType.INVALIDATED)){
                // Validator declined. The V1.28 status enum exposes a single
                // terminal-decline state (DECLINED) that captures both legacy
                // INVALIDATED and NOT_APPROVED. Lineage is preserved on the
                // history table (this row's *_history entry records the
                // validator path). The operator's next createOrUpdate moves
                // the inspection back to IN_PROGRESS / SUBMITTED via the
                // ordinary save path — no reopen-transition guard needed.
                usrChks.setStatus(UserChecksheetStatusType.DECLINED.getValue());
                usrChks.setWaitingUserIds(new ArrayList<>());
                //Send mail to Operator --> NA
//                utilityService.sendEmail(List.of(usrChks.getCreatedBy().getId()), EmailTemplate.USER_CHKS_INVALIDATED, chks,List.of(currentUser.get().getId()));
            }else if(validationHist.size() >= chks.getDataValidatorUserIds().size()){
                usrChks.setStatus(ChecksheetDataValidationStatusType.VALIDATED.getValue());
                usrChks.setWaitingUserIds(chks.getDataApproverUserIds());
                //Send mail to Data Approver
                utilityService.sendEmail(chks.getDataApproverUserIds(), EmailTemplate.USER_CHKS_VALIDATED, chks,chks.getDataApproverUserIds());
            }else{
                List<Long> waitingApproverUserIds = usrChks.getWaitingUserIds();
                waitingApproverUserIds.removeIf(userId -> userId.equals(currentUser.get().getId()));
                usrChks.setWaitingUserIds(waitingApproverUserIds);
            }
            userChecksheetRepository.save(usrChks);
            return new ResponseDTO<>(true, "User checksheet validation added successfully");
        } catch (CustomException ce) {
            ce.printStackTrace();
            throw ce;
        } catch (Exception e) {
            e.printStackTrace();
            throw new CustomException("Something went wrong", HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    @Override
    public ResponseDTO<?> getUserChecksheetValidation(UserChecksheetValidationDTO userChecksheetValidationDTO) throws CustomException {
        try {
            if (Objects.isNull(userChecksheetValidationDTO) || Objects.isNull(userChecksheetValidationDTO.getInspectionId())) {
                throw new CustomException("Please provide userChecksheetId", HttpStatus.UNPROCESSABLE_ENTITY);
            }

            Optional<Inspection> userChecksheet = userChecksheetRepository.findById(userChecksheetValidationDTO.getInspectionId());
            if (userChecksheet.isEmpty()) {
                throw new CustomException("Invalid userChecksheetId", HttpStatus.UNPROCESSABLE_ENTITY);
            }
            Optional<Checksheet> checksheet = checksheetRepository.findById(userChecksheet.get().getChecksheet().getId());
            checksheet.orElseThrow(() -> new CustomException("Invalid checksheetId", HttpStatus.UNPROCESSABLE_ENTITY));
            List<UserChecksheetValidationHistoryDTO> validationHistory = userChecksheetValidationHistoryDAO
                    .getUserChecksheetValidatorHistoryByInspectionId(userChecksheet.get().getId());
            Optional<User> currentUser = utilityService.getCurrentLoggedInUser();
            Map<String, Object> response = new HashMap<>();
            Map<String, List<UserChecksheetValidationHistoryDTO>> groupedHistory = validationHistory.stream()
                    .collect(Collectors.groupingBy(
                            history -> history.getFirstName() + " " + history.getLastName(),
                            LinkedHashMap::new,
                            Collectors.toList()
                    ));
            response.put("userChecksheetValidatorHistory", groupedHistory);
            response.put("checksheetStatus", userChecksheet.get().getStatus());

            if (!validationHistory.isEmpty()) {
                boolean isValidatorComment;
                isValidatorComment = validationHistory.stream()
                        .anyMatch(history ->
                                Objects.equals(history.getDataValidatorUserId(), currentUser.get().getId()) &&
                                        Objects.equals(history.getVersion(), userChecksheet.get().getSubmissionVersion() == null ? null :
                                            userChecksheet.get().getSubmissionVersion().longValue())
                        );
                response.put("isDataValidatorComment", isValidatorComment);
            }
            
            return new ResponseDTO<>(true, "User checksheet validation history fetched successfully", response);
        } catch (CustomException ce) {
            ce.printStackTrace();
            throw ce;
        } catch (Exception e) {
            e.printStackTrace();
            throw new CustomException("Something went wrong", HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }
}
