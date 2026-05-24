package com.checkSheet.service;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import com.checkSheet.DAO.UserChecksheetDAO;
import com.checkSheet.constant.ChksQuestionResultType;
import com.checkSheet.entity.*;
import com.checkSheet.event.UserChecksheetApprovedEvent;
import com.checkSheet.helper.DateHelper;
import com.checkSheet.repository.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.checkSheet.DAO.UserChecksheetApprovalHistoryDAO;
import com.checkSheet.DAO.UsrChecksheetAnsJudgementDAO;
import com.checkSheet.DTO.UserChecksheetApprovalDTO;
import com.checkSheet.DTO.UserChecksheetApprovalHistoryDTO;
import com.checkSheet.DTO.response.ResponseDTO;
import com.checkSheet.constant.ChecksheetDataApprovalStatusType;
import com.checkSheet.exception.CustomException;
import com.checkSheet.service.Email.EmailService;

@Service
public class UserChecksheetApprovalServiceImpl implements UserChecksheetApprovalService {

    @Autowired
    private ChecksheetRepository checksheetRepository;

    @Autowired
    private UtilityService utilityService;

    @Autowired
    private UserChecksheetApprovalRepository userChecksheetApprovalRepository;

    @Autowired
    private UserChecksheetApprovalHistoryRepository userChecksheetApprovalHistoryRepository;

    @Autowired
    private UserChecksheetApprovalHistoryDAO userChecksheetApprovalHistoryDAO;

    @Autowired
    private UserChecksheetRepository userChecksheetRepository;

    @Autowired
    private UsrChksheetAnsJudgementRepository usrChksheetAnsJudgementRepository;

    @Autowired
    private EmailService emailService;

    /** Publishes UserChecksheetApprovedEvent when an audit instance flips to
     *  APPROVED. InterventionInstantiationListener consumes it to (a) create
     *  Improvement Plans on audit-side approvals and (b) evaluate plan
     *  completion on re-inspection-wave approvals. */
    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UsrChecksheetAnsJudgementDAO usrChecksheetAnsJudgementDAO;

    @Autowired
    private ChksQuestionRepository chksQuestionRepository;

    @Autowired
    private ChksQuestionResultRepository chksQuestionResultRepository;

    @Autowired
    private UserChecksheetAnswerRepository userChecksheetAnswerRepository;

    @Autowired
    private LovDataRepository lovDataRepository;
    @Autowired
    private UserChecksheetDAO userChecksheetDAO;

    @Transactional(rollbackFor = Exception.class)
    @Override
    public ResponseDTO<?> addUserChecksheetApproval(UserChecksheetApprovalDTO userChecksheetApprovalDTO) throws CustomException {
        try {
            if (Objects.isNull(userChecksheetApprovalDTO) || 
                Objects.isNull(userChecksheetApprovalDTO.getInspectionId()) || 
                Objects.isNull(userChecksheetApprovalDTO.getStatus()) || 
                userChecksheetApprovalDTO.getStatus().toString().isEmpty() || 
                Objects.isNull(userChecksheetApprovalDTO.getRemarks()) || 
                userChecksheetApprovalDTO.getRemarks().trim().isEmpty()) {
                throw new CustomException("Please provide userChecksheetId, status and remarks", HttpStatus.UNPROCESSABLE_ENTITY);
            }

            Optional<User> currentUser = utilityService.getCurrentLoggedInUser();
            if (!currentUser.isPresent()) {
                throw new CustomException("User not found", HttpStatus.UNAUTHORIZED);
            }

            Optional<Inspection> userChecksheet = userChecksheetRepository.findById(userChecksheetApprovalDTO.getInspectionId());
            if (!userChecksheet.isPresent()) {
                throw new CustomException("Invalid userChecksheetId", HttpStatus.UNPROCESSABLE_ENTITY);
            }

            // Check if checksheet status is VALIDATED
            if (!Objects.equals("VALIDATED", userChecksheet.get().getStatus())) {
                throw new CustomException("User checksheet is not submitted for approval", HttpStatus.UNPROCESSABLE_ENTITY);
            }

            Inspection usrChks = userChecksheet.get();
            Checksheet chks = usrChks.getChecksheet();
            List<UserChecksheetApprovalHistory> approvalHist = userChecksheetApprovalHistoryRepository
                    .findByInspection_IdAndVersion(usrChks.getId(),usrChks.getSubmissionVersion());
            if (approvalHist != null && !approvalHist.isEmpty()) {
                Boolean isDataApproverComment = false;
                isDataApproverComment = approvalHist.stream()
                        .anyMatch(history ->
                                Objects.equals(history.getDataApproverUserId(), currentUser.get().getId())
                        );
                if(isDataApproverComment){
                    throw new CustomException("You have already approved this user checksheet", HttpStatus.UNPROCESSABLE_ENTITY);
                }
            }

            // Create new approval record
            // Post-V1.28: review state anchors on the INSPECTION (a single
            // submission), not on the underlying template. Keying by
            // checksheet_id collides when two inspections of the same template
            // are reviewed by the same approver. C1 fix: key on inspection_id.
            Optional<UserChecksheetApproval> existingApproval = userChecksheetApprovalRepository
                .findByInspection_IdAndDataApproverUserId_IdAndDeletedAtIsNull(usrChks.getId(), currentUser.get().getId());
            UserChecksheetApproval approval;
            if (existingApproval.isPresent()) {
                approval = existingApproval.get();
            }else{
                approval = new UserChecksheetApproval();
            }
            approval.setInspection(usrChks);
            approval.setChecksheet(chks);
            approval.setStatus(userChecksheetApprovalDTO.getStatus());
            approval.setRemarks(userChecksheetApprovalDTO.getRemarks());
            approval.setDataApproverUserId(currentUser.get());
            approval.setApprovedAt(new Date());
            approval.setCreatedBy(currentUser.get());
            userChecksheetApprovalRepository.save(approval);

            UserChecksheetApprovalHistory approvalHistory = new UserChecksheetApprovalHistory();
            approvalHistory.setChecksheet(chks);
            approvalHistory.setInspection(usrChks);
            approvalHistory.setStatus(userChecksheetApprovalDTO.getStatus());
            approvalHistory.setRemarks(userChecksheetApprovalDTO.getRemarks());
            approvalHistory.setDataApproverUserId(currentUser.get());
            approvalHistory.setApprovedAt(new Date());
            approvalHistory.setVersion(usrChks.getSubmissionVersion() == null ? 1 :
                    usrChks.getSubmissionVersion());
            userChecksheetApprovalHistoryRepository.save(approvalHistory);
            userChecksheetApprovalHistoryRepository.flush();

            approvalHist = userChecksheetApprovalHistoryRepository.findByInspection_IdAndVersion(usrChks.getId(),usrChks.getSubmissionVersion());
            if(Objects.equals(userChecksheetApprovalDTO.getStatus(), ChecksheetDataApprovalStatusType.NOT_APPROVED)) {
                // Approver declined. Stored status is DECLINED — the V1.28
                // terminal-decline state shared with the validator path.
                // Lineage (validator vs approver decline) is preserved on the
                // *_history table for this inspection. Operator's next save
                // moves the status forward via the regular createOrUpdate
                // path; no reopen-transition guard exists today.
                usrChks.setStatus(com.checkSheet.constant.UserChecksheetStatusType.DECLINED.getValue());
                usrChks.setWaitingUserIds(new ArrayList<>());
            }else if(approvalHist.size() >= chks.getDataApproverUserIds().size()){
                usrChks.setStatus(ChecksheetDataApprovalStatusType.APPROVED.getValue());
                usrChks.setWaitingUserIds(new ArrayList<>());

                // Publish domain event so intervention-instantiation (audit
                // approvals) and plan-completion (re-inspection-wave approvals)
                // listeners can react. Pulled from the already-loaded entities
                // so we don't re-query.
                {
                    // Post-V1.28: the inspection IS its own assignment. Kind
                    // distinguishes which event fields to populate.
                    boolean isAudit        = "AUDIT".equals(usrChks.getKind());
                    boolean isIntervention = "INTERVENTION".equals(usrChks.getKind());
                    Long auditId = isAudit && usrChks.getAudit() != null ? usrChks.getAudit().getId()
                                 : isIntervention && usrChks.getIntervention() != null
                                       && usrChks.getIntervention().getAudit() != null
                                   ? usrChks.getIntervention().getAudit().getId() : null;
                    Long locId   = usrChks.getAuditeeLocation() != null ? usrChks.getAuditeeLocation().getId() : null;
                    Long aaId    = isAudit        ? usrChks.getId() : null;
                    Long iaId    = isIntervention ? usrChks.getId() : null;
                    if (aaId != null || iaId != null) {
                        eventPublisher.publishEvent(new UserChecksheetApprovedEvent(
                            this, usrChks.getId(), auditId, locId, aaId, iaId));
                    }
                }
                List<Long> alertToChecksheetQuestionIds = new ArrayList<>();
                Optional<LovData> lovData = lovDataRepository.findByName("multipleAnswerSeprator");
                String multipleAnswerSeparator = null;
                if(lovData.isPresent()) {
                    multipleAnswerSeparator = lovData.get().getValue();
                }
                if (chks.getAlertToUserIds() != null && !chks.getAlertToUserIds().isEmpty()) {
                    List<UsrChksheetAnsJudgement> notOkJudgements = usrChksheetAnsJudgementRepository
                            .findByInspection_IdAndJudgement(usrChks.getId(), "NOT OK");

                    if (!notOkJudgements.isEmpty()) {
                        // Build HTML table with judgement details
                        StringBuilder htmlContent = new StringBuilder();
                        // Main HTML content

                        // Create an HTML version for email body
                        StringBuilder tableContent = new StringBuilder();
                        tableContent.append("<!DOCTYPE html><html><head><style>");
                        tableContent.append("p{ font-family: Arial, sans-serif; font-size: 20px; }");
                        tableContent.append("table { border-collapse: collapse; width: 100%; margin-top: 20px; font-family: Arial, sans-serif; }");
                        tableContent.append("th { background-color: #f2f2f2; padding: 12px; text-align: left; border: 1px solid #ddd; font-size: 15px; }");
                        tableContent.append("td { padding: 12px; border: 1px solid #ddd; font-size: 14px; }");
                        tableContent.append(".not-ok { color: #ff4444; font-weight: bold; }");
                        tableContent.append("</style></head><body>");
                        htmlContent.append("<p>Dear [Name],<br><br>");
                        htmlContent.append("This is to inform you that the checksheet <b>([UID])</b> has been approved. However, it contains NOT OK judgements. Kindly review and take the necessary actions.<br><br>");
                        htmlContent.append("Check sheet Name : " + chks.getName() +"</p>");

                        tableContent.append("<table>");
                        tableContent.append("<thead><tr>");
                        tableContent.append("<th>Question</th>");
                        tableContent.append("<th>Spec</th>");
                        tableContent.append("<th>Result</th>");
                        tableContent.append("<th>Judgement</th>");
                        tableContent.append("<th>Date of check</th>");
                        tableContent.append("<th>Remarks</th>");
                        tableContent.append("<th>User</th>");
                        tableContent.append("</tr></thead>");
                        tableContent.append("<tbody>");
                        String operatorUser =
                                userChecksheet.get().getOperatorUser() != null ?
                                        (userChecksheet.get().getOperatorUser().getFirstName() != null ?
                                                userChecksheet.get().getOperatorUser().getFirstName() + " " + userChecksheet.get().getOperatorUser().getLastName(): "")
                                        : "";

                        for (UsrChksheetAnsJudgement judgement : notOkJudgements) {
                            Long chksQuestionId = (Long) judgement.getChksQuestion().getId();
                            alertToChecksheetQuestionIds.add(chksQuestionId);
                            String hierarchicalPath = getQuestionHierarchicalPath(chksQuestionId);
                            ChksQuestion question = judgement.getChksQuestion();
                            tableContent.append("<tr>");
                            tableContent.append("<td>").append(hierarchicalPath).append("</td>");
                            tableContent.append("<td>");
                            List<ChksQuestionResult> chksQuestionResultByChksQuestionIdOrderById = chksQuestionResultRepository.findByChksQuestion_IdOrderById(chksQuestionId);
                            for(ChksQuestionResult chksQuestionResult: chksQuestionResultByChksQuestionIdOrderById) {
                                if(!Objects.isNull(chksQuestionResult) && !Objects.isNull(chksQuestionResult.getChksHeader())) {
                                    tableContent.append(chksQuestionResult.getChksHeader().getName());
                                    tableContent.append(":<br>");
                                    tableContent.append(chksQuestionResult.getAnswerType().toString());
                                    if(Objects.equals(chksQuestionResult.getAnswerType(), ChksQuestionResultType.OBJECTIVE)) {
                                        if (chksQuestionResult.getObjectiveType() != null) {
                                            switch (chksQuestionResult.getObjectiveType()) {
                                                case EQUAL_TO:
                                                    tableContent.append(" : = ").append(chksQuestionResult.getUpperLimit());
                                                    break;
                                                case LESS_THAN:
                                                    tableContent.append(" : < ").append(chksQuestionResult.getUpperLimit());
                                                    break;
                                                case LESS_THAN_OR_EQUAL_TO:
                                                    tableContent.append(" : <= ").append(chksQuestionResult.getUpperLimit());
                                                    break;
                                                case GREATER_THAN:
                                                    tableContent.append(" : > ").append(chksQuestionResult.getLowerLimit());
                                                    break;
                                                case GREATER_THAN_OR_EQUAL_TO:
                                                    tableContent.append(" : >= ").append(chksQuestionResult.getLowerLimit());
                                                    break;
                                                case RANGE:
                                                    tableContent.append(" RANGE: ")
                                                            .append(chksQuestionResult.getLowerLimit())
                                                            .append(" - ")
                                                            .append(chksQuestionResult.getUpperLimit());
                                                    break;
                                            }
                                            tableContent.append(" ").append(chksQuestionResult.getUnit() != null ? chksQuestionResult.getUnit() : "");
                                        }
                                    }
                                    tableContent.append("<br>----------<br>");
                                }
                            }
                            tableContent.append("</td>");
                            tableContent.append("<td>");
                            List<UserChecksheetAnswer> userChecksheetAnswerByChksQuestionIdOrderByChksQuestionResultId =
                                    userChecksheetAnswerRepository.findByChksQuestion_IdAndInspection_IdOrderByChksQuestionResult_Id(chksQuestionId, userChecksheet.get().getId());
                            for(UserChecksheetAnswer userChecksheetAnswer: userChecksheetAnswerByChksQuestionIdOrderByChksQuestionResultId) {
                                if(!Objects.isNull(userChecksheetAnswer) && !Objects.isNull(userChecksheetAnswer.getChksQuestionResult())) {
                                    Optional<ChksQuestionResult> chksQuestionResult = chksQuestionResultRepository.findById(userChecksheetAnswer.getChksQuestionResult().getId());
                                    if(chksQuestionResult.isPresent() && !Objects.isNull(chksQuestionResult.get().getChksHeader())) {
                                        tableContent.append(chksQuestionResult.get().getChksHeader().getName());
                                        tableContent.append(":<br>");
                                        if(Objects.equals(chksQuestionResult.get().getAnswerType(), ChksQuestionResultType.SUBJECTIVE_CONDITION)
                                                && !Objects.isNull(userChecksheetAnswer.getChksQuestionRsltOption())) {
                                            tableContent.append(userChecksheetAnswer.getChksQuestionRsltOption().getJudgement());
                                        }
                                        else if(!Objects.isNull(multipleAnswerSeparator) &&
                                                Objects.equals(chksQuestionResult.get().getAnswerType(), ChksQuestionResultType.OBJECTIVE)) {
                                            String useCheckSheetQuestionAnswer = userChecksheetAnswer.getAnswer() != null ? userChecksheetAnswer.getAnswer() : "";
                                            useCheckSheetQuestionAnswer = useCheckSheetQuestionAnswer.replaceAll(multipleAnswerSeparator.trim(), ", ");
                                            tableContent.append(useCheckSheetQuestionAnswer);
                                        } else {
                                            tableContent.append(userChecksheetAnswer.getAnswer() != null ? userChecksheetAnswer.getAnswer() : "");
                                        }
                                        tableContent.append("<br>----------<br>");
                                    }
                                }
                            }
                            tableContent.append("</td>");
                            tableContent.append("<td class='not-ok'>").append(judgement.getJudgement()).append("</td>");
                            tableContent.append("<td>").append(DateHelper.getDateToString(userChecksheet.get().getSubmittedAt(),"dd-MM-yyyy")).append("</td>");
                            tableContent.append("<td>").append(judgement.getRemarks()).append("</td>");
                            tableContent.append("<td>").append(operatorUser).append("</td>");
                            tableContent.append("</tr>");
                        }

                        tableContent.append("</tbody></table>");
                        tableContent.append("</body></html>");

                        // Send email with both HTML content and image attachment
                        for(Long userId : chks.getAlertToUserIds()) {
                            User user = userRepository.findById(userId).orElse(null);
                            if(user != null && !Objects.equals(user.getEmail(), null) && !Objects.equals(user.getEmail(), "")) {
                                String finalContent = htmlContent.toString().replace("[Name]", user.getFirstName() + " " + user.getLastName())
                                        .replace("[UID]", chks.getUid() != null ? chks.getUid() : "");
                                emailService.sendEmail(
                                        user.getEmail(),
                                        "Alert: NOT OK Judgements in Checksheet " + (Objects.isNull(chks.getUid())? "" :" "+ chks.getUid() + " "),
                                        finalContent + tableContent.toString(),
                                        null,
                                        null,
                                        null
                                );
                            }
                        }
                    }
                }
                    // Check for escalation conditions
                if (chks.getEscalateToUserIds() != null && !chks.getEscalateToUserIds().isEmpty()) {
                    Long escalationDays = chks.getEscalationGuidelinesDays();
                    boolean isDataExist = false;
                    if (escalationDays != null) {
    //                    List<Map<String, Object>> escalationCases = usrChecksheetAnsJudgementDAO
    //                        .getConsecutiveNotOkJudgements(chks.getId(), escalationDays, userChecksheet.get().getOperatorUser().getId());

                        List<Long> escalateToUserCheckList = userChecksheetDAO.getEscalateToUserCheckList(chks.getId(), escalationDays);
                        if (!escalateToUserCheckList.isEmpty()) {
                            // Build HTML table with judgement details
                            StringBuilder htmlContent = new StringBuilder();
                            StringBuilder tableContent = new StringBuilder();
                            tableContent.append("<!DOCTYPE html><html><head><style>");
                            tableContent.append("p{ font-family: Arial, sans-serif; font-size: 20px; }");
                            tableContent.append("table { border-collapse: collapse; width: 100%; margin-top: 20px; font-family: Arial, sans-serif; }");
                            tableContent.append("th { background-color: #f2f2f2; padding: 12px; text-align: left; border: 1px solid #ddd; font-size: 15px; }");
                            tableContent.append("td { padding: 12px; border: 1px solid #ddd; font-size: 14px; }");
                            tableContent.append(".not-ok { color: #ff4444; font-weight: bold; }");
                            tableContent.append("</style></head><body>");
                            htmlContent.append("<p>Dear [Name],<br><br>");
                            htmlContent.append("This is to inform you that the following questions have received ");
                            htmlContent.append(escalationDays).append(" consecutive NOT OK judgements and require your immediate attention: <br><br>");
                            htmlContent.append("Please review and take the necessary action at the earliest.<br><br>");
                            htmlContent.append("Check sheet Name : " + chks.getName() + "</p>");

                            tableContent.append("<table>");
                            tableContent.append("<thead><tr>");
                            tableContent.append("<th>Spec</th>");
                            tableContent.append("<th>Date of check</th>");
                            tableContent.append("<th>Result</th>");
                            tableContent.append("<th>Judgement</th>");
                            tableContent.append("</tr></thead><tbody>");

                            for(Long questionId: alertToChecksheetQuestionIds) {

                                List<UsrChksheetAnsJudgement> notOkJudgements = usrChksheetAnsJudgementRepository
                                        .findByInspection_IdInAndJudgementAndChksQuestionId(escalateToUserCheckList, "NOT OK", questionId);
                                List<Long> allNotOkJudgements = notOkJudgements.stream().map(UsrChksheetAnsJudgement::getId).collect(Collectors.toList());
                                System.out.println("allNotOkJudgements : " + allNotOkJudgements);
                                if(notOkJudgements.size() == escalationDays) {
                                    isDataExist = true;
                                    String hierarchicalPath = getQuestionHierarchicalPath(questionId);
                                    tableContent.append("<tr>");
                                    tableContent.append("<td>").append(hierarchicalPath).append("</td>");

                                    List<ChksQuestionResult> chksQuestionResultByChksQuestionIdOrderById = chksQuestionResultRepository.findByChksQuestion_IdOrderById(questionId);

                                    tableContent.append("<td>");
                                    for(ChksQuestionResult chksQuestionResult: chksQuestionResultByChksQuestionIdOrderById) {
                                        if(!Objects.isNull(chksQuestionResult) && !Objects.isNull(chksQuestionResult.getChksHeader())) {
                                            tableContent.append(chksQuestionResult.getChksHeader().getName());
                                            tableContent.append(":<br>");
                                            tableContent.append(chksQuestionResult.getAnswerType().toString());
                                            if(Objects.equals(chksQuestionResult.getAnswerType(), ChksQuestionResultType.OBJECTIVE)) {
                                                if (chksQuestionResult.getObjectiveType() != null) {
                                                    switch (chksQuestionResult.getObjectiveType()) {
                                                        case EQUAL_TO:
                                                            tableContent.append(" : = ").append(chksQuestionResult.getUpperLimit());
                                                            break;
                                                        case LESS_THAN:
                                                            tableContent.append(" : < ").append(chksQuestionResult.getUpperLimit());
                                                            break;
                                                        case LESS_THAN_OR_EQUAL_TO:
                                                            tableContent.append(" : <= ").append(chksQuestionResult.getUpperLimit());
                                                            break;
                                                        case GREATER_THAN:
                                                            tableContent.append(" : > ").append(chksQuestionResult.getLowerLimit());
                                                            break;
                                                        case GREATER_THAN_OR_EQUAL_TO:
                                                            tableContent.append(" : >= ").append(chksQuestionResult.getLowerLimit());
                                                            break;
                                                        case RANGE:
                                                            tableContent.append(" RANGE: ")
                                                                    .append(chksQuestionResult.getLowerLimit())
                                                                    .append(" - ")
                                                                    .append(chksQuestionResult.getUpperLimit());
                                                            break;
                                                    }
                                                    tableContent.append(" ").append(chksQuestionResult.getUnit() != null ? chksQuestionResult.getUnit() : "");
                                                }
                                            }
                                            tableContent.append("<br>----------<br>");
                                        }
                                    }
                                    tableContent.append("</td>");

                                    tableContent.append("<td>");

                                    tableContent.append("<table>");
                                    tableContent.append("<thead><tr>");
                                    tableContent.append("<th>Answer</th>");
                                    tableContent.append("<th>Date of check</th>");
                                    tableContent.append("<th>Remarks</th>");
                                    tableContent.append("<th>User</th>");
                                    tableContent.append("</tr></thead><tbody>");
                                    for(Long escalationToUserCheck : escalateToUserCheckList) {
                                        List<UserChecksheetAnswer> userChecksheetAnswerByChksQuestionIdOrderByChksQuestionResultId =
                                                userChecksheetAnswerRepository.findByChksQuestion_IdAndInspection_IdOrderByChksQuestionResult_Id(questionId, escalationToUserCheck);

                                        for (UserChecksheetAnswer userChecksheetAnswer : userChecksheetAnswerByChksQuestionIdOrderByChksQuestionResultId) {

                                            String operatorUser =
                                                    userChecksheetAnswer.getInspection().getOperatorUser() != null ?
                                                            (userChecksheetAnswer.getInspection().getOperatorUser().getFirstName() != null ?
                                                                    userChecksheetAnswer.getInspection().getOperatorUser().getFirstName() + " " + userChecksheetAnswer.getInspection().getOperatorUser().getLastName(): "")
                                                            : "";
                                            tableContent.append("<tr>");
                                            tableContent.append("<td  rowspan=\"" + userChecksheetAnswerByChksQuestionIdOrderByChksQuestionResultId + "\" >");
                                            if (!Objects.isNull(userChecksheetAnswer) && !Objects.isNull(userChecksheetAnswer.getChksQuestionResult())) {
                                                Optional<ChksQuestionResult> chksQuestionResult = chksQuestionResultRepository.findById(userChecksheetAnswer.getChksQuestionResult().getId());
                                                if (chksQuestionResult.isPresent() && !Objects.isNull(chksQuestionResult.get().getChksHeader())) {
                                                    tableContent.append(chksQuestionResult.get().getChksHeader().getName());
                                                    tableContent.append(":<br>");
                                                    if (Objects.equals(chksQuestionResult.get().getAnswerType(), ChksQuestionResultType.SUBJECTIVE_CONDITION)
                                                            && !Objects.isNull(userChecksheetAnswer.getChksQuestionRsltOption())) {
                                                        tableContent.append(userChecksheetAnswer.getChksQuestionRsltOption().getJudgement());
                                                    } else if (!Objects.isNull(multipleAnswerSeparator) &&
                                                            Objects.equals(chksQuestionResult.get().getAnswerType(), ChksQuestionResultType.OBJECTIVE)) {
                                                        String useCheckSheetQuestionAnswer = userChecksheetAnswer.getAnswer() != null ? userChecksheetAnswer.getAnswer() : "";
                                                        useCheckSheetQuestionAnswer = useCheckSheetQuestionAnswer.replaceAll(multipleAnswerSeparator.trim(), ", ");
                                                        tableContent.append(useCheckSheetQuestionAnswer);
                                                    } else {
                                                        tableContent.append(userChecksheetAnswer.getAnswer() != null ? userChecksheetAnswer.getAnswer() : "");
                                                    }
                                                    tableContent.append("<br>----------<br>");
                                                }

                                            }
                                            tableContent.append("</td>");
                                            tableContent.append("<td>").append(DateHelper.getDateToString(userChecksheetAnswer.getInspection().getSubmittedAt(),"dd-MM-yyyy"))
                                                    .append("</td>");
                                            Optional<UsrChksheetAnsJudgement> usrChksheetAnsJudgementByInspectionIdAndChksQuestionId =
                                                    usrChksheetAnsJudgementRepository.findByInspectionIdAndChksQuestionId(userChecksheetAnswer.getInspection().getId(), questionId);
                                            tableContent.append("<td>");
                                            if(usrChksheetAnsJudgementByInspectionIdAndChksQuestionId.isPresent()) {
                                                String remarks = usrChksheetAnsJudgementByInspectionIdAndChksQuestionId.get().getRemarks() != null ?
                                                        usrChksheetAnsJudgementByInspectionIdAndChksQuestionId.get().getRemarks() : "";
                                                tableContent.append(remarks);
                                            }
                                            tableContent.append("</td>");
                                            tableContent.append("<td>").append(operatorUser).append("</td>");
                                            tableContent.append("</tr>");
                                        }
                                    }
                                    tableContent.append("</tbody></table>");
                                    tableContent.append("</td>");

                                    tableContent.append("<td class='not-ok'>").append("NOT OK").append("</td>");

                                }

                            }

    //                        for (Map<String, Object> escalation : escalationCases) {
    //                            Long chksQuestionId = (Long) escalation.get("chksQuestionId");
    //                            String hierarchicalPath = getQuestionHierarchicalPath(chksQuestionId);
    //                            Long userId = (Long) escalation.get("userId");
    //                            User judgementUser = userRepository.findById(userId).orElse(null);
    //                            String userName = judgementUser != null ?
    //                                judgementUser.getFirstName() + " " + judgementUser.getLastName() : "Unknown";
    //
    //                            tableContent.append("<tr>");
    //                            tableContent.append("<td>").append(hierarchicalPath).append("</td>");
    //                            tableContent.append("<td>").append(userName).append("</td>");
    //                            tableContent.append("</tr>");
    //                        }

                            tableContent.append("</tbody></table>");
                            tableContent.append("</body></html>");

                            // Send email to each escalation user
                            if(isDataExist) {
                                for (Long userId : chks.getEscalateToUserIds()) {
                                    User user = userRepository.findById(userId).orElse(null);
                                    if (user != null && user.getEmail() != null && !user.getEmail().isEmpty()) {
                                        String finalContent = htmlContent.toString()
                                                .replace("[Name]", user.getFirstName() + " " + user.getLastName())
                                                .replace("[UID]", chks.getUid() != null ? chks.getUid() : "");

                                        emailService.sendEmail(
                                                user.getEmail(),
                                                "Escalation Alert: Consecutive NOT OK Judgements in Checksheet " +
                                                        (chks.getUid() != null ? " " + chks.getUid() + " " : ""),
                                                finalContent + tableContent.toString(),
                                                null,
                                                null,
                                                null
                                        );
                                    }
                                }
                            }
                        }
                    }
                }
                
                // Get alert user emails and send notifications if there are NOT OK judgements

            }else{
                List<Long> waitingApproverUserIds = usrChks.getWaitingUserIds();
                waitingApproverUserIds.removeIf(userId -> userId.equals(currentUser.get().getId()));
                usrChks.setWaitingUserIds(waitingApproverUserIds);
            }
            userChecksheetRepository.save(usrChks);
            return new ResponseDTO<>(true, "User checksheet approval added successfully");
        } catch (CustomException ce) {
            ce.printStackTrace();
            throw ce;
        } catch (Exception e) {
            e.printStackTrace();
            throw new CustomException("Something went wrong", HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    @Override
    public ResponseDTO<?> getUserChecksheetApproval(UserChecksheetApprovalDTO userChecksheetApprovalDTO) throws CustomException {
        try {
            if (Objects.isNull(userChecksheetApprovalDTO) || Objects.isNull(userChecksheetApprovalDTO.getInspectionId())) {
                throw new CustomException("Please provide userChecksheetId", HttpStatus.UNPROCESSABLE_ENTITY);
            }

            Optional<Inspection> userChecksheet = userChecksheetRepository.findById(userChecksheetApprovalDTO.getInspectionId());
            if (userChecksheet.isEmpty()) {
                throw new CustomException("Invalid userChecksheetId", HttpStatus.UNPROCESSABLE_ENTITY);
            }
            Map<String, Object> response = new HashMap<>();
            Optional<User> currentUser = utilityService.getCurrentLoggedInUser();
            List<UserChecksheetApprovalHistoryDTO> approvalHistory = userChecksheetApprovalHistoryDAO
                .getUserChecksheetApproverHistoryByInspectionId(userChecksheet.get().getId());
            if (approvalHistory != null && !approvalHistory.isEmpty()) {
                boolean isDataApproverComment;
                isDataApproverComment = approvalHistory.stream()
                        .anyMatch(history ->
                                Objects.equals(history.getDataApproverUserId(), currentUser.get().getId()) &&
                                        Objects.equals(history.getVersion(), userChecksheet.get().getSubmissionVersion())
                        );
                response.put("isDataApproverComment", isDataApproverComment);
            }
            Optional<Checksheet> checksheet = checksheetRepository.findById(userChecksheet.get().getChecksheet().getId());
            checksheet.orElseThrow(() -> new CustomException("Invalid checksheetId", HttpStatus.UNPROCESSABLE_ENTITY));

            Map<String, List<UserChecksheetApprovalHistoryDTO>> groupedHistory = approvalHistory.stream()
                    .collect(Collectors.groupingBy(
                            history -> history.getFirstName() + " " + history.getLastName(),
                            LinkedHashMap::new,
                            Collectors.toList()
                    ));
            response.put("userChecksheetApprovalHistory", groupedHistory);
            response.put("checksheetStatus", userChecksheet.get().getStatus());


            
            return new ResponseDTO<>(true, "User checksheet approval history fetched successfully", response);
        } catch (CustomException ce) {
            ce.printStackTrace();
            throw ce;
        } catch (Exception e) {
            e.printStackTrace();
            throw new CustomException("Something went wrong", HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    private String getQuestionHierarchicalPath(Long chksQuestionId) {
        StringBuilder path = new StringBuilder();
        
        // Get the initial question
        Optional<ChksQuestion> question = chksQuestionRepository.findQuestionById(chksQuestionId);
        if (question.isEmpty()) {
            return "";
        }
        
        // Add question name
        path.insert(0, question.get().getName());
        
        // Get initial header data
        ChksHeaderData currentHeaderData = question.get().getChksHeaderData();

        // Traverse up the hierarchy until we reach the top (null parent)
        while (currentHeaderData != null) {
            path.insert(0, currentHeaderData.getName() + " -> ");
            currentHeaderData = currentHeaderData.getChksHeaderData();
        }
        
        return path.toString();
    }

}
