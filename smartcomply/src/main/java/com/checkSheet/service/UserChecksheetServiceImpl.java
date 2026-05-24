package com.checkSheet.service;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.file.Paths;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import com.checkSheet.DAO.*;
import com.checkSheet.DTO.*;
import com.checkSheet.constant.*;
import com.checkSheet.helper.FileStorageUtil;
import com.checkSheet.repository.*;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.poi.common.usermodel.HyperlinkType;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.WorkbookUtil;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFClientAnchor;
import org.apache.poi.xssf.usermodel.XSSFFont;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.checkSheet.DTO.response.ResponseDTO;
import com.checkSheet.entity.Audit;
import com.checkSheet.entity.Checksheet;
import com.checkSheet.entity.ChksGeneralField;
import com.checkSheet.entity.ChksGeneralFieldValue;
import com.checkSheet.entity.ChksHeaderData;
import com.checkSheet.entity.ChksQuestion;
import com.checkSheet.entity.ChksQuestionResult;
import com.checkSheet.entity.ChksQuestionResultMatrix;
import com.checkSheet.entity.ChksQuestionResultOption;
import com.checkSheet.entity.Department;
import com.checkSheet.entity.User;
import com.checkSheet.entity.Inspection;
import com.checkSheet.entity.Inspection;
import com.checkSheet.entity.UserChecksheetAnswer;
import com.checkSheet.entity.UserChecksheetAnswerFile;
import com.checkSheet.entity.UserChecksheetMatrixAnswers;
import com.checkSheet.entity.UserChecksheetTraceValue;
import com.checkSheet.entity.UserRoleDepartment;
import com.checkSheet.entity.UsrChksheetAnsJudgement;
import com.checkSheet.entity.UsrChksheetAnsJudgementFile;
import com.checkSheet.constant.ChksQuestionResultType;
import com.checkSheet.exception.CustomException;
import com.checkSheet.helper.DateHelper;

import javax.imageio.ImageIO;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class UserChecksheetServiceImpl implements UserChecksheetService{
    @Autowired
    private PermissionService permissionService;
    
    @Autowired
    private UtilityService utilityService;
    @Autowired
    private ChksGeneralFieldService chksGeneralFieldService;
    @Autowired
    private ChksHeaderService chksHeaderService;

    @Autowired
    private UserChecksheetRepository userChecksheetRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ChecksheetRepository checksheetRepository;

    @Autowired
    private AuditAssignmentRepository auditAssignmentRepository;

    @Autowired
    private com.checkSheet.repository.InterventionAssignmentRepository interventionAssignmentRepository;

    @Autowired
    private UserChecksheetAnswerRepository userChecksheetAnswerRepository;
    @Autowired
    private UserChecksheetAnswerFileRepository userChecksheetAnswerFileRepository;
    @Autowired
    private com.checkSheet.repository.AiAssessmentRepository aiAssessmentRepository;

    @Autowired
    private ChksHeaderDataRepository chksHeaderDataRepository;
    @Autowired
    private ChksHeaderRepository chksHeaderRepository;

    @Autowired
    private ChksQuestionResultRepository chksQuestionResultRepository;

    @Autowired
    private ChksGeneralFieldRepository chksGeneralFieldRepository;
    @Autowired
    private ChksGeneralFieldValueRepository chksGeneralFieldValueRepository;

    @Autowired
    private ChksQuestionResultOptionRepository chksQuestionResultOptionRepository;
    @Autowired
    private UserChecksheetMatrixAnswersRepository userChecksheetMatrixAnswersRepository;

    @Autowired
    private UsrChksheetAnsJudgementRepository usrChksheetAnsJudgementRepository;

    @Autowired
    private ChksQuestionRepository chksQuestionRepository;
    @Autowired
    private UserRoleDepartmentRepository userRoleDepartmentRepository;
    @Autowired
    private DepartmentRepository departmentRepository;
    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private UsrChksheetAnsJudgementFileRepository usrChksheetAnsJudgementFileRepository;
    @Autowired
    AWSS3Service awss3Service;
    @Autowired
    private ChksQuestionResultMatrixRepository chksQuestionResultMatrixRepository;
    @Autowired
    private UserChecksheetTraceValueRepository userChecksheetTraceValueRepository;
    @Autowired
    private ChecksheetDAO checksheetDAO;
    @Autowired
    private ChksHeaderDataDAO chksHeaderDataDAO;
    @Autowired
    private ChksHeaderDataFileDAO chksHeaderDataFileDAO;
    @Autowired
    private ChksQuestionDAO chksQuestionDAO;
    @Autowired
    private ChksQuestionFileDAO chksQuestionFileDAO;
    @Autowired
    private ChksQuestionResultDAO chksQuestionResultDAO;
    @Autowired
    private ChksQuestionResultMatrixDAO chksQuestionResultMatrixDAO;

    @Autowired
    private ChksQuestionResultOptionDAO chksQuestionResultOptionDAO;

    @Autowired
    private UserChecksheetDAO userChecksheetDAO;

    // ─── Upload validation (issue #9) ────────────────────────────────────────
    // Configurable via application.properties so ops can tune per-tenant
    // without redeploying. Defaults are conservative: 10MB ceiling for phone
    // photos (typical 2-5MB, headroom for HDR), JPEG/PNG/WEBP only.
    @Value("${app.upload.max-size-mb:10}")
    private long uploadMaxSizeMb;

    @Value("${app.upload.allowed-content-types:image/jpeg,image/png,image/webp}")
    private String uploadAllowedContentTypes;

    /**
     * Reject oversized or wrong-type uploads BEFORE the file is persisted or
     * buffered into memory by downstream code (thumbnail generation, hashing,
     * disk write). The multipart parser layer enforces a hard ceiling
     * (spring.servlet.multipart.max-file-size) — this method is the friendly
     * application-level check that returns descriptive 413/415 errors.
     */
    private void validateUpload(MultipartFile file) throws CustomException {
        long maxBytes = uploadMaxSizeMb * 1024L * 1024L;
        if (file.getSize() > maxBytes) {
            throw new CustomException(
                    "File too large: " + file.getSize() + " bytes exceeds the "
                            + uploadMaxSizeMb + " MB limit.",
                    HttpStatus.PAYLOAD_TOO_LARGE);
        }

        String contentType = file.getContentType();
        Set<String> allowed = new HashSet<>();
        for (String t : uploadAllowedContentTypes.split(",")) {
            String trimmed = t.trim().toLowerCase();
            if (!trimmed.isEmpty()) allowed.add(trimmed);
        }
        if (contentType == null || !allowed.contains(contentType.toLowerCase())) {
            throw new CustomException(
                    "Unsupported content type: " + contentType
                            + ". Allowed: " + uploadAllowedContentTypes,
                    HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        }
    }

    @Autowired
    private ChksHeaderDataService chksHeaderDataService;

    @Autowired
    private UserChecksheetAnswerDAO userChecksheetAnswerDAO;
    @Autowired
    private ChksGeneralFieldValueDAO chksGeneralFieldValueDAO;
    @Autowired
    private UsrChecksheetAnsJudgementDAO usrChecksheetAnsJudgementDAO;
    @Autowired
    private UserChecksheetTraceValueDAO userChecksheetTraceValueDAO;
    @Autowired
    private UserChecksheetValidationHistoryDAO userChecksheetValidationHistoryDAO;
    @Autowired
    private UserChecksheetApprovalHistoryDAO userChecksheetApprovalHistoryDAO;
    @Autowired
    private UserDAO userDAO;

    // ─────────────────────────────────────────────────────────────────────
    // Security helpers (F1 + F7 + M1 addendum)
    //
    // F1/F7: an operator must NOT be able to dictate the status via the
    // createOrUpdate DTO. The legal-transition table is the gate that
    // prevents self-approval and back-stepping (un-submitting). Anything
    // outside the table → 403.
    //
    // M1 addendum: every answer/judgement/file/trace/general-field write
    // endpoint must verify the caller owns the inspection AND the
    // inspection is in an editable state. Allowing writes to a SUBMITTED/
    // VALIDATED/APPROVED inspection would let the operator silently mutate
    // an audit that's already in the validator/approver pipeline.
    // ─────────────────────────────────────────────────────────────────────

    private static final java.util.Set<String> NON_EDITABLE_STATUSES =
            java.util.Set.of("SUBMITTED", "VALIDATED", "APPROVED");

    /**
     * Enforce the V1.28 legal transition table for operator-driven status
     * changes coming through createOrUpdate. INVALIDATED / NOT_APPROVED are
     * legacy stored values and not accepted from the operator path.
     *
     * Allowed transitions:
     *   ASSIGNED      → IN_PROGRESS        (start)
     *   ASSIGNED      → ASSIGNED           (no-op)
     *   IN_PROGRESS   → IN_PROGRESS        (save without submit)
     *   IN_PROGRESS   → SUBMITTED          (submit for validation)
     *   DECLINED      → IN_PROGRESS        (reopen after decline)
     *
     * Anything else (e.g. IN_PROGRESS → APPROVED, SUBMITTED → IN_PROGRESS)
     * is a privilege escalation or contract violation → 403.
     */
    private void assertLegalOperatorTransition(String from, String to) throws CustomException {
        if (to == null || to.trim().isEmpty()) {
            throw new CustomException("Status is required", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        if (from == null) {
            throw new CustomException(
                    "Inspection is in an invalid state — missing current status",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
        boolean ok =
                ("ASSIGNED".equals(from)    && "IN_PROGRESS".equals(to)) ||
                ("ASSIGNED".equals(from)    && "ASSIGNED".equals(to))    ||
                ("IN_PROGRESS".equals(from) && "IN_PROGRESS".equals(to)) ||
                ("IN_PROGRESS".equals(from) && "SUBMITTED".equals(to))   ||
                ("DECLINED".equals(from)    && "IN_PROGRESS".equals(to));
        if (!ok) {
            throw new CustomException(
                    "Illegal status transition from " + from + " to " + to,
                    HttpStatus.FORBIDDEN);
        }
    }

    /**
     * Load the inspection by id; reject the call if (a) it doesn't exist,
     * (b) the current user is not the assigned operator, or (c) it is in a
     * non-editable status (SUBMITTED/VALIDATED/APPROVED). Returns the
     * loaded entity so the caller can use it without a second findById.
     */
    private Inspection assertOperatorCanEditInspection(Long inspectionId) throws CustomException {
        if (inspectionId == null) {
            throw new CustomException("Please provide User Checksheet Id", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        User currentUser = utilityService.getCurrentLoggedInUser()
                .orElseThrow(() -> new CustomException("User not authenticated", HttpStatus.UNAUTHORIZED));
        Inspection ins = userChecksheetRepository.findById(inspectionId)
                .orElseThrow(() -> new CustomException(
                        "Please provide valid User Checksheet Id", HttpStatus.UNPROCESSABLE_ENTITY));
        if (ins.getOperatorUser() == null
                || !Objects.equals(ins.getOperatorUser().getId(), currentUser.getId())) {
            throw new CustomException(
                    "You are not authorized to modify this audit", HttpStatus.FORBIDDEN);
        }
        if (NON_EDITABLE_STATUSES.contains(ins.getStatus())) {
            throw new CustomException(
                    "Inspection is not in an editable state (status=" + ins.getStatus() + ")",
                    HttpStatus.FORBIDDEN);
        }
        return ins;
    }

    @Override
    public ResponseDTO<?> getUserChecksheets(ChecksheetDTO checksheetDTO) throws CustomException {
        try {
            Optional<User> currentLoggedInUser = utilityService.getCurrentLoggedInUser();
            List<ChecksheetDTO> checksheets = checksheetDAO.getUserChecksheets(currentLoggedInUser.get().getId(),"operator_user_ids",checksheetDTO);
            List<UserChecksheetDTO> declinedUserChecksheets = userChecksheetDAO.getDeclinedUserChecksheets(currentLoggedInUser.get().getId());
            Date startDate, endDate;
            for(ChecksheetDTO checksheet : checksheets){
                List<UserChecksheetDTO> userChecksheetDTOS = new ArrayList<>(0);
                List<Inspection> userChecksheets = userChecksheetRepository.findByUserIdChecksheetId(currentLoggedInUser.get().getId(),checksheet.getId());
                if(userChecksheets.isEmpty()){
                    checksheet.setStatus("NEW");
                }else {
                    UserChecksheetDTO userChecksheetDTO;
                    Optional<Inspection> userChecksheetOpt = userChecksheets.stream()
                                                                            .filter(userChks -> userChks.getStatus().equals("IN_PROGRESS"))
                                                                            .findFirst();
                    if(userChecksheetOpt.isPresent()) {
                        userChecksheetDTO = new UserChecksheetDTO();
                        Inspection userChks = userChecksheetOpt.get();
                        userChecksheetDTO.setStatus(userChks.getStatus());
                        userChecksheetDTO.setId(userChks.getId());
                        userChecksheetDTO.setShift(userChks.getShift());
                        userChecksheetDTO.setFrequencyOfFreqOfChkCnt(userChks.getFrequencyOfFreqOfChkCnt());
                        userChecksheetDTO.setStartedAt(userChks.getStartedAt());
                        userChecksheetDTO.setSubmittedAt(userChks.getSubmittedAt());
                        userChecksheetDTO.setSubmissionVersion(userChks.getSubmissionVersion());
                        userChecksheetDTOS.add(userChecksheetDTO);
                    }
                    switch (checksheet.getFrequencyOfCheck().getValue()) {
                        case "SHIFT", "DAILY" -> startDate = endDate = new Date();
                        case "WEEKLY" -> {
                            startDate = DateHelper.getDateOfCurrentWeek(0);
                            endDate = DateHelper.getDateOfCurrentWeek(6);
                        }
                        case "MONTHLY" -> {
                            startDate = DateHelper.getDateOfCurrentMonth(1);
                            endDate = DateHelper.getLastDateOfCurrentMonth();
                        }
                        case "UNPLANNED" -> {
                            startDate = endDate = null;
                            checksheet.setStatus("START");
                        }
                        default -> startDate = endDate = null;
                    }
                    if(startDate != null){
                        LocalDate finalStartDate = startDate.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
                        LocalDate finalEndDate = endDate.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
                        List<Inspection> lastFilledUserChecksheets = userChecksheetRepository.findByUserIdChecksheetIdAndStartedDateBetween(
                                currentLoggedInUser.get().getId(),
                                checksheet.getId(),
                                finalStartDate,
                                finalEndDate
                        );
                        if(lastFilledUserChecksheets.isEmpty()){
                            checksheet.setStatus("START");
                        }else{
                            //Inspection userChks = lastFilledUserChecksheets.stream().findFirst().orElseThrow(() -> new CustomException("No completed User Checksheet.",HttpStatus.UNPROCESSABLE_ENTITY));
                            for(Inspection userChks:lastFilledUserChecksheets) {
                                userChecksheetDTO = new UserChecksheetDTO();
                                userChecksheetDTO.setStatus(userChks.getStatus());
                                userChecksheetDTO.setId(userChks.getId());
                                userChecksheetDTO.setShift(userChks.getShift());
                                userChecksheetDTO.setStartedAt(userChks.getStartedAt());
                                userChecksheetDTO.setSubmittedAt(userChks.getSubmittedAt());
                                userChecksheetDTO.setSubmissionVersion(userChks.getSubmissionVersion());
                                userChecksheetDTO.setFrequencyOfFreqOfChkCnt(userChks.getFrequencyOfFreqOfChkCnt());
                                userChecksheetDTOS.add(userChecksheetDTO);
                            }
                            checksheet.setStatus("COMPLETED");
                        }
                    }
                }
                List<UserChecksheetDTO> declinedUC = declinedUserChecksheets.stream().filter(duc -> Objects.equals(duc.getChecksheetId(), checksheet.getId())).toList();
                userChecksheetDTOS.addAll(declinedUC);
                checksheet.setUserChecksheets(userChecksheetDTOS);
            }
            return new ResponseDTO<>(true, "User's checksheets are fetched successfully", checksheets);
        } catch (CustomException ce) {
            throw ce;  // preserve original status (FORBIDDEN, NOT_FOUND, etc.)
        } catch ( Exception e ) {
            log.error("Inspection operation failed: {} ({})", e.getMessage(), e.getClass().getSimpleName(), e);
            throw new CustomException("Inspection operation failed: " + e.getMessage(), e, HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
    @Override
    public ResponseDTO<?> createOrUpdateUserChecksheets(List<UserChecksheetDTO> userChecksheetDTOs) throws CustomException {
        try {
            List<UserChecksheetDTO> newUserChecksheetDTOs = new ArrayList<>();
            List<UserChecksheetDTO> existUserChks = userChecksheetDTOs.stream().filter(userChks -> userChks.getId() != null).toList();
            for(UserChecksheetDTO existUserChk: existUserChks){
                newUserChecksheetDTOs.add(createOrUpdateUserChecksheet(existUserChk).getData());
            }
            List<UserChecksheetDTO> newSubmittedUserChks = userChecksheetDTOs.stream().filter(userChks -> userChks.getId() == null && userChks.getStatus().equals("SUBMITTED")).toList();
            for(UserChecksheetDTO newSubmittedUserChk: newSubmittedUserChks){
                newUserChecksheetDTOs.add(createOrUpdateUserChecksheet(newSubmittedUserChk).getData());
            }
            List<UserChecksheetDTO> newInProgressUserChks = userChecksheetDTOs.stream().filter(userChks -> userChks.getId() == null && userChks.getStatus().equals("IN_PROGRESS")).toList();
            for(UserChecksheetDTO newInProgressUserChk: newInProgressUserChks){
                newUserChecksheetDTOs.add(createOrUpdateUserChecksheet(newInProgressUserChk).getData());
            }
            return new ResponseDTO<>(true, "Inspection saved successfully",newUserChecksheetDTOs);
        } catch (CustomException ce) {
            throw ce;
        } catch ( Exception e ) {
            log.error("Inspection operation failed: {} ({})", e.getMessage(), e.getClass().getSimpleName(), e);
            throw new CustomException("Inspection operation failed: " + e.getMessage(), e, HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
    @Transactional(rollbackFor = Exception.class)
    @Override
    public ResponseDTO<UserChecksheetDTO> createOrUpdateUserChecksheet(UserChecksheetDTO userChecksheetDTO) throws CustomException {
        try{
            // ── Resolve audit assignment ──────────────────────────────────────
            // The mobile/web client sends only auditAssignmentId. From it we
            // resolve the audit campaign, the location being audited, and the
            // checksheet template — there is no need to send checksheetId,
            // auditId, or auditeeLocationId separately.
            Optional<Inspection> userChecksheetOpt;
            Inspection userChecksheet;
            Inspection assignment;
            Checksheet checksheet;
            User currentUser = utilityService.getCurrentLoggedInUser()
                    .orElseThrow(() -> new CustomException("User not authenticated", HttpStatus.UNAUTHORIZED));

            // Pre-V1.28, the mobile client sent `auditAssignmentId` for audit-side
            // starts and `interventionAssignmentId` for re-inspection-wave starts —
            // those came from two different tables, so the field name was the
            // discriminator. Post-V1.28, `/api/audit/myAssignments` returns a unified
            // `assignmentId` regardless of kind, and the mobile app does not yet
            // distinguish the two when it calls back. If we receive `auditAssignmentId`
            // that resolves to a `kind=INTERVENTION` inspection, treat it as an
            // intervention assignment by swapping the DTO field so the existing
            // branch logic below handles it. Avoids a 422 the mobile UI couldn't
            // recover from. Remove this once mobile reads `assignmentKind` on the
            // myAssignments response.
            if (userChecksheetDTO.getId() == null
                    && userChecksheetDTO.getInterventionAssignmentId() == null
                    && userChecksheetDTO.getAuditAssignmentId() != null) {
                auditAssignmentRepository.findById(userChecksheetDTO.getAuditAssignmentId())
                    .filter(ins -> "INTERVENTION".equals(ins.getKind()))
                    .ifPresent(ins -> {
                        userChecksheetDTO.setInterventionAssignmentId(ins.getId());
                        userChecksheetDTO.setAuditAssignmentId(null);
                    });
            }

            if (userChecksheetDTO.getId() != null) {
                // UPDATE path — load existing UC, take its assignment as the source of truth.
                userChecksheet = userChecksheetRepository.findById(userChecksheetDTO.getId())
                        .orElseThrow(() -> new CustomException("Invalid Userchecksheet Id", HttpStatus.UNPROCESSABLE_ENTITY));
                // Ownership check: only the operator who owns this UC can update it.
                if (userChecksheet.getOperatorUser() == null
                        || !Objects.equals(userChecksheet.getOperatorUser().getId(), currentUser.getId())) {
                    throw new CustomException("You are not authorized to modify this audit", HttpStatus.FORBIDDEN);
                }
                // Resolve the underlying assignment whether this UC is on
                // the audit side or on a re-inspection wave.
                // Post-V1.28: the inspection IS its own assignment. Audit
                // context resolves directly (kind=AUDIT) or via intervention.audit
                // (kind=INTERVENTION).
                assignment = userChecksheet;
                Audit auditCtx = "AUDIT".equals(userChecksheet.getKind())
                    ? userChecksheet.getAudit()
                    : (userChecksheet.getIntervention() != null
                       ? userChecksheet.getIntervention().getAudit() : null);
                if (auditCtx == null || auditCtx.getChecksheet() == null) {
                    throw new CustomException("Inspection is in an invalid state — missing audit/checksheet context",
                        HttpStatus.UNPROCESSABLE_ENTITY);
                }
                checksheet = auditCtx.getChecksheet();
                userChecksheet.setUpdatedBy(currentUser);
            } else if (userChecksheetDTO.getInterventionAssignmentId() != null) {
                // CREATE path — RE-INSPECTION on an Inspection of kind=INTERVENTION.
                // Post-V1.28: the intervention assignment IS the inspection
                // (kind='INTERVENTION'). Starting the re-inspection means
                // transitioning that row's status from ASSIGNED → IN_PROGRESS,
                // not inserting a new row.
                com.checkSheet.entity.Inspection ia =
                    interventionAssignmentRepository.findByIdAndDeletedAtIsNull(userChecksheetDTO.getInterventionAssignmentId())
                        .orElseThrow(() -> new CustomException("Invalid interventionAssignmentId", HttpStatus.UNPROCESSABLE_ENTITY));
                if (!"ASSIGNED".equals(ia.getStatus())) {
                    throw new CustomException(
                        "Re-inspection is already started or closed (status=" + ia.getStatus() + ")",
                        HttpStatus.UNPROCESSABLE_ENTITY);
                }
                if (ia.getIntervention() == null
                    || ia.getIntervention().getAudit() == null
                    || ia.getIntervention().getAudit().getChecksheet() == null) {
                    throw new CustomException("Re-inspection is in an invalid state",
                        HttpStatus.UNPROCESSABLE_ENTITY);
                }
                User assignedOperator = ia.getOperatorUser();
                if (assignedOperator != null && !Objects.equals(assignedOperator.getId(), currentUser.getId())) {
                    throw new CustomException("This re-inspection is not assigned to you", HttpStatus.FORBIDDEN);
                }
                checksheet = ia.getIntervention().getAudit().getChecksheet();
                userChecksheet = ia;                 // SAME row, just bump status
                userChecksheet.setOperatorUser(currentUser);
                userChecksheet.setChecksheet(checksheet);
                assignment = ia;                     // for downstream code that reads from `assignment`
            } else {
                // CREATE path — caller MUST provide auditAssignmentId.
                if (userChecksheetDTO.getAuditAssignmentId() == null) {
                    throw new CustomException("Please provide auditAssignmentId or interventionAssignmentId", HttpStatus.UNPROCESSABLE_ENTITY);
                }
                assignment = auditAssignmentRepository.findById(userChecksheetDTO.getAuditAssignmentId())
                        .orElseThrow(() -> new CustomException("Invalid auditAssignmentId", HttpStatus.UNPROCESSABLE_ENTITY));
                if (assignment.getDeletedAt() != null) {
                    throw new CustomException("Audit assignment has been deleted", HttpStatus.UNPROCESSABLE_ENTITY);
                }
                if (assignment.getAudit() == null || assignment.getAudit().getDeletedAt() != null) {
                    throw new CustomException("Audit has been deleted or is invalid", HttpStatus.UNPROCESSABLE_ENTITY);
                }
                if (assignment.getAudit().getChecksheet() == null) {
                    throw new CustomException("Audit assignment is missing a checksheet", HttpStatus.UNPROCESSABLE_ENTITY);
                }
                // Ownership check: only the assigned operator (or an unassigned slot) may
                // start a user_checksheet for this assignment.
                User assignedOperator = assignment.getOperatorUser();
                if (assignedOperator != null && !Objects.equals(assignedOperator.getId(), currentUser.getId())) {
                    throw new CustomException("This audit assignment is not assigned to you", HttpStatus.FORBIDDEN);
                }
                checksheet = assignment.getAudit().getChecksheet();
                if (!checksheet.getStatus().getValue().equals("APPROVED")) {
                    throw new CustomException("Audit's checksheet template is not APPROVED", HttpStatus.UNPROCESSABLE_ENTITY);
                }
                if (!checksheet.getFrequencyOfCheck().equals(ChecksheetFrequencyType.UNPLANNED)
                        && userChecksheetDTO.getFrequencyOfFreqOfChkCnt() != null
                        && checksheet.getFrequencyOfFreqOfChk() < userChecksheetDTO.getFrequencyOfFreqOfChkCnt()) {
                    throw new CustomException("You can not attempt more than defined maximum attempt.", HttpStatus.UNPROCESSABLE_ENTITY);
                }
                // Post-V1.28: the audit assignment IS the inspection. "Starting"
                // a UC means transitioning status from ASSIGNED → IN_PROGRESS on
                // the SAME row (no new INSERT). Reject if it's already past
                // ASSIGNED — the caller should be calling UPDATE instead.
                if (!"ASSIGNED".equals(assignment.getStatus())) {
                    throw new CustomException(
                        "Inspection is already started (status=" + assignment.getStatus() + "); use update path",
                        HttpStatus.UNPROCESSABLE_ENTITY);
                }
                userChecksheet = assignment;             // SAME row, just bump status
                userChecksheet.setOperatorUser(currentUser);
                userChecksheet.setChecksheet(checksheet);
            }

            // F1+F7: never accept the caller-supplied status verbatim. The
            // operator could POST { id: <theirs>, status: "APPROVED" } and
            // skip the validator/approver pipeline entirely; or pull a
            // SUBMITTED inspection back to IN_PROGRESS. The legal-transition
            // table is the single gate.
            assertLegalOperatorTransition(userChecksheet.getStatus(), userChecksheetDTO.getStatus());
            userChecksheet.setStatus(userChecksheetDTO.getStatus());
            if (userChecksheetDTO.getShift() != null) userChecksheet.setShift(userChecksheetDTO.getShift());
            // Preserve existing startedAt on UPDATE if DTO omits it.
            // The CREATE path always sets it; the SUBMIT/UPDATE path doesn't have to re-send it.
            if (userChecksheetDTO.getStartedAt() != null) {
                userChecksheet.setStartedAt(userChecksheetDTO.getStartedAt());
            }
            if (userChecksheetDTO.getSubmissionVersion() != null) userChecksheet.setSubmissionVersion(userChecksheetDTO.getSubmissionVersion());
            if (userChecksheetDTO.getSubmittedAt() != null) userChecksheet.setSubmittedAt(userChecksheetDTO.getSubmittedAt());
            if (userChecksheetDTO.getFrequencyOfFreqOfChkCnt() != null) userChecksheet.setFrequencyOfFreqOfChkCnt(userChecksheetDTO.getFrequencyOfFreqOfChkCnt());

            userChecksheetRepository.save(userChecksheet);
            userChecksheetRepository.flush();

            // Populate response DTO with derived fields for the consumer.
            // Audit context resolves either from the inspection's direct audit
            // (kind=AUDIT) or via intervention.audit (kind=INTERVENTION).
            Audit auditCtx = assignment.getAudit() != null
                ? assignment.getAudit()
                : (assignment.getIntervention() != null ? assignment.getIntervention().getAudit() : null);
            userChecksheetDTO.setInspectionId(userChecksheet.getId());
            userChecksheetDTO.setId(userChecksheet.getId());
            userChecksheetDTO.setAuditAssignmentId(assignment.getId());
            if (auditCtx != null) {
                userChecksheetDTO.setAuditId(auditCtx.getId());
                userChecksheetDTO.setAuditName(auditCtx.getName());
            }
            userChecksheetDTO.setAuditeeLocationId(assignment.getAuditeeLocation().getId());
            userChecksheetDTO.setChecksheetId(checksheet.getId());
            userChecksheetDTO.setChecksheetName(checksheet.getName());
            userChecksheetDTO.setStartedAt(userChecksheet.getStartedAt());

            if (Objects.equals(userChecksheetDTO.getStatus(), UserChecksheetStatusType.SUBMITTED.toString())) {
                userChecksheet.setWaitingUserIds(checksheet.getDataValidatorUserIds());
                userChecksheetRepository.save(userChecksheet);
                utilityService.sendEmail(checksheet.getDataValidatorUserIds(), EmailTemplate.USER_CHKS_SUBMITTED, checksheet, List.of(currentUser.getId()));
            }
            return new ResponseDTO<UserChecksheetDTO>(true, "Inspection saved successfully", userChecksheetDTO);
        } catch (CustomException ce) {
            throw ce;  // preserve original status (FORBIDDEN, NOT_FOUND, etc.)
        } catch ( Exception e ) {
            log.error("Inspection operation failed: {} ({})", e.getMessage(), e.getClass().getSimpleName(), e);
            throw new CustomException("Inspection operation failed: " + e.getMessage(), e, HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }



    @Override
    public ResponseDTO<?> getUserChecksheetDetails(UserChecksheetDTO userChecksheetDTO) throws CustomException {
        try{

//            return chksHeaderDataService.getChecksheetData(userChecksheetDTO.getChecksheetId(),true);
            Map<String, Object> responseData = new HashMap<>();
            if(!Objects.equals(userChecksheetDTO.getChecksheetId(),null)){
                ChksHeaderDTO chksHeaderDTO = new ChksHeaderDTO();
                chksHeaderDTO.setChecksheetId(userChecksheetDTO.getChecksheetId());
                ResponseDTO<?> chksHeaders = chksHeaderService.getChksHeaderData(chksHeaderDTO);

                ChksGeneralFieldDTO chksGeneralFieldDTO = new ChksGeneralFieldDTO();
                chksGeneralFieldDTO.setChecksheetId(userChecksheetDTO.getChecksheetId());
                ResponseDTO<?> chksGeneralFieldData = chksGeneralFieldService.getChksGeneralFieldData(chksGeneralFieldDTO);

                responseData.put("chksGeneralFields", chksGeneralFieldData.getData());
                responseData.put("chksHeaders", chksHeaders.getData());
                responseData.put("chksHeaderData", chksHeaderDataDAO.getChksHeaderData(userChecksheetDTO.getChecksheetId()));
                List<ChksHeaderDataFileDTO> chksHeaderDataFiles =chksHeaderDataFileDAO.getChksHeaderDataFileByChecksheetId(userChecksheetDTO.getChecksheetId());
                for(ChksHeaderDataFileDTO chksHeaderDataFile:chksHeaderDataFiles){
//                    chksHeaderDataFile.setUrl(awss3Service.getDocs(chksHeaderDataFile.getPath()).toString());
                    chksHeaderDataFile.setUrl(FileStorageUtil.getFileURL(chksHeaderDataFile.getPath()));
                }
                responseData.put("chksHeaderDataFiles", chksHeaderDataFiles);
                responseData.put("chksQuestions",chksQuestionDAO.getChksQuestionByChecksheetId(userChecksheetDTO.getChecksheetId()));
                List<ChksQuestionFileDTO> chksQueFiles =chksQuestionFileDAO.getChksQuestionFileByChecksheetId(userChecksheetDTO.getChecksheetId());
                for(ChksQuestionFileDTO chksQueFile:chksQueFiles){
//                    chksQueFile.setUrl(awss3Service.getDocs(chksQueFile.getPath()).toString());
                    chksQueFile.setUrl(FileStorageUtil.getFileURL(chksQueFile.getPath()));
                }
                responseData.put("chksQuestionsFiles",chksQueFiles);
                responseData.put("chksQuestionResults",chksQuestionResultDAO.getChksQuestionResultByChecksheetId(userChecksheetDTO.getChecksheetId()));
                responseData.put("chksQuestionResultMatrices",chksQuestionResultMatrixDAO.getMatrixDataByChksId(userChecksheetDTO.getChecksheetId()));
                responseData.put("chksQuestionResultOptions",chksQuestionResultOptionDAO.getOptionDataByChksId(userChecksheetDTO.getChecksheetId()));
            }
            if(!Objects.equals(userChecksheetDTO.getInspectionId(),null)){
                responseData.put("userChecksheetAnswers",userChecksheetAnswerDAO.getUserChksAnswers(userChecksheetDTO.getInspectionId()));
                responseData.put("userChecksheetMatrixAnswers",userChecksheetAnswerDAO.getUserChksMtrxAnswers(userChecksheetDTO.getInspectionId()));
                responseData.put("userChksGeneralFieldValues",chksGeneralFieldValueDAO.getUserChksGeneralFieldValues(userChecksheetDTO.getInspectionId()));
                responseData.put("usrChecksheetTraceValues",userChecksheetTraceValueDAO.getUserChksAnswerTraceValues(userChecksheetDTO.getInspectionId()));
                responseData.put("usrChecksheetAnsJudgements",usrChecksheetAnsJudgementDAO.getUsrChecksheetAnsJudgements(userChecksheetDTO.getInspectionId()));
                List<UsrChksheetAnsJudgementFileDTO> usrChecksheetAnsJudgementFiles = usrChecksheetAnsJudgementDAO.usrChecksheetAnsJudgementFiles(userChecksheetDTO.getInspectionId());
                usrChecksheetAnsJudgementFiles.forEach(usrChksAnsJudgementFile -> {
//                    usrChksAnsJudgementFile.setUrl(awss3Service.getDocs(usrChksAnsJudgementFile.getPath()).toString());
                    usrChksAnsJudgementFile.setUrl(FileStorageUtil.getFileURL(usrChksAnsJudgementFile.getPath()));
                });
                responseData.put("usrChecksheetAnsJudgementFiles",usrChecksheetAnsJudgementFiles);

                List<UserChecksheetAnswerFileDTO> usrChecksheetAnsFiles = userChecksheetAnswerFileRepository
                        .findByUserChecksheetAnswer_Inspection_IdAndDeletedAtIsNull(userChecksheetDTO.getInspectionId())
                        .stream()
                        .map(userChecksheetAnswerFile -> getUserChecksheetAnswerFileDTO(
                                userChecksheetAnswerFile,
                                userChecksheetAnswerFile.getUserChecksheetAnswer()
                        ))
                        .toList();
                responseData.put("usrChecksheetAnsFiles", usrChecksheetAnsFiles);

                responseData.put("userChecksheetValidationsHistory",userChecksheetValidationHistoryDAO.getUsrChksValidationhistory(userChecksheetDTO.getInspectionId()));
                responseData.put("userChecksheetApprovalsHistory",userChecksheetApprovalHistoryDAO.getUsrChksApprovalhistory(userChecksheetDTO.getInspectionId()));

            }
            return new ResponseDTO<>(true, "Data fetched successfully", responseData);
        } catch (CustomException ce) {
            throw ce;  // preserve original status (FORBIDDEN, NOT_FOUND, etc.)
        } catch ( Exception e ) {
            log.error("Inspection operation failed: {} ({})", e.getMessage(), e.getClass().getSimpleName(), e);
            throw new CustomException("Inspection operation failed: " + e.getMessage(), e, HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public ResponseDTO<?> createOrUpdateUserChksAns(List<UserChecksheetAnswerDTO> userChecksheetAnswers) throws CustomException {
        try{
            Optional<UserChecksheetAnswer> userChecksheetAnswer;
            Optional<Inspection> userChecksheet;
            UserChecksheetAnswer usrChksAns;
            Optional<ChksQuestionResult> chksQuestionResult;
            Optional<ChksQuestionResultOption> chksQuestionResultOption;
            User currentUser = utilityService.getCurrentLoggedInUser()
                    .orElseThrow(() -> new CustomException("User not authenticated", HttpStatus.UNAUTHORIZED));
            for (UserChecksheetAnswerDTO userChecksheetAnswerDTO:userChecksheetAnswers){
                if(Objects.equals(userChecksheetAnswerDTO.getInspectionId(), null)){
                    throw new CustomException("Please provide User Checksheet Id", HttpStatus.UNPROCESSABLE_ENTITY);
                }else if(Objects.equals(userChecksheetAnswerDTO.getChksQuestionResultId(), null)){
                    throw new CustomException("Please provide Checksheet Question Result Id", HttpStatus.UNPROCESSABLE_ENTITY);
                }
                // M1 addendum: caller must own the parent inspection AND it
                // must still be editable (not yet SUBMITTED/VALIDATED/APPROVED).
                assertOperatorCanEditInspection(userChecksheetAnswerDTO.getInspectionId());
                if(!Objects.equals(userChecksheetAnswerDTO.getId(), null)) {
                    userChecksheetAnswer = userChecksheetAnswerRepository.findById(userChecksheetAnswerDTO.getId());
                    if(!userChecksheetAnswer.isPresent()){
                        throw new CustomException("Please provide valid id", HttpStatus.UNPROCESSABLE_ENTITY);
                    }else{
                        usrChksAns = userChecksheetAnswer.get();
                        usrChksAns.setUpdatedBy(currentUser);
                    }
                }else{
                    userChecksheetAnswer = userChecksheetAnswerRepository.findByInspectionIdAndChksQuestionResultId(userChecksheetAnswerDTO.getInspectionId(), userChecksheetAnswerDTO.getChksQuestionResultId());
                    if(userChecksheetAnswer.isPresent()){
                        usrChksAns = userChecksheetAnswer.get();
                        usrChksAns.setUpdatedBy(currentUser);
                    }else{
                        usrChksAns = new UserChecksheetAnswer();
                        usrChksAns.setCreatedBy(currentUser);
                    }
                }
                userChecksheet = userChecksheetRepository.findById(userChecksheetAnswerDTO.getInspectionId());
                if(userChecksheet.isEmpty()){
                    throw new CustomException("Please provide valid User Checksheet Id", HttpStatus.UNPROCESSABLE_ENTITY);
                }else{
                    usrChksAns.setInspection(userChecksheet.get());
                }

                chksQuestionResult = chksQuestionResultRepository.findById(userChecksheetAnswerDTO.getChksQuestionResultId());
                if(chksQuestionResult.isEmpty()){
                    throw new CustomException("Please provide valid Checksheet Question Result Id", HttpStatus.UNPROCESSABLE_ENTITY);
                }else{
                    usrChksAns.setChksQuestionResult(chksQuestionResult.get());
                }
                if(!Objects.equals(userChecksheetAnswerDTO.getChksQuestionRsltOptionId(), null)) {
                    chksQuestionResultOption = chksQuestionResultOptionRepository.findById(userChecksheetAnswerDTO.getChksQuestionRsltOptionId());
                    if(chksQuestionResultOption.isEmpty()){
                        throw new CustomException("Please provide Checksheet Question Result Option Id", HttpStatus.UNPROCESSABLE_ENTITY);
                    }else{
                        usrChksAns.setChksQuestionRsltOption(chksQuestionResultOption.get());
                    }
                    usrChksAns.setAnswer(null);
                }else if((Objects.isNull(userChecksheetAnswerDTO.getIsNotApplicable()) || !userChecksheetAnswerDTO.getIsNotApplicable()) &&
                        (!List.of(ChksQuestionResultType.FILE_UPLOAD, ChksQuestionResultType.MATRIX).contains(chksQuestionResult.get().getAnswerType())) &&
                        ((Objects.equals(userChecksheetAnswerDTO.getAnswer(), null) || userChecksheetAnswerDTO.getAnswer().trim().isEmpty()))){
                    throw new CustomException("Please provide Answer", HttpStatus.UNPROCESSABLE_ENTITY);
                }else if(Objects.nonNull(userChecksheetAnswerDTO.getIsNotApplicable()) && !userChecksheetAnswerDTO.getIsNotApplicable()) {
                    usrChksAns.setAnswer(userChecksheetAnswerDTO.getAnswer().trim());
                    usrChksAns.setChksQuestionRsltOption(null);
                } else {
                    usrChksAns.setChksQuestionRsltOption(null);
                    usrChksAns.setAnswer(null);
                }
                if(Objects.equals(chksQuestionResult.get().getAnswerType(), ChksQuestionResultType.MATRIX) &&
                        Boolean.TRUE.equals(userChecksheetAnswerDTO.getIsNotApplicable())) {
                    userChecksheetMatrixAnswersRepository.deleteByInspection_IdAndChksQuestionResult_Id(
                            userChecksheetAnswerDTO.getInspectionId(),
                            userChecksheetAnswerDTO.getChksQuestionResultId()
                    );
                }
                usrChksAns.setChksQuestion(chksQuestionResult.get().getChksQuestion());
                usrChksAns.setJudgement(userChecksheetAnswerDTO.getJudgement());
                usrChksAns.setIsNotApplicable(Objects.isNull(userChecksheetAnswerDTO.getIsNotApplicable()) ? Boolean.FALSE : userChecksheetAnswerDTO.getIsNotApplicable());
                usrChksAns.setAnsweredAt(userChecksheetAnswerDTO.getAnsweredAt());
                userChecksheetAnswerRepository.save(usrChksAns);
                userChecksheetAnswerDTO.setId(usrChksAns.getId());
            }
            return new ResponseDTO<>(true, "Inspection Answer(s) are saved successfully",userChecksheetAnswers);
        } catch (CustomException ce) {
            throw ce;  // preserve original status (FORBIDDEN, NOT_FOUND, etc.)
        } catch ( Exception e ) {
            log.error("Inspection operation failed: {} ({})", e.getMessage(), e.getClass().getSimpleName(), e);
            throw new CustomException("Inspection operation failed: " + e.getMessage(), e, HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public ResponseDTO<?> createUserChksAnsFile(UserChecksheetAnswerFileDTO userChecksheetAnswerFileDTO) throws CustomException {
        try {
            User currentLoggedInUser = utilityService.getCurrentLoggedInUser()
                .orElseThrow(() -> new CustomException("User is not authenticated.", HttpStatus.UNPROCESSABLE_ENTITY));

            if (!permissionService.hasPermission(currentLoggedInUser.getId(), "CHECKSHEET_FILL_ANSWER")) {
                throw new CustomException("User does not have permission to upload answer files", HttpStatus.FORBIDDEN);
            }

            if (Objects.isNull(userChecksheetAnswerFileDTO.getUserChecksheetAnswerId())) {
                throw new CustomException("Please provide User Checksheet Answer Id", HttpStatus.UNPROCESSABLE_ENTITY);
            }
            if (Objects.isNull(userChecksheetAnswerFileDTO.getFile()) || userChecksheetAnswerFileDTO.getFile().isEmpty()) {
                throw new CustomException("Please provide valid file", HttpStatus.UNPROCESSABLE_ENTITY);
            }

            UserChecksheetAnswer userChecksheetAnswer = userChecksheetAnswerRepository
                .findById(userChecksheetAnswerFileDTO.getUserChecksheetAnswerId())
                .orElseThrow(() -> new CustomException("Please provide valid User Checksheet Answer Id", HttpStatus.UNPROCESSABLE_ENTITY));

            // Photo evidence is permitted on ANY answer type. The original
            // FILE_UPLOAD-only restriction conflated "the file IS the answer"
            // (FILE_UPLOAD) with "supporting evidence on top of an answer"
            // (every other type) — the latter is a real product need.

            // M1 addendum: caller must own the parent inspection AND it must
            // still be editable. The endpoint is keyed by userChecksheetAnswerId,
            // so derive the parent inspection from the answer, then gate.
            Inspection parentUc = userChecksheetAnswer.getInspection();
            if (parentUc == null) {
                throw new CustomException("Answer has no parent inspection", HttpStatus.UNPROCESSABLE_ENTITY);
            }
            assertOperatorCanEditInspection(parentUc.getId());

            MultipartFile file = userChecksheetAnswerFileDTO.getFile();
            String originalFileName = file.getOriginalFilename();
            if (Objects.isNull(originalFileName) || originalFileName.trim().isEmpty()) {
                throw new CustomException("File name is invalid", HttpStatus.UNPROCESSABLE_ENTITY);
            }

            // Issue #9: reject oversized / wrong-content-type BEFORE we hash,
            // thumbnail, or write the file to disk. Throws 413 / 415.
            validateUpload(file);

            UserChecksheetAnswerFile userChecksheetAnswerFile = new UserChecksheetAnswerFile();
            userChecksheetAnswerFile.setUserChecksheetAnswer(userChecksheetAnswer);
            userChecksheetAnswerFile.setCreatedBy(currentLoggedInUser);
            userChecksheetAnswerFile.setMimeType(file.getContentType());
            userChecksheetAnswerFile.setFileSizeBytes(file.getSize());
            userChecksheetAnswerFile.setFileHashSha256(getFileHash(file));

            userChecksheetAnswerFileRepository.save(userChecksheetAnswerFile);
            userChecksheetAnswerFileRepository.flush();

            String truncatedFilename = utilityService.getTruncatedFileName(originalFileName, 50);
            String storedFileName = userChecksheetAnswerFile.getId() + "_" + truncatedFilename;
            String filePath = FileStorageUtil.storeFile(file, "UserChecksheetAnswerFile/", storedFileName);
            String thumbnailPath = FileStorageUtil.createThumbnailIfImage(file, userChecksheetAnswerFile.getId(), truncatedFilename);

            userChecksheetAnswerFile.setPath(filePath);
            userChecksheetAnswerFile.setThumbnailPath(thumbnailPath);
            userChecksheetAnswerFileRepository.save(userChecksheetAnswerFile);

            UserChecksheetAnswerFileDTO fileDTO = getUserChecksheetAnswerFileDTO(userChecksheetAnswerFile, userChecksheetAnswer);

            return new ResponseDTO<>(true, "Inspection Answer File is saved successfully", fileDTO);
        } catch (CustomException ce) {
            throw ce;
        } catch (Exception e) {
            log.error("Inspection operation failed: {} ({})", e.getMessage(), e.getClass().getSimpleName(), e);
            throw new CustomException("Inspection operation failed: " + e.getMessage(), e, HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public ResponseDTO<?> deleteUserChksAnsFile(UserChecksheetAnswerFileDTO userChecksheetAnswerFileDTO) throws CustomException {
        try {
            if (Objects.isNull(userChecksheetAnswerFileDTO.getUserChecksheetAnswerFileIds())
                || userChecksheetAnswerFileDTO.getUserChecksheetAnswerFileIds().isEmpty()) {
                throw new CustomException("Please provide User Checksheet Answer File Id", HttpStatus.UNPROCESSABLE_ENTITY);
            }

            List<UserChecksheetAnswerFile> userChecksheetAnswerFiles = userChecksheetAnswerFileRepository
                .findByIdIn(userChecksheetAnswerFileDTO.getUserChecksheetAnswerFileIds());

            if (userChecksheetAnswerFiles.size() != userChecksheetAnswerFileDTO.getUserChecksheetAnswerFileIds().size()) {
                throw new CustomException("Please provide valid User Checksheet Answer File Id", HttpStatus.UNPROCESSABLE_ENTITY);
            }

            for (UserChecksheetAnswerFile userChecksheetAnswerFile : userChecksheetAnswerFiles) {
                if (!Objects.isNull(userChecksheetAnswerFile.getPath()) && !userChecksheetAnswerFile.getPath().isBlank()) {
                    Path path = Paths.get(userChecksheetAnswerFile.getPath());
                    String directoryPath = path.getParent() != null ? path.getParent().toString() : "";
                    String fileName = path.getFileName().toString();
                    FileStorageUtil.deleteFile(directoryPath, fileName);
                }

                if (!Objects.isNull(userChecksheetAnswerFile.getThumbnailPath()) && !userChecksheetAnswerFile.getThumbnailPath().isBlank()) {
                    Path thumbnailPath = Paths.get(userChecksheetAnswerFile.getThumbnailPath());
                    String thumbnailDirectoryPath = thumbnailPath.getParent() != null ? thumbnailPath.getParent().toString() : "";
                    String thumbnailFileName = thumbnailPath.getFileName().toString();
                    FileStorageUtil.deleteFile(thumbnailDirectoryPath, thumbnailFileName);
                }
            }

            userChecksheetAnswerFileRepository.deleteByIdIn(userChecksheetAnswerFileDTO.getUserChecksheetAnswerFileIds());
            return new ResponseDTO<>(true, "Inspection Answer File(s) are deleted successfully", userChecksheetAnswerFileDTO);
        } catch (CustomException ce) {
            throw ce;
        } catch (Exception e) {
            log.error("Inspection operation failed: {} ({})", e.getMessage(), e.getClass().getSimpleName(), e);
            throw new CustomException("Inspection operation failed: " + e.getMessage(), e, HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private static UserChecksheetAnswerFileDTO getUserChecksheetAnswerFileDTO(UserChecksheetAnswerFile userChecksheetAnswerFile, UserChecksheetAnswer userChecksheetAnswer) {
        UserChecksheetAnswerFileDTO fileDTO = new UserChecksheetAnswerFileDTO();
        fileDTO.setId(userChecksheetAnswerFile.getId());
        fileDTO.setUserChecksheetAnswerId(userChecksheetAnswer.getId());
        fileDTO.setMimeType(userChecksheetAnswerFile.getMimeType());
        fileDTO.setFileSizeBytes(userChecksheetAnswerFile.getFileSizeBytes());
        fileDTO.setFileHashSha256(userChecksheetAnswerFile.getFileHashSha256());
        fileDTO.setPath(userChecksheetAnswerFile.getPath());
        fileDTO.setThumbnailPath(userChecksheetAnswerFile.getThumbnailPath());
        fileDTO.setUrl(FileStorageUtil.getFileURL(userChecksheetAnswerFile.getPath()));
        return fileDTO;
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public ResponseDTO<?> createOrUpdateUserChksMtrxAns(List<UserChecksheetAnswerDTO> userChecksheetAnswers) throws CustomException {
        try{
            Inspection userChecksheet = null;
            UserChecksheetMatrixAnswers usrChksMtrxAns;
            ChksQuestionResult chksQuestionResult = null;
            ChksQuestionResultMatrix chksQuestionResultMatrix;
            User currentLoggedInUser = utilityService.getCurrentLoggedInUser().orElseThrow(() -> new CustomException("User is not authenticated.", HttpStatus.UNPROCESSABLE_ENTITY));
            for (UserChecksheetAnswerDTO userChecksheetMtrxAnswerDTO:userChecksheetAnswers){
                if(Objects.equals(userChecksheetMtrxAnswerDTO.getInspectionId(), null)){
                    throw new CustomException("Please provide User Checksheet Id", HttpStatus.UNPROCESSABLE_ENTITY);
                }else if(Objects.equals(userChecksheetMtrxAnswerDTO.getChksQuestionResultId(), null)){
                    throw new CustomException("Please provide Checksheet Question Result Id", HttpStatus.UNPROCESSABLE_ENTITY);
                }else if (Objects.equals(userChecksheetMtrxAnswerDTO.getChksQuestionResultMatrixId(), null)){
                    throw new CustomException("Please provide Checksheet Question Result Matrix Id", HttpStatus.UNPROCESSABLE_ENTITY);
                }else if (Objects.equals(userChecksheetMtrxAnswerDTO.getOrderNo(), null)){
                    throw new CustomException("Please provide Order No", HttpStatus.UNPROCESSABLE_ENTITY);
                }else if(Objects.equals(userChecksheetMtrxAnswerDTO.getResult(), null) || userChecksheetMtrxAnswerDTO.getResult().trim().isEmpty()){
                    throw new CustomException("Please provide Result", HttpStatus.UNPROCESSABLE_ENTITY);
                }
                // M1 addendum: caller must own the parent inspection AND it
                // must still be editable.
                assertOperatorCanEditInspection(userChecksheetMtrxAnswerDTO.getInspectionId());
                userChecksheet = userChecksheetRepository.findById(userChecksheetMtrxAnswerDTO.getInspectionId()).orElseThrow(() -> new CustomException("Please provide valid User Checksheet Id", HttpStatus.UNPROCESSABLE_ENTITY));
                chksQuestionResult = chksQuestionResultRepository.findById(userChecksheetMtrxAnswerDTO.getChksQuestionResultId()).orElseThrow(() -> new CustomException("Please provide valid Checksheet Question Result Id", HttpStatus.UNPROCESSABLE_ENTITY));
                chksQuestionResultMatrix = chksQuestionResultMatrixRepository.findById(userChecksheetMtrxAnswerDTO.getChksQuestionResultMatrixId()).orElseThrow(() -> new CustomException("Please provide valid Checksheet Question Result Matrix Id", HttpStatus.UNPROCESSABLE_ENTITY));

                Optional<UserChecksheetMatrixAnswers> tempUserChecksheetMtrxAnswer = userChecksheetMatrixAnswersRepository.findByInspectionIdAndChksQuestionResultIdAndChksQuestionResultMatrixIdAndOrderNo(
                        userChecksheetMtrxAnswerDTO.getInspectionId(), userChecksheetMtrxAnswerDTO.getChksQuestionResultId(),
                        userChecksheetMtrxAnswerDTO.getChksQuestionResultMatrixId(), userChecksheetMtrxAnswerDTO.getOrderNo());
                if(tempUserChecksheetMtrxAnswer.isPresent()) {
                    usrChksMtrxAns = tempUserChecksheetMtrxAnswer.get();
                    usrChksMtrxAns.setUpdatedBy(currentLoggedInUser);
                }else{
                    usrChksMtrxAns = new UserChecksheetMatrixAnswers();
                    usrChksMtrxAns.setCreatedBy(currentLoggedInUser);
                }
                usrChksMtrxAns.setInspection(userChecksheet);
                usrChksMtrxAns.setChksQuestion(chksQuestionResult.getChksQuestion());
                usrChksMtrxAns.setChksQuestionResult(chksQuestionResult);
                usrChksMtrxAns.setOrderNo(userChecksheetMtrxAnswerDTO.getOrderNo());
                usrChksMtrxAns.setChksQuestionResultMatrix(chksQuestionResultMatrix);
                usrChksMtrxAns.setResult(userChecksheetMtrxAnswerDTO.getResult().trim());
                usrChksMtrxAns.setOrderNo(userChecksheetMtrxAnswerDTO.getOrderNo());
                usrChksMtrxAns.setJudgement(userChecksheetMtrxAnswerDTO.getJudgement());
                usrChksMtrxAns.setMcResult(userChecksheetMtrxAnswerDTO.getMcResult());
                usrChksMtrxAns.setAnsweredAt(userChecksheetMtrxAnswerDTO.getAnsweredAt());
                userChecksheetMatrixAnswersRepository.save(usrChksMtrxAns);
                userChecksheetMtrxAnswerDTO.setId(usrChksMtrxAns.getId());
            }
            return new ResponseDTO<>(true, "Inspection Answer(s) are saved successfully",userChecksheetAnswers);
        } catch (CustomException ce) {
            throw ce;  // preserve original status (FORBIDDEN, NOT_FOUND, etc.)
        } catch ( Exception e ) {
            log.error("Inspection operation failed: {} ({})", e.getMessage(), e.getClass().getSimpleName(), e);
            throw new CustomException("Inspection operation failed: " + e.getMessage(), e, HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public ResponseDTO<?> createOrUpdateUserChksJudgements(List<UsrChecksheetAnsJudgementDTO> usrChecksheetAnsJudgementDTOS) throws CustomException {
        try{
            Optional<Inspection> userChecksheet;
            Optional<ChksQuestion> chksQuestion;
            Optional<UsrChksheetAnsJudgement> usrChksheetAnsJudgementOpt;
            UsrChksheetAnsJudgement usrChksheetAnsJudgement;

            Optional<User> currentLoggedInUser = utilityService.getCurrentLoggedInUser();

            for(UsrChecksheetAnsJudgementDTO usrChecksheetAnsJudgementDTO:usrChecksheetAnsJudgementDTOS){
                if(Objects.equals(usrChecksheetAnsJudgementDTO.getInspectionId(), null)){
                    throw new CustomException("Please provide User Checksheet Id", HttpStatus.UNPROCESSABLE_ENTITY);
                }else if(Objects.equals(usrChecksheetAnsJudgementDTO.getChksQuestionId(), null)){
                    throw new CustomException("Please provide Checksheet Question Id", HttpStatus.UNPROCESSABLE_ENTITY);
                }
                // M1 addendum: caller must own the parent inspection AND it
                // must still be editable.
                assertOperatorCanEditInspection(usrChecksheetAnsJudgementDTO.getInspectionId());
//                else if(usrChecksheetAnsJudgementDTO.getJudgement().trim().isEmpty()){
//                    throw new CustomException("Please provide judgement", HttpStatus.UNPROCESSABLE_ENTITY);
//                }
//                else if(usrChecksheetAnsJudgementDTO.getJudgement().trim().equals("NOT OK") && usrChecksheetAnsJudgementDTO.getRemarks().trim().isEmpty()){
//                    throw new CustomException("Please provide remarks", HttpStatus.UNPROCESSABLE_ENTITY);
//                }
                if(!Objects.equals(usrChecksheetAnsJudgementDTO.getId(), null)) {
                    usrChksheetAnsJudgementOpt = usrChksheetAnsJudgementRepository.findById(usrChecksheetAnsJudgementDTO.getId());
                    if(!usrChksheetAnsJudgementOpt.isPresent()){
                        throw new CustomException("Please provide valid user checksheet Question judgement Id", HttpStatus.UNPROCESSABLE_ENTITY);
                    }
                }else{
                    usrChksheetAnsJudgementOpt = usrChksheetAnsJudgementRepository.findByInspectionIdAndChksQuestionId(usrChecksheetAnsJudgementDTO.getInspectionId(), usrChecksheetAnsJudgementDTO.getChksQuestionId());
                }
                if(usrChksheetAnsJudgementOpt.isPresent()){
                    usrChksheetAnsJudgement = usrChksheetAnsJudgementOpt.get();
                    usrChksheetAnsJudgement.setUpdatedBy(currentLoggedInUser.get());
                }else{
                    usrChksheetAnsJudgement = new UsrChksheetAnsJudgement();
                    usrChksheetAnsJudgement.setCreatedBy(currentLoggedInUser.get());
                }

                userChecksheet = userChecksheetRepository.findById(usrChecksheetAnsJudgementDTO.getInspectionId());
                if(!userChecksheet.isPresent()){
                    throw new CustomException("Please provide valid User Checksheet Id", HttpStatus.UNPROCESSABLE_ENTITY);
                }else{
                    usrChksheetAnsJudgement.setInspection(userChecksheet.get());
                }
                chksQuestion = chksQuestionRepository.findById(usrChecksheetAnsJudgementDTO.getChksQuestionId());
                if(!chksQuestion.isPresent()){
                    throw new CustomException("Please provide valid Checksheet Question Id", HttpStatus.UNPROCESSABLE_ENTITY);
                }else{
                    usrChksheetAnsJudgement.setChksQuestion(chksQuestion.get());
                }
                usrChksheetAnsJudgement.setJudgement(usrChecksheetAnsJudgementDTO.getJudgement());
                usrChksheetAnsJudgement.setRemarks(usrChecksheetAnsJudgementDTO.getRemarks());
                usrChksheetAnsJudgementRepository.save(usrChksheetAnsJudgement);
                usrChecksheetAnsJudgementDTO.setId(usrChksheetAnsJudgement.getId());
            }
            return  new ResponseDTO<>(true, "Inspection Judgement(s) are saved successfully",usrChecksheetAnsJudgementDTOS);
        } catch (CustomException ce) {
            throw ce;  // preserve original status (FORBIDDEN, NOT_FOUND, etc.)
        } catch ( Exception e ) {
            log.error("Inspection operation failed: {} ({})", e.getMessage(), e.getClass().getSimpleName(), e);
            throw new CustomException("Inspection operation failed: " + e.getMessage(), e, HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    public ResponseDTO<?> getDeclinedUserChecksheets() throws CustomException {
        try{
            Optional<User> currentLoggedInUser = utilityService.getCurrentLoggedInUser();
            List<UserChecksheetDTO> declinedUserChecksheets = userChecksheetDAO.getDeclinedUserChecksheets(currentLoggedInUser.get().getId());
            return new ResponseDTO<>(true, "Data fetched successfully", declinedUserChecksheets);
        } catch (CustomException ce) {
            throw ce;  // preserve original status (FORBIDDEN, NOT_FOUND, etc.)
        } catch ( Exception e ) {
            log.error("Inspection operation failed: {} ({})", e.getMessage(), e.getClass().getSimpleName(), e);
            throw new CustomException("Inspection operation failed: " + e.getMessage(), e, HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    public ResponseDTO<?> getRespectedUserChecksheet(ChecksheetDTO checksheetDTO) throws CustomException {
        try {
            if(Objects.equals(checksheetDTO.getCurrentPage(), null) || Objects.equals(checksheetDTO.getCurrentPage(), "") ||
                    Objects.equals(checksheetDTO.getPerPageRecord(), null) || Objects.equals(checksheetDTO.getPerPageRecord(), "")) {
                return new ResponseDTO<>(false, "Please provide currentPage, perPageRecord");
            }
            Optional<User> currentLoggedInUser = utilityService.getCurrentLoggedInUser();
            List<UserRoleDepartment> userRoleByUserIdDepartment = userRoleDepartmentRepository.findByUser_IdAndDeletedByIsNull(currentLoggedInUser.get().getId());
            
            // Check if user has any checksheet-related permissions
            boolean hasChecksheetAccess = permissionService.hasPermission(currentLoggedInUser.get().getId(), "CHECKSHEET_FILL_ANSWER") ||
                                         permissionService.hasPermission(currentLoggedInUser.get().getId(), "CHECKSHEET_MANAGEMENT_TEMPLATE_CREATE") ||
                                         permissionService.hasPermission(currentLoggedInUser.get().getId(), "CHECKSHEET_MANAGEMENT_VALIDATOR_COMMENT_CREATE") ||
                                         permissionService.hasPermission(currentLoggedInUser.get().getId(), "CHECKSHEET_MANAGEMENT_APPROVER_COMMENT_CREATE") ||
                                         permissionService.hasPermission(currentLoggedInUser.get().getId(), "CHECKSHEET_DATA_VALIDATOR_COMMENT_CREATE") ||
                                         permissionService.hasPermission(currentLoggedInUser.get().getId(), "CHECKSHEET_DATA_APPROVER_COMMENT_CREATE");
            
            if(hasChecksheetAccess) {
                checksheetDTO.setCurrentUserId(currentLoggedInUser.get().getId());
            }
            
            // Filter by departments based on user's assigned departments
            List<Long> userDepartmentIds = userRoleByUserIdDepartment.stream()
                .map(urd -> urd.getDepartmentId().getId())
                .collect(Collectors.toList());
            
            // Check if user has department/subdepartment list permissions (can see subdepartments)
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

            Page<ChecksheetDTO> respectedChecksheet = userChecksheetDAO.getRespectedUserChecksheet(checksheetDTO, checksheetDTO.getCurrentPage(), checksheetDTO.getPerPageRecord());
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
        } catch (CustomException ce) {
            throw ce;  // preserve original status (FORBIDDEN, NOT_FOUND, etc.)
        } catch ( Exception e ) {
            log.error("Inspection operation failed: {} ({})", e.getMessage(), e.getClass().getSimpleName(), e);
            throw new CustomException("Inspection operation failed: " + e.getMessage(), e, HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public ResponseDTO<?> createOrUpdateUserChksGnrlFieldVals(List<ChksGeneralFieldValueDTO> userChksGnrlFieldVals) throws CustomException {
        try{
            Optional<ChksGeneralFieldValue> chksGeneralFieldValueOpt;
            Optional<Inspection> userChecksheet;
            ChksGeneralFieldValue chksGeneralFieldValue;
            Optional<ChksGeneralField> chksGeneralFieldOpt;
            ChksGeneralField chksGeneralField;
            Optional<User> currentLoggedInUser = utilityService.getCurrentLoggedInUser();
            for (ChksGeneralFieldValueDTO userChksGnrlFieldValDTO:userChksGnrlFieldVals){
                if(Objects.equals(userChksGnrlFieldValDTO.getInspectionId(), null)){
                    throw new CustomException("Please provide User Checksheet Id", HttpStatus.UNPROCESSABLE_ENTITY);
                }else if(Objects.equals(userChksGnrlFieldValDTO.getChksGeneralFieldId(), null)){
                    throw new CustomException("Please provide Checksheet General Field Id", HttpStatus.UNPROCESSABLE_ENTITY);
                }
                // M1 addendum: caller must own the parent inspection AND it
                // must still be editable.
                assertOperatorCanEditInspection(userChksGnrlFieldValDTO.getInspectionId());
                if(!Objects.equals(userChksGnrlFieldValDTO.getId(), null)) {
                    chksGeneralFieldValueOpt = chksGeneralFieldValueRepository.findById(userChksGnrlFieldValDTO.getId());
                    if(!chksGeneralFieldValueOpt.isPresent()){
                        throw new CustomException("Please provide Checksheet Id", HttpStatus.UNPROCESSABLE_ENTITY);
                    }else{
                        chksGeneralFieldValue = chksGeneralFieldValueOpt.get();
                        chksGeneralFieldValue.setUpdatedBy(currentLoggedInUser.get());
                    }
                }else{
                    chksGeneralFieldValueOpt = chksGeneralFieldValueRepository.findByInspectionIdAndChksGeneralFieldId(userChksGnrlFieldValDTO.getInspectionId(), userChksGnrlFieldValDTO.getChksGeneralFieldId());
                    if(chksGeneralFieldValueOpt.isPresent()){
                        chksGeneralFieldValue = chksGeneralFieldValueOpt.get();
                        chksGeneralFieldValue.setUpdatedBy(currentLoggedInUser.get());
                    }else{
                        chksGeneralFieldValue = new ChksGeneralFieldValue();
                        chksGeneralFieldValue.setCreatedBy(currentLoggedInUser.get());
                    }
                }
                userChecksheet = userChecksheetRepository.findById(userChksGnrlFieldValDTO.getInspectionId());
                if(!userChecksheet.isPresent()){
                    throw new CustomException("Please provide valid User Checksheet Id", HttpStatus.UNPROCESSABLE_ENTITY);
                }else{
                    chksGeneralFieldValue.setInspection(userChecksheet.get());
                }

                chksGeneralFieldOpt = chksGeneralFieldRepository.findById(userChksGnrlFieldValDTO.getChksGeneralFieldId());
                if(!chksGeneralFieldOpt.isPresent()){
                    throw new CustomException("Please provide valid Checksheet Question Result Id", HttpStatus.UNPROCESSABLE_ENTITY);
                }else{
                    chksGeneralFieldValue.setChksGeneralField(chksGeneralFieldOpt.get());
                }
                if(Objects.equals(userChksGnrlFieldValDTO.getValue(), null) || userChksGnrlFieldValDTO.getValue().trim().isEmpty()){
                    throw new CustomException("Please provide Answer", HttpStatus.UNPROCESSABLE_ENTITY);
                }else{
                    chksGeneralFieldValue.setValue(userChksGnrlFieldValDTO.getValue().trim());
                }
                chksGeneralFieldValueRepository.save(chksGeneralFieldValue);
                userChksGnrlFieldValDTO.setId(chksGeneralFieldValue.getId());
            }
            return new ResponseDTO<>(true, "Inspection General Field(s) are saved successfully",userChksGnrlFieldVals);
        } catch (CustomException ce) {
            throw ce;  // preserve original status (FORBIDDEN, NOT_FOUND, etc.)
        } catch ( Exception e ) {
            log.error("Inspection operation failed: {} ({})", e.getMessage(), e.getClass().getSimpleName(), e);
            throw new CustomException("Inspection operation failed: " + e.getMessage(), e, HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public ResponseDTO<?> createOrUpdateUserChksTraceValues(List<UserChecksheetTraceValueDTO> userChecksheetTraceValueDTOs) throws CustomException {
        try{
            Optional<UserChecksheetTraceValue> userChksTraceValOpt;
            Optional<Inspection> userChecksheet;
            UserChecksheetTraceValue userChksTraceVal;
            Optional<User> currentLoggedInUser = utilityService.getCurrentLoggedInUser();
            Optional<ChksHeaderData> chksHeaderDataOpt;
            ChksHeaderData chksHeaderData;
            for (UserChecksheetTraceValueDTO userChecksheetTraceValueDTO:userChecksheetTraceValueDTOs){
                if(Objects.equals(userChecksheetTraceValueDTO.getInspectionId(), null)){
                    throw new CustomException("Please provide User Checksheet Id", HttpStatus.UNPROCESSABLE_ENTITY);
                }else if(Objects.equals(userChecksheetTraceValueDTO.getChksHeaderDataId(), null)){
                    throw new CustomException("Please provide Checksheet General Field Id", HttpStatus.UNPROCESSABLE_ENTITY);
                }
                // M1 addendum: caller must own the parent inspection AND it
                // must still be editable.
                assertOperatorCanEditInspection(userChecksheetTraceValueDTO.getInspectionId());
                if(!Objects.equals(userChecksheetTraceValueDTO.getId(), null)) {
                    userChksTraceValOpt = userChecksheetTraceValueRepository.findById(userChecksheetTraceValueDTO.getId());
                    if(!userChksTraceValOpt.isPresent()){
                        throw new CustomException("Please provide Checksheet Id", HttpStatus.UNPROCESSABLE_ENTITY);
                    }else{
                        userChksTraceVal = userChksTraceValOpt.get();
                        userChksTraceVal.setUpdatedBy(currentLoggedInUser.get());
                    }
                }else{
                    userChksTraceValOpt = userChecksheetTraceValueRepository.findByInspectionIdAndChksHeaderDataId(userChecksheetTraceValueDTO.getInspectionId(), userChecksheetTraceValueDTO.getChksHeaderDataId());
                    if(userChksTraceValOpt.isPresent()){
                        userChksTraceVal = userChksTraceValOpt.get();
                        userChksTraceVal.setUpdatedBy(currentLoggedInUser.get());
                    }else{
                        userChksTraceVal = new UserChecksheetTraceValue();
                        userChksTraceVal.setCreatedBy(currentLoggedInUser.get());
                    }
                }
                userChecksheet = userChecksheetRepository.findById(userChecksheetTraceValueDTO.getInspectionId());
                if(!userChecksheet.isPresent()){
                    throw new CustomException("Please provide valid User Checksheet Id", HttpStatus.UNPROCESSABLE_ENTITY);
                }else{
                    userChksTraceVal.setInspection(userChecksheet.get());
                }

                chksHeaderDataOpt = chksHeaderDataRepository.findById(userChecksheetTraceValueDTO.getChksHeaderDataId());
                if(!chksHeaderDataOpt.isPresent()){
                    throw new CustomException("Please provide valid Checksheet Question Result Id", HttpStatus.UNPROCESSABLE_ENTITY);
                }else{
                    userChksTraceVal.setChksHeaderData(chksHeaderDataOpt.get());
                }
                if(Objects.equals(userChecksheetTraceValueDTO.getTraceValue(), null) || userChecksheetTraceValueDTO.getTraceValue().trim().isEmpty()){
                    throw new CustomException("Please provide Trace Value", HttpStatus.UNPROCESSABLE_ENTITY);
                }else{
                    userChksTraceVal.setTraceValue(userChecksheetTraceValueDTO.getTraceValue().trim());
                }
                userChecksheetTraceValueRepository.save(userChksTraceVal);
                userChecksheetTraceValueDTO.setId(userChksTraceVal.getId());
            }
            return new ResponseDTO<>(true, "Inspection Trace Value(s) are saved successfully",userChecksheetTraceValueDTOs);
        } catch (CustomException ce) {
            throw ce;  // preserve original status (FORBIDDEN, NOT_FOUND, etc.)
        } catch ( Exception e ) {
            log.error("Inspection operation failed: {} ({})", e.getMessage(), e.getClass().getSimpleName(), e);
            throw new CustomException("Inspection operation failed: " + e.getMessage(), e, HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public ResponseDTO<?> createOrUpdateUserChksJudgementFile(UsrChksheetAnsJudgementFileDTO usrChksheetAnsJudgementFileDTO) throws CustomException {
        try {
            Optional<UsrChksheetAnsJudgement> usrChksheetAnsJudgementOpt;
            Optional<UsrChksheetAnsJudgementFile> usrChksheetAnsJudgementFileOpt;
            UsrChksheetAnsJudgementFile usrChksheetAnsJudgementFile;
            Optional<Inspection> userChecksheetOpt;
            if(Objects.equals(usrChksheetAnsJudgementFileDTO.getInspectionId(), null)){
                throw new CustomException("Please provide User Checksheet Id", HttpStatus.UNPROCESSABLE_ENTITY);
            }else if(Objects.equals(usrChksheetAnsJudgementFileDTO.getUsrChksheetAnsJudgementId(), null)){
                throw new CustomException("Please provide Checksheet Answer Judgement Id", HttpStatus.UNPROCESSABLE_ENTITY);
            }
            // M1 addendum: caller must own the parent inspection AND it must
            // still be editable.
            assertOperatorCanEditInspection(usrChksheetAnsJudgementFileDTO.getInspectionId());
            Optional<User> currentLoggedInUser = utilityService.getCurrentLoggedInUser();
            if(!Objects.equals(usrChksheetAnsJudgementFileDTO.getId(), null)) {
                usrChksheetAnsJudgementFileOpt = usrChksheetAnsJudgementFileRepository.findById(usrChksheetAnsJudgementFileDTO.getId());
                if(!usrChksheetAnsJudgementFileOpt.isPresent()){
                    throw new CustomException("Please provide Checksheet Id", HttpStatus.UNPROCESSABLE_ENTITY);
                }else{
                    usrChksheetAnsJudgementFile = usrChksheetAnsJudgementFileOpt.get();
                    usrChksheetAnsJudgementFile.setUpdatedBy(currentLoggedInUser.get());
                }
            }else{
                usrChksheetAnsJudgementFile = new UsrChksheetAnsJudgementFile();
                usrChksheetAnsJudgementFile.setCreatedBy(currentLoggedInUser.get());
            }
            usrChksheetAnsJudgementOpt = usrChksheetAnsJudgementRepository.findById(usrChksheetAnsJudgementFileDTO.getUsrChksheetAnsJudgementId());
            if(usrChksheetAnsJudgementOpt.isPresent()){
                usrChksheetAnsJudgementFile.setUsrChksheetAnsJudgement(usrChksheetAnsJudgementOpt.get());
            }else{
                throw new CustomException("Please provide valid User Checksheet Judgement Id", HttpStatus.UNPROCESSABLE_ENTITY);
            }
            userChecksheetOpt = userChecksheetRepository.findById(usrChksheetAnsJudgementFileDTO.getInspectionId());
            if(!userChecksheetOpt.isPresent()){
                throw new CustomException("Please provide valid User Checksheet Id", HttpStatus.UNPROCESSABLE_ENTITY);
            }else{
                usrChksheetAnsJudgementFile.setInspection(userChecksheetOpt.get());
            }
            usrChksheetAnsJudgementFileRepository.save(usrChksheetAnsJudgementFile);
            usrChksheetAnsJudgementFileRepository.flush();
            usrChksheetAnsJudgementFileDTO.setId(usrChksheetAnsJudgementFile.getId());
            if (usrChksheetAnsJudgementFileDTO.getFile() != null) {
                String truncatedFilename = utilityService.getTruncatedFileName(usrChksheetAnsJudgementFileDTO.getFile().getOriginalFilename(), 50);

//                String fileName = "UsrChksheetAnsJudgementFile/" + usrChksheetAnsJudgementFile.getId() + "_" + truncatedFilename;
//                awss3Service.uploadMultipartFile(usrChksheetAnsJudgementFileDTO.getFile(), fileName);
                String fileName = FileStorageUtil.storeFile(usrChksheetAnsJudgementFileDTO.getFile(),"UsrChksheetAnsJudgementFile/", usrChksheetAnsJudgementFile.getId() + "_" + truncatedFilename);

                usrChksheetAnsJudgementFile.setPath(fileName);
                usrChksheetAnsJudgementFileRepository.save(usrChksheetAnsJudgementFile);
            }
            usrChksheetAnsJudgementFileDTO.setFile(null);
            return new ResponseDTO<>(true, "Inspection Answer Judgement Files are saved successfully",usrChksheetAnsJudgementFileDTO);
        } catch (CustomException ce) {
            throw ce;
        } catch (Exception e) {
            log.error("Inspection operation failed: {} ({})", e.getMessage(), e.getClass().getSimpleName(), e);
            throw new CustomException("Inspection operation failed: " + e.getMessage(), e, HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public ResponseDTO<?> deleteUserChksJudgementFile(UsrChksheetAnsJudgementFileDTO usrChksheetAnsJudgementFileDTO) throws CustomException {
        try {
            if(!usrChksheetAnsJudgementFileDTO.getUsrChksheetAnsJudgementFileIds().isEmpty()){
                List<UsrChksheetAnsJudgementFile> usrChksheetAnsJudgementFiles = usrChksheetAnsJudgementFileRepository.findByIdIn(usrChksheetAnsJudgementFileDTO.getUsrChksheetAnsJudgementFileIds());
                if(usrChksheetAnsJudgementFiles.size() == usrChksheetAnsJudgementFileDTO.getUsrChksheetAnsJudgementFileIds().size()){
                    Path path;
                    String directoryPath,fileName;
                    for(UsrChksheetAnsJudgementFile usrChksheetAnsJudgementFile:usrChksheetAnsJudgementFiles){
//                        awss3Service.deleteFile(usrChksheetAnsJudgementFile.getPath());
                        path = Paths.get(usrChksheetAnsJudgementFile.getPath());
                        directoryPath = path.getParent() != null ? path.getParent().toString() : "";
                        fileName = path.getFileName().toString();
                        FileStorageUtil.deleteFile(directoryPath,fileName);
                    }
                    usrChksheetAnsJudgementFileRepository.deleteByIdIn(usrChksheetAnsJudgementFileDTO.getUsrChksheetAnsJudgementFileIds());
                }else{
                    throw new CustomException("Please provide valid User Checksheet Judgement File Id", HttpStatus.UNPROCESSABLE_ENTITY);
                }
            }
            return new ResponseDTO<>(true, "Inspection Answer Judgement Files are deleted successfully",usrChksheetAnsJudgementFileDTO);
        } catch (CustomException ce) {
            throw ce;
        } catch (Exception e) {
            log.error("Inspection operation failed: {} ({})", e.getMessage(), e.getClass().getSimpleName(), e);
            throw new CustomException("Inspection operation failed: " + e.getMessage(), e, HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    public ResponseDTO<?> getUserChecksheetWithAnswers(UserChecksheetDTO userChecksheetDTO) throws CustomException {
        try {
            if (Objects.isNull(userChecksheetDTO) || Objects.isNull(userChecksheetDTO.getId())) {
                throw new CustomException("Please provide userChecksheetId", HttpStatus.UNPROCESSABLE_ENTITY);
            }

            // Get user checksheet
            Optional<Inspection> userChecksheetOpt = userChecksheetRepository.findById(userChecksheetDTO.getId());
            if (!userChecksheetOpt.isPresent()) {
                throw new CustomException("Invalid userChecksheetId", HttpStatus.UNPROCESSABLE_ENTITY);
            }

            Inspection userChecksheet = userChecksheetOpt.get();
            // Authorization: an audit-report contains the full operator-recorded
            // content + photos + AI verdicts, so the read must be gated by
            // scope, not just authentication. Allowed callers:
            //   (a) anyone with the AUDIT_VIEW permission (senior reviewers —
            //       Dept Head, Section Head, Super Admin),
            //   (b) the operator who actually filled this UC,
            //   (c) the Dealer Principal of the dealership this UC was performed at.
            // Anyone else gets 403 — without this gate, any authenticated user
            // could read any UC by changing the URL.
            assertUserChecksheetVisible(userChecksheet);
            Long checksheetId = userChecksheet.getChecksheet().getId();

            // Get checksheet data
            ResponseDTO<?> checksheetData = chksHeaderDataService.getChecksheetData(checksheetId, true);
            
            // Get user answers
            List<UserChecksheetAnswerDTO> ucAnswrs= userChecksheetAnswerDAO.getUserChecksheetAnswers(userChecksheetDTO.getId());
            Map<Long, UserChecksheetAnswerDTO> regularAnswers = ucAnswrs.stream().collect(Collectors.toMap(
                                                                                    UserChecksheetAnswerDTO::getChksQuestionResultId,
                                                                                    answer -> answer
                                                                                ));
            int okCnt = 0, notOkCnt = 0;
            for(UserChecksheetAnswerDTO ans : ucAnswrs){
                // Judgement comes from usr_chksheet_ans_judgements via LEFT JOIN —
                // can be null when no judgement record exists yet (e.g. answers
                // saved without a separate judgement row, as the seed and the
                // mobile app currently do). Fall back to the integer judgement
                // on the answer row itself: 1 = OK, 2 = NOT OK.
                String j = ans.getChecksheetQuestionJudgement();
                if (j == null) {
                    Short raw = ans.getJudgement();
                    if (raw != null) {
                        j = raw == 1 ? "OK" : "NOT OK";
                    }
                }
                if ("OK".equals(j)) okCnt++;
                else if ("NOT OK".equals(j)) notOkCnt++;
            }

            // Get matrix answers
//            Map<String, UserChecksheetMatrixAnswerDTO> matrixAnswers = userChecksheetAnswerDAO
//                .getUserChecksheetMatrixAnswers(userChecksheetDTO.getId())
//                .stream()
//                .collect(Collectors.toMap(
//                    answer -> answer.getChksQuestionResultId() + "_" + answer.getChksQuestionResultMatrixId() + "_" + answer.getOrderNo(),
//                    answer -> answer
//                ));

            // Merge answers with checksheet data
            Map<String, Object> checksheetContent = (Map<String, Object>) checksheetData.getData();
            checksheetContent.put("okCnt",okCnt);
            checksheetContent.put("notOkCnt",notOkCnt);
            List<ChksHeaderDataDTO> contentData = (List<ChksHeaderDataDTO>) checksheetContent.get("chksContentData");

            List<ChksGeneralFieldDTO> generalFieldData = (List<ChksGeneralFieldDTO>) checksheetContent.get("chksGeneralColumn");
            List<ChksGeneralFieldValue> usrChksGnrlFieldVals = chksGeneralFieldValueRepository.findByInspection_Id(userChecksheetDTO.getId());
            Map<Long, String> valueMap = usrChksGnrlFieldVals.stream()
                    .collect(Collectors.toMap(
                            fieldValue -> fieldValue.getChksGeneralField().getId(),
                            ChksGeneralFieldValue::getValue,
                            (existing, replacement) -> existing
                    ));
            // Now map the answer field in generalFieldData
            generalFieldData.forEach(dto ->
                    dto.setAnswer(valueMap.getOrDefault(dto.getId(), null))
            );

            // Pre-fetch AI assessments for this UC, keyed by chks_question_result_id.
            // We pass them into the merge so the renderer can show "AI: OK · 90%"
            // / "AI: NOT OK" + explanation, or "AI: NOT AVAILABLE" when no row exists.
            Map<Long, com.checkSheet.entity.AiAssessment> aiByResult =
                aiAssessmentRepository
                    .findByInspectionIdAndDeletedAtIsNullOrderByAssessedAtDesc(userChecksheetDTO.getId())
                    .stream()
                    // Multiple AI runs per result are possible; take the most recent.
                    .collect(Collectors.toMap(
                        a -> a.getChksQuestionResult().getId(),
                        a -> a,
                        (existing, replacement) -> existing
                    ));

            mergeAnswersWithContent(contentData, regularAnswers, aiByResult, userChecksheetDTO);

            return new ResponseDTO<>(true, "Data fetched successfully", checksheetContent);
        } catch (CustomException ce) {
            throw ce;
        } catch (Exception e) {
            log.error("Error fetching user checksheet data", e);
            throw new CustomException("Error fetching data", e, HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    public void downloadUserChecksheetWithAnswers(UserChecksheetDTO userChecksheetDTO, HttpServletResponse response) throws CustomException {
        try{
//            dwnlExlWithImgTxtInSingleCell(response);

            Inspection userChecksheet = userChecksheetRepository.findById(userChecksheetDTO.getId()).orElseThrow(() -> new CustomException("Invalid userChecksheetId", HttpStatus.UNPROCESSABLE_ENTITY));
            Checksheet chks = userChecksheet.getChecksheet();

            Object data = getUserChecksheetWithAnswers(userChecksheetDTO).getData();
            Map<String, Object> userChksContentData = new HashMap<>();
            List<ChksHeaderDataDTO> chksHeaderDataDTOs = new ArrayList<>();
            if (data instanceof Map<?, ?> tempMap) {
                for (Map.Entry<?, ?> entry : tempMap.entrySet()) {
                    if (entry.getKey() instanceof String) {
                        userChksContentData.put((String) entry.getKey(), entry.getValue());
                    }
                }
            }
            if(userChksContentData.containsKey("chksContentData")){
                List<?> contentData = (List<?>) userChksContentData.get("chksContentData");
                chksHeaderDataDTOs = contentData.stream()
                                                .filter(ChksHeaderDataDTO.class::isInstance)
                                                .map(ChksHeaderDataDTO.class::cast)
                                                .toList();
            }

            List<ChksGeneralFieldDTO> generalFieldData = new ArrayList<>();
            if(userChksContentData.containsKey("chksGeneralColumn")){
                List<?> contentData = (List<?>) userChksContentData.get("chksGeneralColumn");
                generalFieldData = contentData.stream()
                        .filter(ChksGeneralFieldDTO.class::isInstance)
                        .map(ChksGeneralFieldDTO.class::cast)
                        .toList();
            }

            Map<String, Object> hdrCols = new HashMap<>();
            List<ChksHeaderDTO> chksHeaderResultFalse = new ArrayList<>(0);
            List<ChksHeaderDTO> chksHeaderResultTrue = new ArrayList<>(0);
            if(userChksContentData.containsKey("chksHeaderColumn")){
                if (userChksContentData.get("chksHeaderColumn") instanceof Map<?, ?> tempMap) {
                    for (Map.Entry<?, ?> entry : tempMap.entrySet()) {
                        if (entry.getKey() instanceof String) {
                            hdrCols.put((String) entry.getKey(), entry.getValue());
                        }
                    }
                }
                if(hdrCols.containsKey("headerResultFalse")){
                    List<?> contentData = (List<?>) hdrCols.get("headerResultFalse");
                    chksHeaderResultFalse = contentData.stream()
                            .filter(ChksHeaderDTO.class::isInstance)
                            .map(ChksHeaderDTO.class::cast)
                            .toList();
                }
                if(hdrCols.containsKey("headerResultTrue")){
                    List<?> contentData = (List<?>) hdrCols.get("headerResultTrue");
                    chksHeaderResultTrue = contentData.stream()
                            .filter(ChksHeaderDTO.class::isInstance)
                            .map(ChksHeaderDTO.class::cast)
                            .toList();
                }
            }
            SXSSFWorkbook workbook =  new SXSSFWorkbook(1000);
            SXSSFSheet sheet = workbook.createSheet("User Checksheet");
            sheet.trackAllColumnsForAutoSizing();
            int rowCount = 0, columnCnt = 0;
            Row row;
            CellStyle headerStyle = setCellStyle(workbook, true);
            CellStyle hdrDataStyle = setCellStyle(workbook, false);
            row = sheet.createRow(rowCount++);
            createCell(row, columnCnt++, chks.getName(), headerStyle);
            createCell(row, columnCnt++, chks.getUid(), headerStyle);

            //Get Data validators
            List<User> users = userRepository.findByIdIn(chks.getDataValidatorUserIds());
            StringBuilder userNames = new StringBuilder();
            for(User u: users){
                userNames.append(u.getFirstName()).append(" ").append(u.getLastName()).append(" (").append(u.getUsername()).append(")").append("\n");
            }
            createCell(row, columnCnt++, "Data Validator", headerStyle);
            createCell(row, columnCnt++, userNames.toString(), hdrDataStyle);

            //Get Data Approvers
            users = userRepository.findByIdIn(chks.getDataApproverUserIds());
            userNames = new StringBuilder();
            for(User u: users){
                userNames.append(u.getFirstName()).append(" ").append(u.getLastName()).append(" (").append(u.getUsername()).append(")").append("\n");
            }
            createCell(row, columnCnt++, "Data Approver", headerStyle);
            createCell(row, columnCnt, userNames.toString(), hdrDataStyle);

            columnCnt =0;
            for(ChksGeneralFieldDTO generalField : generalFieldData){
                row = sheet.createRow(rowCount++);
                createCell(row, columnCnt, generalField.getName(), headerStyle);
            }
            row = sheet.createRow(rowCount);
            for(ChksHeaderDTO chksHeader:chksHeaderResultFalse){
                createCell(row, columnCnt++, chksHeader.getName(), headerStyle);
            }
            for(ChksHeaderDTO chksHeader:chksHeaderResultTrue){
                createCell(row, columnCnt++, chksHeader.getName(), headerStyle);
            }
            createCell(row, columnCnt, "Judgement", headerStyle);
            rowCount++;
            List<List<CellContent>> contentRows = new ArrayList<>();
            Map<Long, List<UserChecksheetMatrixAnswers>> mapUserChecksheetMatrixAnswers = new HashMap<>();
            mapUserChecksheetMatrixAnswers = userChecksheetMatrixAnswersRepository.findByInspection_IdInOrderByOrderNo(List.of(userChecksheetDTO.getId())).stream().collect(Collectors.groupingBy(corm -> corm.getChksQuestionResult().getId()));

            extractContent(chksHeaderDataDTOs, new ArrayList<>(),contentRows,chksHeaderResultTrue, workbook,mapUserChecksheetMatrixAnswers, new AtomicInteger(1));
            CreationHelper createHelper = workbook.getCreationHelper();
            Map<Integer, Integer> lastRowForColumn = new HashMap<>();
            for(List<CellContent> contentRow:contentRows){
                row = sheet.createRow(rowCount);
                for (int i = 0; i < contentRow.size(); i++) {
                    if (i < contentRow.size() - (chksHeaderResultTrue.size()+2)) { // Merge cells except for result + judgement + question columns
                        if (!contentRow.get(i).getContent().isEmpty() && (!lastRowForColumn.containsKey(i) || !contentRow.get(i).getContent().equals(sheet.getRow(lastRowForColumn.get(i)).getCell(i).getStringCellValue()))) {
                            lastRowForColumn.put(i, rowCount);
                            createCell(row, i, contentRow.get(i).getContent(), hdrDataStyle);
                            if(!contentRow.get(i).getFilePaths().isEmpty()){
                                insertImageToCell(workbook,sheet,contentRow.get(i).getFilePaths().get(0),rowCount,i);
//                                insertImageToCell(workbook,sheet,"189_image_2.png",rowCount,i);
                            }
//                        } else {
//                            sheet.addMergedRegion(new CellRangeAddress(lastRowForColumn.get(i), rowCount, i, i));
                        }
                    } else {
                        if(contentRow.get(i).getContent().contains("_MATRIX_")){
                            Hyperlink hyperlink = createHelper.createHyperlink(HyperlinkType.DOCUMENT);
                            hyperlink.setAddress("'"+contentRow.get(i).getContent()+"'!A1");
                            createCell(row, i, contentRow.get(i).getContent(), hdrDataStyle,hyperlink);
                        }else{
                            createCell(row, i, contentRow.get(i).getContent(), hdrDataStyle);
                            if(!contentRow.get(i).getFilePaths().isEmpty()){
                                insertImageToCell(workbook,sheet,contentRow.get(i).getFilePaths().get(0),rowCount,i);
//                                insertImageToCell(workbook,sheet,"189_image_2.png",rowCount,i);
                            }
                        }
                    }

                }
                rowCount++;
            }

            rowCount++;
            contentRows = new ArrayList<>();
            extractChksVersionData(chks, new ArrayList<>(), contentRows);
            for(List<CellContent> contentRow:contentRows){
                row = sheet.createRow(rowCount);
                for (int i = 0; i < contentRow.size(); i++) {
                    createCell(row, i, contentRow.get(i).getContent(), hdrDataStyle);
                }
                rowCount++;
            }
            List<String> headers = List.of("No.", "Revision Number", "Revision Date","Revision Details","Prepared By","Validated By","Approved By");
            row = sheet.createRow(rowCount);
            columnCnt = 0;
            for(String hdr : headers){
                createCell(row, columnCnt++, hdr, headerStyle);
            }
            // Auto-size columns
            int headerSize = chksHeaderResultFalse.size()+ chksHeaderResultTrue.size()+1;
            for (int i = 0; i < headerSize; i++) {
                sheet.autoSizeColumn(i);
            }
            ServletOutputStream outputStream = response.getOutputStream();
            workbook.write(outputStream);
            workbook.close();
            outputStream.close();
        } catch (Exception e) {
            log.error("Failed to generate Inspection Excel", e);
            throw new CustomException("Something went wrong", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    public void downloadUserChecksheetWithAnswersPdf(UserChecksheetDTO userChecksheetDTO, HttpServletResponse response) throws CustomException {
        try {
            Inspection userChecksheet = userChecksheetRepository.findById(userChecksheetDTO.getId())
                    .orElseThrow(() -> new CustomException("Invalid userChecksheetId", HttpStatus.UNPROCESSABLE_ENTITY));
            Checksheet checksheet = userChecksheet.getChecksheet();

            Object data = getUserChecksheetWithAnswers(userChecksheetDTO).getData();
            Map<String, Object> userChksContentData = new HashMap<>();
            if (data instanceof Map<?, ?> tempMap) {
                for (Map.Entry<?, ?> entry : tempMap.entrySet()) {
                    if (entry.getKey() instanceof String) {
                        userChksContentData.put((String) entry.getKey(), entry.getValue());
                    }
                }
            }

            ReportMetrics reportMetrics = prepareReportMetrics(userChksContentData);
            writeUserChecksheetPdf(response, checksheet, userChecksheet, reportMetrics);
        } catch (CustomException ce) {
            throw ce;
        } catch (Exception e) {
            log.error("Failed to generate Inspection PDF", e);
            throw new CustomException("Unable to generate Inspection PDF", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private ReportMetrics prepareReportMetrics(Map<String, Object> userChksContentData) {
        ReportMetrics metrics = new ReportMetrics();
        long sourceOkCount = parseLong(userChksContentData.get("okCnt"));
        long sourceNotOkCount = parseLong(userChksContentData.get("notOkCnt"));

        if (userChksContentData.containsKey("chksContentData")) {
            List<?> contentData = (List<?>) userChksContentData.get("chksContentData");
            List<ChksHeaderDataDTO> rootHeaders = contentData.stream()
                    .filter(ChksHeaderDataDTO.class::isInstance)
                    .map(ChksHeaderDataDTO.class::cast)
                    .toList();

            for (ChksHeaderDataDTO rootHeader : rootHeaders) {
                CategoryMetrics category = new CategoryMetrics();
                category.name = Optional.ofNullable(rootHeader.getName()).orElse("-");
                populateCategoryMetrics(rootHeader, category, metrics.questionCards, new ArrayList<>(), true);
                category.total = category.totalQuestions;
                category.score = category.totalQuestions == 0 ? 0d : (category.ok * 100d) / category.totalQuestions;
                metrics.categories.add(category);
            }
        }

        for (CategoryMetrics category : metrics.categories) {
            metrics.totalOk += category.ok;
            metrics.totalNotOk += category.notOk;
            metrics.totalCheckpoints += category.totalQuestions;
        }

        if (metrics.totalCheckpoints == 0) {
            metrics.totalOk = sourceOkCount;
            metrics.totalNotOk = sourceNotOkCount;
            metrics.totalCheckpoints = sourceOkCount + sourceNotOkCount;
        }

        metrics.overallScore = metrics.totalCheckpoints == 0 ? 0d : (metrics.totalOk * 100d) / metrics.totalCheckpoints;
        return metrics;
    }

    private void populateCategoryMetrics(
            ChksHeaderDataDTO headerData,
            CategoryMetrics category,
            List<QuestionCard> cards,
            List<String> hierarchyPath,
            boolean isRoot
    ) {
        if (headerData == null) {
            return;
        }
        List<String> currentPath = new ArrayList<>(hierarchyPath);
        if (!isRoot) {
            currentPath.add(Optional.ofNullable(headerData.getName()).orElse("-"));
        }
        List<ChksQuestionDTO> questions = headerData.getQuestions();
        if (questions != null) {
            for (ChksQuestionDTO question : questions) {
                QuestionCard card = new QuestionCard();
                card.categoryName = category.name;
                card.question = Optional.ofNullable(question.getName()).orElse("-");
                card.subText = Optional.ofNullable(question.getDescription()).orElse("-");
                card.remark = Optional.ofNullable(question.getJudgement()).orElse("-");
                card.auditorRemark = Optional.ofNullable(question.getRemarks()).orElse("-");
                card.childHierarchy = currentPath.isEmpty() ? "-" : String.join(" > ", currentPath);
                card.status = resolveQuestionStatus(question);
                card.aiText = resolveAiText(question, card.status);
                cards.add(card);
                category.totalQuestions++;

                if ("OK".equals(card.status)) {
                    category.ok++;
                } else if ("NOT OK".equals(card.status)) {
                    category.notOk++;
                }
            }
        }

        List<ChksHeaderDataDTO> children = headerData.getChildren();
        if (children != null) {
            for (ChksHeaderDataDTO child : children) {
                populateCategoryMetrics(child, category, cards, currentPath, false);
            }
        }
    }

    private String resolveQuestionStatus(ChksQuestionDTO question) {
        if (question.getChksQuestionResults() != null) {
            for (ChksQuestionResultDTO result : question.getChksQuestionResults()) {
                String option = normalizeStatus(getOption(result));
                if (!option.equals("-") && !option.equals("NA")) {
                    return option;
                }
            }
        }

        String judgementStatus = normalizeStatus(question.getJudgement());
        if (!judgementStatus.equals("-") && !judgementStatus.equals("NA")) {
            return judgementStatus;
        }

        String remarksStatus = normalizeStatus(question.getRemarks());
        if (!remarksStatus.equals("-") && !remarksStatus.equals("NA")) {
            return remarksStatus;
        }
        return "NA";
    }

    private String resolveAiText(ChksQuestionDTO question, String status) {
        if ("OK".equals(status) || "NOT OK".equals(status)) {
            return "AI: " + status;
        }
        String judgementStatus = normalizeStatus(question.getJudgement());
        if ("OK".equals(judgementStatus) || "NOT OK".equals(judgementStatus)) {
            return "AI: " + judgementStatus;
        }
        return "AI: NA";
    }

    private String normalizeStatus(String raw) {
        String value = Optional.ofNullable(raw).orElse("").trim().toUpperCase();
        if (value.contains("NOT OK") || value.contains("NOTOK") || value.contains("FAIL")) {
            return "NOT OK";
        }
        if ("OK".equals(value) || value.contains(" OK") || value.contains("PASS")) {
            return "OK";
        }
        if (value.contains("NA")) {
            return "NA";
        }
        return "-";
    }

    private void writeUserChecksheetPdf(HttpServletResponse response, Checksheet checksheet, Inspection userChecksheet, ReportMetrics metrics) throws IOException {
        String html = buildAuditReportHtml(checksheet, userChecksheet, metrics);
        PdfRendererBuilder builder = new PdfRendererBuilder();
        builder.useFastMode();
        builder.withHtmlContent(html, null);
        builder.toStream(response.getOutputStream());
        builder.run();
    }

    private String buildAuditReportHtml(Checksheet checksheet, Inspection userChecksheet, ReportMetrics metrics) {
        DateFormat dateFormat = new SimpleDateFormat("dd MMM yyyy");
        String auditDate = userChecksheet.getSubmittedAt() == null ? "-" : dateFormat.format(userChecksheet.getSubmittedAt());
        String auditorName = Optional.ofNullable(userChecksheet.getCreatedBy())
                .map(u -> {
                    String fullName = ((Optional.ofNullable(u.getFirstName()).orElse("")) + " " + (Optional.ofNullable(u.getLastName()).orElse(""))).trim();
                    if (!fullName.isBlank()) {
                        return fullName;
                    }
                    return Optional.ofNullable(u.getUsername()).orElse("-");
                })
                .orElse("-");
        String reportTitle = escapeHtml(Optional.ofNullable(checksheet.getName()).orElse("Dealer Audit Report"));
        String auditRef = escapeHtml(Optional.ofNullable(checksheet.getUid()).orElse("-"));
        String overallBand = classifyBand((float) metrics.overallScore);
        String bandClass = bandCssClass((float) metrics.overallScore);
        String overallScore = String.format("%.0f%%", metrics.overallScore);
        String overallScoreNumeric = String.format("%.0f", metrics.overallScore);
        String locationLine = escapeHtml(Optional.ofNullable(checksheet.getDescription()).orElse("-"));

        StringBuilder categoriesHtml = new StringBuilder();
        int srNo = 1;
        for (CategoryMetrics category : metrics.categories) {
            categoriesHtml.append("<tr>")
                    .append("<td>").append(srNo++).append(". ").append(escapeHtml(category.name)).append("</td>")
                    .append("<td class='score ").append(scoreCssClass(category.score)).append("'>").append(String.format("%.0f%%", category.score)).append("</td>")
                    .append("<td>").append(category.ok).append("</td>")
                    .append("<td class='bad'>").append(category.notOk).append("</td>")
                    .append("<td>").append(category.total).append("</td>")
                    .append("</tr>");
        }

        LinkedHashMap<String, List<QuestionCard>> cardsBySection = new LinkedHashMap<>();
        for (CategoryMetrics category : metrics.categories) {
            cardsBySection.put(category.name, new ArrayList<>());
        }
        for (QuestionCard card : metrics.questionCards) {
            cardsBySection.computeIfAbsent(card.categoryName, ignored -> new ArrayList<>()).add(card);
        }

        StringBuilder sectionsHtml = new StringBuilder();
        int sectionSeq = 1;
        for (CategoryMetrics category : metrics.categories) {
            List<QuestionCard> sectionCards = cardsBySection.getOrDefault(category.name, List.of());
            sectionsHtml.append("<div class='section-block'>")
                    .append("<div class='section-title-row'>")
                    .append("<div class='section-title-cell'><div class='section-title'>").append(escapeHtml(category.name)).append("</div></div>")
                    .append("<div class='section-meta-cell'><div class='section-meta'>")
                    .append("<span class='section-score ").append(scoreCssClass(category.score)).append("'>").append(String.format("%.0f%%", category.score)).append("</span>")
                    .append("<span class='section-count ok'>").append(category.ok).append(" OK</span>")
                    .append("<span class='section-count notok'>").append(category.notOk).append(" NOT OK</span>")
                    .append("</div></div>")
                    .append("</div>")
                    .append("<table class='cards-table'><tbody>");

            int subSectionSeq = 1;
            for (int i = 0; i < sectionCards.size(); i += 2) {
                sectionsHtml.append("<tr>");
                String leftNumber = sectionSeq + "." + subSectionSeq++;
                sectionsHtml.append("<td class='card-cell'>")
                        .append(buildQuestionCardHtml(sectionCards.get(i), leftNumber))
                        .append("</td>");
                if (i + 1 < sectionCards.size()) {
                    String rightNumber = sectionSeq + "." + subSectionSeq++;
                    sectionsHtml.append("<td class='card-cell'>")
                            .append(buildQuestionCardHtml(sectionCards.get(i + 1), rightNumber))
                            .append("</td>");
                } else {
                    sectionsHtml.append("<td class='card-cell'></td>");
                }
                sectionsHtml.append("</tr>");
            }
            sectionsHtml.append("</tbody></table></div>");
            sectionSeq++;
        }

        return """
                <!DOCTYPE html>
                <html>
                <head>
                    <meta charset='UTF-8' />
                    <style>
                        @page { size: A4; margin: 14mm 10mm 12mm 10mm; }
                        * { box-sizing: border-box; font-family: Arial, sans-serif; }
                        body { margin: 0; color: #1f2937; font-size: 10px; }
                        .title-row { display: table; width: 100%%; table-layout: fixed; border-bottom: 2px solid #1f2937; padding-bottom: 6px; }
                        .title-left { display: table-cell; width: 72%%; vertical-align: top; }
                        .title-right { display: table-cell; width: 28%%; vertical-align: top; text-align: right; }
                        .title { font-size: 24px; font-weight: 700; color: #1f2937; }
                        .subtitle { margin-top: 2px; color: #6b7280; font-size: 11px; }
                        .ref { text-align: right; font-size: 10px; color: #4b5563; line-height: 1.25; min-width: 170px; }
                        .kpi-panel { margin-top: 8px; border: none; padding: 0; }
                        .kpi-wrap { display: table; width: 100%%; table-layout: fixed; border-collapse: separate; border-spacing: 8px 0; }
                        .kpi-left { display: table-cell; width: 84px; vertical-align: middle; }
                        .kpi-right { display: table-cell; vertical-align: middle; }
                        .score-tile { background: #fff5f5; border-radius: 8px; text-align: center; padding: 7px 4px 5px; }
                        .score-tile .value { color: #dc2626; font-size: 30px; font-weight: 800; line-height: 26px; letter-spacing: 0.2px; margin-bottom: 8px; }
                        .score-tile .divider { color: #dc2626; font-size: 16px; line-height: 12px; font-weight: 700; margin-bottom: 2px; }
                        .score-tile .base { color: #b45309; font-size: 16px; line-height: 14px; font-weight: 700; }
                        .kpi-inline { white-space: nowrap; margin-bottom: 5px; }
                        .kpi-chip { display: inline-block; margin-right: 12px; font-size: 22px; font-weight: 700; line-height: 1; }
                        .kpi-chip small { font-size: 15px; font-weight: 600; margin-left: 4px; color: #111827; }
                        .kpi-chip.ok { color: #059669; }
                        .kpi-chip.notok { color: #dc2626; }
                        .kpi-chip.total { color: #111827; }
                        .band-row { font-size: 14px; color: #94a3b8; }
                        .band-row .band-label { margin-right: 8px; }
                        .band-pill { display: inline-block; padding: 2px 7px; border-radius: 10px; font-size: 14px; font-weight: 700; line-height: 1.1; }
                        .band-pill.green { background: #dcfce7; color: #15803d; }
                        .band-pill.yellow { background: #fef3c7; color: #b45309; }
                        .band-pill.red { background: #fee2e2; color: #b91c1c; }
                        .ok { color: #169b62; }
                        .notok { color: #dc2626; }
                        .total { color: #111827; }
                        .band { margin-top: 6px; font-size: 12px; font-weight: 700; }
                        .band.green { color: #169b62; }
                        .band.yellow { color: #d97706; }
                        .band.red { color: #dc2626; }
                        .section-table { margin-top: 10px; border: 1px solid #d1d5db; border-radius: 4px; overflow: hidden; }
                        table { width: 100%%; border-collapse: collapse; }
                        th, td { border-bottom: 1px solid #e5e7eb; padding: 6px; font-size: 10px; }
                        th { background: #f3f4f6; text-align: left; font-weight: 700; }
                        td.score { font-weight: 700; }
                        td.score.good { color: #169b62; }
                        td.score.mid { color: #d97706; }
                        td.score.low { color: #dc2626; }
                        td.bad { color: #dc2626; }
                        .sections-wrap { margin-top: 12px; }
                        .section-block { margin-bottom: 10px; width: 100%%; }
                        .section-title-row { border-top: none; border-bottom: 3px solid #111827; background: #ffffff; padding: 6px 0; display: table; width: 100%%; table-layout: fixed; }
                        .section-title-cell { display: table-cell; vertical-align: middle; width: 68%%; }
                        .section-meta-cell { display: table-cell; vertical-align: middle; width: 32%%; text-align: right; }
                        .section-title { font-size: 12px; font-weight: 700; color: #111827; }
                        .section-meta { text-align: right; white-space: nowrap; }
                        .section-score { font-size: 11px; font-weight: 700; margin-right: 12px; vertical-align: middle; display: inline-block; }
                        .section-score.good { color: #169b62; }
                        .section-score.mid { color: #d97706; }
                        .section-score.low { color: #dc2626; }
                        .section-count { font-size: 10px; font-weight: 700; white-space: nowrap; vertical-align: middle; display: inline-block; }
                        .section-count.ok { margin-left: 6px; margin-right: 16px; }
                        .section-count.notok { margin-left: 0; margin-right: 0; }
                        .cards-table { width: 100%%; border-collapse: separate; border-spacing: 0 8px; table-layout: fixed; }
                        .cards-table td { border: none; padding: 0; vertical-align: top; width: 50%%; }
                        .cards-table tr td:first-child { padding-right: 6px; }
                        .q-card { border: 1px solid #dbe2ea; border-radius: 8px; padding: 6px; min-height: 150px; }
                        .q-layout { display: table; width: 100%%; table-layout: fixed; height: 130px; }
                        .q-left { display: table-cell; width: 75%%; vertical-align: top; padding-right: 10px; }
                        .q-right { display: table-cell; width: 25%%; vertical-align: top; text-align: right; padding: 2px 0 2px 6px; }
                        .q-header { display: table; width: 100%%; table-layout: fixed; }
                        .q-title { display: table-cell; vertical-align: top; font-size: 13px; font-weight: 700; color: #111827; width: 54%%; line-height: 1.25; }
                        .q-badges { display: table-cell; vertical-align: top; text-align: right; white-space: nowrap; width: 46%%; padding-top: 1px; }
                        .q-status, .q-ai { display: inline-flex; align-items: center; justify-content: center; font-weight: 700; font-size: 9px; border-radius: 3px; padding: 3px 8px; margin-left: 4px; line-height: 1; border: none; white-space: nowrap; text-align: center; min-width: 40px; }
                        .q-status.ok { color: #059669; background: #ecfdf5; }
                        .q-status.notok { color: #dc2626; background: #fef2f2; }
                        .q-sub { margin-top: 7px; color: #9aa7b8; font-size: 11px; line-height: 1.2; }
                        .q-ai { color: #6d28d9; background: #f5f3ff; }
                        .q-remark { margin-top: 7px; font-size: 12px; color: #1f2937; font-style: italic; font-weight: 600; line-height: 1.25; }
                        .q-auditor-remark { margin-top: 6px; font-size: 11px; color: #6b7280; line-height: 1.2; }
                        .q-hierarchy { margin-top: 3px; font-size: 10px; color: #5b7fb3; }
                        .evidence { border: 1px solid #e5e7eb; background: #f8fafc; border-radius: 6px; height: 142px; color: #94a3b8; font-size: 8px; display: table; width: 104%%; text-align: center; margin-left: auto; margin-right: 0; }
                        .evidence-inner { display: table-cell; vertical-align: middle; }
                        .evidence-icon { width: 22px; height: 14px; border: 2px solid #94a3b8; margin: 0 auto 6px; border-radius: 3px; position: relative; box-sizing: border-box; background: transparent; }
                        .evidence-icon:before { content: ''; position: absolute; top: -5px; left: 6px; width: 8px; height: 4px; border: 2px solid #94a3b8; border-bottom: none; border-radius: 2px 2px 0 0; box-sizing: border-box; }
                        .evidence-icon:after { content: ''; position: absolute; top: 3px; left: 7px; width: 4px; height: 4px; border: 2px solid #94a3b8; border-radius: 50%%; box-sizing: border-box; }
                    </style>
                </head>
                <body>
                    <div class='title-row'>
                        <div class='title-left'>
                            <div class='title'>%s</div>
                            <div class='subtitle'>%s</div>
                        </div>
                        <div class='title-right ref'>
                            <div>Audit Date: %s</div>
                            <div>Auditor: %s</div>
                            <div>Audit Ref: %s</div>
                        </div>
                    </div>

                    <div class='kpi-panel'>
                    <div class='kpi-wrap'>
                        <div class='kpi-left'>
                            <div class='score-tile'>
                                <div class='value'>%s</div>
                                <div class='divider'> </div>
                                <div class='base'>/100</div>
                            </div>
                        </div>
                        <div class='kpi-right'>
                            <div class='kpi-inline'>
                                <span class='kpi-chip ok'>%d<small>Passed</small></span>
                                <span class='kpi-chip notok'>%d<small>Failed</small></span>
                                <span class='kpi-chip total'>%d<small>Checkpoints</small></span>
                            </div>
                            <div class='band-row'>
                                <span class='band-label'>Band Classification:</span>
                                <span class='band-pill %s'>%s</span>
                            </div>
                        </div>
                    </div>
                    </div>

                    <div class='section-table'>
                        <table>
                            <thead>
                                <tr>
                                    <th style='width:57%%'>Category</th>
                                    <th style='width:13%%'>Score</th>
                                    <th style='width:10%%'>OK</th>
                                    <th style='width:10%%'>NOT OK</th>
                                    <th style='width:10%%'>Total</th>
                                </tr>
                            </thead>
                            <tbody>
                                %s
                            </tbody>
                        </table>
                    </div>

                    <div class='sections-wrap'>
                        %s
                    </div>
                </body>
                </html>
                """.formatted(
                reportTitle,
                locationLine,
                auditDate,
                escapeHtml(auditorName),
                auditRef,
                overallScoreNumeric,
                metrics.totalOk,
                metrics.totalNotOk,
                metrics.totalCheckpoints,
                bandClass,
                overallBand + " (" + overallScore + ")",
                categoriesHtml,
                sectionsHtml
        );
    }

    private String buildQuestionCardHtml(QuestionCard card, String questionNumber) {
        String hierarchy = Optional.ofNullable(card.childHierarchy).orElse("").trim();
        String hierarchyHtml = (!hierarchy.isBlank() && !"-".equals(hierarchy))
                ? "<div class='q-hierarchy'>Hierarchy: " + escapeHtml(hierarchy) + "</div>"
                : "";

        return new StringBuilder()
                .append("<div class='q-card'>")
                .append("<div class='q-layout'>")
                .append("<div class='q-left'>")
                .append("<div class='q-header'>")
                .append("<div class='q-title'>").append(escapeHtml(questionNumber)).append(" ").append(escapeHtml(card.question)).append("</div>")
                .append("<div class='q-badges'>")
                .append("<span class='q-status ").append("OK".equals(card.status) ? "ok" : "notok").append("'>").append(escapeHtml(card.status)).append("</span>")
                .append("<span class='q-ai'>").append(escapeHtml(card.aiText)).append("</span>")
                .append("</div>")
                .append("</div>")
                .append("<div class='q-sub'>").append(escapeHtml(card.subText)).append("</div>")
                .append(hierarchyHtml)
                .append("<div class='q-auditor-remark'>").append(escapeHtml(card.auditorRemark)).append("</div>")
                .append("</div>")
                .append("<div class='q-right'>")
                .append("<div class='evidence'>")
                .append("<div class='evidence-inner'>")
                .append("<div class='evidence-icon'></div>")
                .append("Evidence Photo")
                .append("</div>")
                .append("</div>")
                .append("</div>")
                .append("</div>")
                .append("</div>")
                .toString();
    }

    private String scoreCssClass(double score) {
        if (score >= 70) {
            return "good";
        }
        if (score >= 40) {
            return "mid";
        }
        return "low";
    }

    private String classifyBand(float score) {
        if (score >= 80) {
            return "Green Band";
        }
        if (score >= 60) {
            return "Yellow Band";
        }
        return "Red Band";
    }

    private String bandCssClass(float score) {
        if (score >= 80) {
            return "green";
        }
        if (score >= 60) {
            return "yellow";
        }
        return "red";
    }

    private String escapeHtml(String value) {
        String safe = Optional.ofNullable(value).orElse("");
        return safe
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private long parseLong(Object value) {
        if (value == null) {
            return 0L;
        }
        if (value instanceof Number num) {
            return num.longValue();
        }
        try {
            return Long.parseLong(value.toString());
        } catch (Exception e) {
            return 0L;
        }
    }

    private String sanitizePdfText(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("\n", " ")
                .replace("\r", " ")
                .replaceAll("[^\\x20-\\x7E]", "")
                .trim();
    }

    private static class ReportMetrics {
        long totalOk;
        long totalNotOk;
        long totalCheckpoints;
        double overallScore;
        List<CategoryMetrics> categories = new ArrayList<>();
        List<QuestionCard> questionCards = new ArrayList<>();
    }

    private static class CategoryMetrics {
        String name;
        long ok;
        long notOk;
        long total;
        long totalQuestions;
        double score;
    }

    private static class QuestionCard {
        String categoryName;
        String question;
        String subText;
        String status;
        String aiText;
        String remark;
        String auditorRemark;
        String childHierarchy;
    }

    private void extractContent(
            List<ChksHeaderDataDTO> chksHeaderDataDTOs,
            List<CellContent> parentData,
            List<List<CellContent>> contentRows,
            List<ChksHeaderDTO> chksHeaderResults,
            SXSSFWorkbook workbook,
            Map<Long, List<UserChecksheetMatrixAnswers>> mapUserChecksheetMatrixAnswers,
            AtomicInteger matrixCnt
    ) throws CustomException {
        for (ChksHeaderDataDTO chksHeaderDataDTO : chksHeaderDataDTOs) {
            List<CellContent> rowData = new ArrayList<>(parentData);
            CellContent cellContent = new CellContent();
            cellContent.setContent(chksHeaderDataDTO.getName());
            if(chksHeaderDataDTO.getChksHeaderDataFiles() != null)
                cellContent.setFilePaths(chksHeaderDataDTO.getChksHeaderDataFiles().stream().map(ChksHeaderDataFileDTO::getPath).toList());
            rowData.add(cellContent);

            List<ChksQuestionDTO> questions = chksHeaderDataDTO.getQuestions();
            if(!questions.isEmpty()){
                for (ChksQuestionDTO question : questions) {
                    List<CellContent> questionRow = new ArrayList<>(rowData);
                    cellContent = new CellContent();
                    cellContent.setContent(question.getName());
                    if(question.getChksQuestionDataFiles() != null)
                        cellContent.setFilePaths(question.getChksQuestionDataFiles().stream().map(ChksQuestionFileDTO::getPath).toList());
                    questionRow.add(cellContent);

                    List<ChksQuestionResultDTO> results = question.getChksQuestionResults();
                    for(ChksHeaderDTO chksHeaderResult : chksHeaderResults){
                        ChksQuestionResultDTO result = results.stream().filter(r -> Objects.equals(r.getChksHeaderId(),chksHeaderResult.getId())).findFirst().orElseThrow(() -> new CustomException("No Answer found!!",HttpStatus.UNPROCESSABLE_ENTITY));
                        cellContent = new CellContent();
                        if (result.getAnswerType().equals(ChksQuestionResultType.MATRIX)){
                            String sheetNm = (matrixCnt.get() + "_MATRIX_"+chksHeaderResult.getName()).replaceAll("[\\\\/\\[\\]\\*\\?]", "");
                            if(!mapUserChecksheetMatrixAnswers.isEmpty())
                                createMatrixSheet(workbook,sheetNm,result,mapUserChecksheetMatrixAnswers.get(result.getId()));
                            cellContent.setContent(sheetNm);
                            matrixCnt.incrementAndGet();
                        }else{
                            cellContent.setContent(getOption(result));
                            if (result.getAnswerType().equals(ChksQuestionResultType.FILE_UPLOAD)
                                    && result.getUserChecksheetAnswerFiles() != null
                                    && !result.getUserChecksheetAnswerFiles().isEmpty()) {
                                List<String> filePaths = result.getUserChecksheetAnswerFiles().stream()
                                        .map(UserChecksheetAnswerFileDTO::getThumbnailPath)
                                        .filter(path -> path != null && !path.isBlank())
                                        .toList();
                                cellContent.setFilePaths(filePaths);
                            }
                        }
                        questionRow.add(cellContent);
                    }
                    cellContent = new CellContent();
                    cellContent.setContent(question.getJudgement());
                    questionRow.add(cellContent);
                    contentRows.add(questionRow);
                }
            }
            List<ChksHeaderDataDTO> children = chksHeaderDataDTO.getChildren();
            if (children != null) {
                extractContent(children, rowData, contentRows, chksHeaderResults, workbook, mapUserChecksheetMatrixAnswers,matrixCnt);
            }
        }
    }

    private void extractChksVersionData(Checksheet chks, List<CellContent> parentData,
                                        List<List<CellContent>> contentRows){
        List<CellContent> rowData = new ArrayList<>(parentData);
        CellContent cellContent = new CellContent();
        cellContent.setContent((chks.getVersion() + 1L) + "");
        rowData.add(cellContent);
        cellContent = new CellContent();
        cellContent.setContent((chks.getVersion() == 0L ? "N" : chks.getVersion()) + "");
        rowData.add(cellContent);
        cellContent = new CellContent();
        cellContent.setContent(DateHelper.getDateToStringFormat(chks.getImplementationDate(),"dd/MM/yyyy"));
        rowData.add(cellContent);
        cellContent = new CellContent();
        cellContent.setContent(chks.getVersionRemark());
        rowData.add(cellContent);
        cellContent = new CellContent();
        User user = chks.getPreparerUser();
        cellContent.setContent(user.getFirstName() + " " +user.getLastName() + "("+user.getUsername()+")" );
        rowData.add(cellContent);
        //Get validators
        List<User> users = userRepository.findByIdIn(chks.getValidatorUserIds());
        cellContent = new CellContent();
        StringBuilder userNames = new StringBuilder();
        for(User u: users){
            userNames.append(u.getFirstName()).append(" ").append(u.getLastName()).append(" (").append(u.getUsername()).append(")").append("\n");
        }
        cellContent.setContent(userNames.toString());
        rowData.add(cellContent);

        //Get Approves
        users = userRepository.findByIdIn(chks.getApproverUserIds());
        cellContent = new CellContent();
        userNames = new StringBuilder();
        for(User u: users){
            userNames.append(u.getFirstName()).append(" ").append(u.getLastName()).append(" (").append(u.getUsername()).append(")").append("\n");
        }
        cellContent.setContent(userNames.toString());
        rowData.add(cellContent);

        contentRows.add(rowData);
        Checksheet parentChks = chks.getChecksheet();
        if(parentChks != null){
            extractChksVersionData(parentChks,rowData,contentRows);
        }
    }
    private static String getOption(ChksQuestionResultDTO result) {
        String res = "";
        if(result.getAnswerType().equals(ChksQuestionResultType.SUBJECTIVE)){
            res = result.getUserAnswer();
        } else if (result.getAnswerType().equals(ChksQuestionResultType.SUBJECTIVE_CONDITION)
                || result.getAnswerType().equals(ChksQuestionResultType.SELECTIVE)) {
            Long selectedOptionId = result.getSelectedChksQuestionResultId();
            if (result.getChksQuestionResultOptions() == null || selectedOptionId == null) {
                return res;
            }
            ChksQuestionResultOptionDTO selectedOpt = result.getChksQuestionResultOptions()
                    .stream()
                    .filter(option -> Objects.equals(option.getId(), selectedOptionId))
                    .findFirst()
                    .orElse(null);
            if(selectedOpt != null){
                res = selectedOpt.getOption();
            }
        } else if (result.getAnswerType().equals(ChksQuestionResultType.OBJECTIVE)) {
            if(result.getUnit() != null && !result.getUnit().isBlank()){
                res = "Unit : " + result.getUnit() + "\n";
            }
            res += result.getUserAnswer();
        } else if (result.getAnswerType().equals(ChksQuestionResultType.NA)) {
            res = "NA";
        } else if (result.getAnswerType().equals(ChksQuestionResultType.FILE_UPLOAD)) {
            int fileCount = result.getUserChecksheetAnswerFiles() == null ? 0 : result.getUserChecksheetAnswerFiles().size();
            res = fileCount > 0 ? "Image(s): " + fileCount : "";
        }
        return res;
    }

    private void createMatrixSheet(
            SXSSFWorkbook workbook,
            String sheetName,
            ChksQuestionResultDTO chksQuestionResultDTO,
            List<UserChecksheetMatrixAnswers> userChecksheetMatrixAnswers
    ) throws CustomException {
        Sheet sheet = workbook.createSheet(sheetName); //--> Must not contain \,/,[,],*,?
        CellStyle headerStyle = setCellStyle(workbook,true);
        // Set text alignment to center (both horizontally and vertically)
        headerStyle.setAlignment(HorizontalAlignment.CENTER);
        headerStyle.setVerticalAlignment(VerticalAlignment.CENTER);

        CellStyle hdrDataStyle = setCellStyle(workbook,false);

        int rowCount = 0, columnCnt = 0;
        Row row = sheet.createRow(rowCount);
        //Set Matrix name
        createCell(row, columnCnt, chksQuestionResultDTO.getMatrixName(), headerStyle);
        sheet.addMergedRegion(new CellRangeAddress(0, 1, 0, 1));

        //Set Matrix Column Name
        createCell(row, 2, chksQuestionResultDTO.getChksMatrixColName() , headerStyle);
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 2,2+chksQuestionResultDTO.getChksMatrixColNm().size()-1));

        //Set Matrix Column Names
        row = sheet.createRow(1);
        columnCnt = 2;
        for (String colNm : chksQuestionResultDTO.getChksMatrixColNm()) {
            createCell(row, columnCnt++, colNm, headerStyle);
        }

        //Set Matrix Data
        rowCount = 2;
        Row firstDataRow = null;
        List<ChksQuestionResultMatrixDTO>  chksQuestionResultMatrices= chksQuestionResultDTO.getChksQuestionResultMatrices();
        for (String rowNm : chksQuestionResultDTO.getChksMatrixRowNm()) {
            row = sheet.createRow(rowCount++);
            if(firstDataRow == null){
                firstDataRow = row;
            }
            columnCnt = 1;
            createCell(row, columnCnt++, rowNm, headerStyle); // --> Set Matrix Row Names
            for (String colNm : chksQuestionResultDTO.getChksMatrixColNm()) {
                ChksQuestionResultMatrixDTO chksQueResMatrix = chksQuestionResultMatrices.stream().filter(corm -> corm.getChksMatrixRowHdr().equals(rowNm) && corm.getChksMatrixColHdr().equals(colNm)).findFirst().orElseThrow(() -> new CustomException("No Matrix data found",HttpStatus.UNPROCESSABLE_ENTITY));
                createCell(row, columnCnt++, chksQueResMatrix.getData(), hdrDataStyle);
            }
        }

        //Set Matrix Row Name
        assert firstDataRow != null;
        createCell(firstDataRow, 0, chksQuestionResultDTO.getChksMatrixRowName(), headerStyle);
        sheet.addMergedRegion(new CellRangeAddress(2, 2+chksQuestionResultDTO.getChksMatrixRowNm().size()-1, 0,0));

        rowCount++;columnCnt = 0;
        //Set Answers data
        row = sheet.createRow(rowCount++);
        createCell(row, columnCnt++, "Number of Result", headerStyle);
        createCell(row, columnCnt, chksQuestionResultDTO.getNoOfResults(), headerStyle);
        row = sheet.createRow(rowCount++);
        columnCnt = 0;
        createCell(row, columnCnt++, "#", headerStyle);
        createCell(row, columnCnt++, chksQuestionResultDTO.getChksMatrixRowName(), headerStyle);
        createCell(row, columnCnt++, chksQuestionResultDTO.getChksMatrixColName(), headerStyle);
        createCell(row, columnCnt++, "Result", headerStyle);
        createCell(row, columnCnt++, "M/C Result", headerStyle);
        createCell(row, columnCnt, "Judgement", headerStyle);
        ChksQuestionResultMatrix chksQuestionResultMatrix;
        for(UserChecksheetMatrixAnswers userChecksheetMatrixAnswer: userChecksheetMatrixAnswers){
            row = sheet.createRow(rowCount++);
            columnCnt = 0;
            chksQuestionResultMatrix = userChecksheetMatrixAnswer.getChksQuestionResultMatrix();
            createCell(row, columnCnt++, userChecksheetMatrixAnswer.getOrderNo(), hdrDataStyle);
            createCell(row, columnCnt++, chksQuestionResultMatrix.getChksMatrixRowHdr(), hdrDataStyle);
            createCell(row, columnCnt++, chksQuestionResultMatrix.getChksMatrixColHdr(), hdrDataStyle);
            createCell(row, columnCnt++, chksQuestionResultMatrix.getData(), hdrDataStyle);
            createCell(row, columnCnt++, userChecksheetMatrixAnswer.getMcResult(), hdrDataStyle);
            createCell(row, columnCnt, userChecksheetMatrixAnswer.getJudgement() == 1 ? "OK":"NOT OK", hdrDataStyle);
        }
    }

    private CellStyle setCellStyle(SXSSFWorkbook workbook, boolean isHdr) {
        CellStyle headerStyle = workbook.createCellStyle();
        XSSFFont font = (XSSFFont) workbook.createFont();
        font.setFontHeight(11);
        font.setBold(isHdr);
        headerStyle.setFont(font);
        headerStyle.setVerticalAlignment(VerticalAlignment.TOP);
        // Set borders - thin black borders on all sides
        headerStyle.setBorderTop(BorderStyle.THIN);
        headerStyle.setBorderBottom(BorderStyle.THIN);
        headerStyle.setBorderLeft(BorderStyle.THIN);
        headerStyle.setBorderRight(BorderStyle.THIN);

        headerStyle.setTopBorderColor(IndexedColors.BLACK.getIndex());
        headerStyle.setBottomBorderColor(IndexedColors.BLACK.getIndex());
        headerStyle.setLeftBorderColor(IndexedColors.BLACK.getIndex());
        headerStyle.setRightBorderColor(IndexedColors.BLACK.getIndex());
        return headerStyle;
    }
    private void createCell(Row row, int columnCount, String valueOfCell, CellStyle hdrDataStyle, Hyperlink hyperlink) {
        Cell cell = row.createCell(columnCount);
        cell.setCellValue(valueOfCell);
        hdrDataStyle.setWrapText(true);  // Enable text wrapping
        cell.setCellStyle(hdrDataStyle);
        cell.setHyperlink(hyperlink);
    }
    private void createCell(Row row, int columnCount, Object valueOfCell, CellStyle style) {
//        sheet1.autoSizeColumn(columnCount);
        Cell cell = row.createCell(columnCount);

        if (valueOfCell instanceof Integer) {
            cell.setCellValue((Integer) valueOfCell);
        } else if (valueOfCell instanceof Long) {
            cell.setCellValue((Long) valueOfCell);
        } else if (valueOfCell instanceof Double) {
            cell.setCellValue((Double) valueOfCell);
        } else if (valueOfCell instanceof String) {
            cell.setCellValue((String) valueOfCell);
        } else if (valueOfCell instanceof Date) {
            DateFormat df = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss");
            cell.setCellValue(df.format(valueOfCell));
        } else if(valueOfCell instanceof Boolean) {
            cell.setCellValue((Boolean) valueOfCell);
        } else if(Objects.equals(valueOfCell, null)) {
            cell.setCellValue("");
        }
        style.setWrapText(true);  // Enable text wrapping
        cell.setCellStyle(style);
    }
    private void insertImageToCell(SXSSFWorkbook workbook, Sheet sheet, String imagePath, int rowNum, int colNum) throws CustomException {
        try {
            if (imagePath == null || imagePath.isEmpty()) return;

            // Read image into byte array
            byte[] imageBytes = FileStorageUtil.getFileAsByteArray(imagePath);

            // Determine image type
            int pictureType = Workbook.PICTURE_TYPE_JPEG; // Default to JPEG
            if (imagePath.toLowerCase().endsWith(".png")) {
                pictureType = Workbook.PICTURE_TYPE_PNG;
            }

            // Add picture to workbook
            int pictureIdx = workbook.addPicture(imageBytes, pictureType);
            CreationHelper helper = workbook.getCreationHelper();

            // Create drawing and anchor
            Drawing<?> drawing = sheet.createDrawingPatriarch();
//            ClientAnchor anchor = helper.createClientAnchor();

            // Set anchor properties (top-left corner of the image's cell)
//            anchor.setCol1(colNum);
//            anchor.setRow1(rowNum);
//            anchor.setCol2(colNum + 1);
//            anchor.setRow2(rowNum + 1);

            // Set vertical anchor points to occupy the bottom 75% of the cell
//            anchor.setDy1((int) (32767 * 0.25)); // Start at 25%
//            anchor.setDy2(32767);              // End at 100%

            // Read image dimensions
            BufferedImage bufferedImage = ImageIO.read(new ByteArrayInputStream(imageBytes));
            int imgHeightPx = bufferedImage.getHeight();
            double pointsPerPixel = 0.75;
            double imgHeightInPoints = imgHeightPx * pointsPerPixel;

            // Set the row height to accommodate the image (at least the 75% portion)
            Row row = sheet.getRow(rowNum);
            if (row == null) row = sheet.createRow(rowNum);
            row.setHeightInPoints((float) Math.max(row.getHeightInPoints(), imgHeightInPoints));

            // Calculate column width (approximate)
            int imgWidthPx = bufferedImage.getWidth();
            int columnWidthUnits = (int) (imgWidthPx * 35); // 1 px = 35 unit
            sheet.setColumnWidth(colNum, Math.min(columnWidthUnits, 255 * 256));

            int cellHeight = (int) (imgHeightInPoints+40);
            int dy1 = (int)((cellHeight - imgHeightInPoints)*9525);
            int cellWidthPx = sheet.getColumnWidth(colNum) * 7 / 256; // Approximate conversion --> 1 unit = 1/256th of the width of a character '0' in the default font
            int horizontalPadding = (cellWidthPx - imgWidthPx) / 2;
            System.out.println(horizontalPadding);
            int dx1 = 95250; // Convert pixels to EMUs 10 px

            XSSFClientAnchor anchor = new XSSFClientAnchor(
                dx1, dy1,           // Offset from top-left (start lower)
                dx1 + ((imgWidthPx - 10) * 9525), // Optional: width in EMUs
                (int) (dy1 + (imgHeightPx * 9525)),  // 9525 is the number of EMUs (English Metric Units) per pixel used by Excel.
                colNum, rowNum, // Start cell
                colNum, rowNum  // End cell (same)
            );
            anchor.setAnchorType(ClientAnchor.AnchorType.MOVE_AND_RESIZE);

            // Create picture
            Picture picture = drawing.createPicture(anchor, pictureIdx);
        } catch (Exception e) {
            throw new CustomException("Something weng wrong..." + e.getMessage(),HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private String getFileHash(MultipartFile file) throws CustomException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(file.getBytes());
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException | IOException e) {
            throw new CustomException("Failed to generate file hash", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private void dwnlExlWithImgTxtInSingleCell(HttpServletResponse response) throws IOException {
        SXSSFWorkbook workbook = new SXSSFWorkbook(1000);
        SXSSFSheet sheet = workbook.createSheet(WorkbookUtil.createSafeSheetName("Example"));

        // Create row and cell
        Row row = sheet.createRow(0);
        Cell cell = row.createCell(0);
        cell.setCellValue("Text at Top Text at Top Text at Top Text at TopText at TopText at TopText at TopText at Top Text at Top Text at Top Text at Top Text at TopText at TopText at TopText at TopText at Top");

        // Set style (optional)
        CellStyle style = workbook.createCellStyle();
        style.setVerticalAlignment(VerticalAlignment.TOP);
        style.setWrapText(true);
        cell.setCellStyle(style);

        // Set row height for 25% text, 75% image
        float textHeight = 15f;
        float totalHeight = textHeight / 0.25f; // 25% text

        // Read image from file
        String imagePath = "189_image_2.png"; //  Replace with actual image path
        byte[] bytes = FileStorageUtil.getFileAsByteArray(imagePath);
        int pictureIdx = workbook.addPicture(bytes, Workbook.PICTURE_TYPE_PNG);

        // Read image dimensions
        BufferedImage bufferedImage = ImageIO.read(new ByteArrayInputStream(bytes));
        int imgHeightPx = bufferedImage.getHeight();
        int imgHeightPoints = (int) (imgHeightPx * 0.75);
        int cellHeight = imgHeightPoints+40;
        int dy1 = (int)((cellHeight - imgHeightPoints)*9525);
        int dx1 = 0;
        System.out.println(imgHeightPoints);
        System.out.println(row.getHeightInPoints());

        // Set column width
        sheet.setColumnWidth(0, 25 * 256); // 25 characters wide

        XSSFClientAnchor anchor = new XSSFClientAnchor(
            dx1, dy1,           // Offset from top-left (start lower)
            dx1 + (cellHeight * 9525), // Optional: width in EMUs
            dy1 + (imgHeightPoints * 9525),  // Optional: height in EMUs
            0, 0, // Start cell
            0, 0  // End cell (same)
        );
        anchor.setAnchorType(ClientAnchor.AnchorType.MOVE_AND_RESIZE);



        // Create anchor
//        CreationHelper helper = workbook.getCreationHelper();
        Drawing<?> drawing = sheet.createDrawingPatriarch();
//        ClientAnchor anchor = helper.createClientAnchor();
//        anchor.setCol1(0);
//        anchor.setRow1(0);
//        anchor.setCol2(1);
//        anchor.setRow2(1);
//        anchor.setDy1((int) (32767 * 0.25)); // 25% down
//        anchor.setDy2(32767);                // 100%
//        anchor.setDx1((int)(1023 * 0.125));  // 12.5%
//        anchor.setDx2((int)(1023 * 0.875));  // 87.5%

        // Add picture to sheet
        drawing.createPicture(anchor, pictureIdx);

        // Write to file
        ServletOutputStream outputStream = response.getOutputStream();
        workbook.write(outputStream);
        workbook.close();
        outputStream.close();


    }

    private void mergeAnswersWithContent(List<ChksHeaderDataDTO> content,
                                       Map<Long, UserChecksheetAnswerDTO> regularAnswers,
                                       Map<Long, com.checkSheet.entity.AiAssessment> aiByResult,
                                       UserChecksheetDTO userChecksheetDTO) {
        for (ChksHeaderDataDTO headerData : content) {
            if (headerData.getQuestions() != null) {
                for (ChksQuestionDTO question : headerData.getQuestions()) {
                    if (question.getChksQuestionResults() != null) {
                        for (ChksQuestionResultDTO result : question.getChksQuestionResults()) {
                            // Attach AI assessment (if any) — runs for every result
                            // type, so do this before the answerType branch.
                            com.checkSheet.entity.AiAssessment ai = aiByResult.get(result.getId());
                            if (ai != null) {
                                AiAssessmentDTO aiDto = new AiAssessmentDTO();
                                aiDto.setId(ai.getId());
                                aiDto.setInspectionId(userChecksheetDTO.getId());
                                aiDto.setChksQuestionResultId(result.getId());
                                aiDto.setSuggestedJudgement(ai.getSuggestedJudgement());
                                aiDto.setExplanation(ai.getExplanation());
                                aiDto.setConfidence(ai.getConfidence());
                                aiDto.setAssessedAt(ai.getAssessedAt());
                                result.setAiAssessment(aiDto);
                            }
                            if (result.getAnswerType() == ChksQuestionResultType.MATRIX) {
//                                mergeMatrixAnswers(result, matrixAnswers);
                                List<UserChecksheetMatrixAnswerDTO> userChecksheetMatrixAnswers = userChecksheetAnswerDAO.getUserChecksheetMatrixAnswers(
                                    userChecksheetDTO.getId(), question.getId(), result.getId());
                                if (!userChecksheetMatrixAnswers.isEmpty()) {
                                    result.setMatrixAnswers(userChecksheetMatrixAnswers);
                                }
                            } else if (result.getAnswerType() == ChksQuestionResultType.FILE_UPLOAD) {
                                UserChecksheetAnswerDTO answer = regularAnswers.get(result.getId());
                                if (answer != null && answer.getId() != null) {
                                    List<UserChecksheetAnswerFileDTO> userChecksheetAnswerFiles = userChecksheetAnswerFileRepository
                                            .findByUserChecksheetAnswer_IdAndDeletedAtIsNullOrderByIdAsc(answer.getId())
                                            .stream()
                                            .map(userChecksheetAnswerFile -> getUserChecksheetAnswerFileDTO(
                                                    userChecksheetAnswerFile,
                                                    userChecksheetAnswerFile.getUserChecksheetAnswer()
                                            ))
                                            .toList();
                                    result.setUserChecksheetAnswerFiles(userChecksheetAnswerFiles);
                                }
                            } else {
                                UserChecksheetAnswerDTO answer = regularAnswers.get(result.getId());
                                if (answer != null) {
                                    result.setIsNotApplicable(answer.getIsNotApplicable());
                                    result.setUserAnswer(answer.getAnswer());
                                    result.setSelectedChksQuestionResultId(answer.getChksQuestionRsltOptionId());
                                    // Photo evidence is allowed on any answer type (the seed
                                    // attaches checkpoint photos to SUBJECTIVE_CONDITION /
                                    // OBJECTIVE answers, not just FILE_UPLOAD ones). Surface
                                    // them on the result so the audit-report renders the
                                    // thumbnails next to the question.
                                    if (answer.getId() != null) {
                                        List<UserChecksheetAnswerFileDTO> files = userChecksheetAnswerFileRepository
                                                .findByUserChecksheetAnswer_IdAndDeletedAtIsNullOrderByIdAsc(answer.getId())
                                                .stream()
                                                .map(f -> getUserChecksheetAnswerFileDTO(f, f.getUserChecksheetAnswer()))
                                                .toList();
                                        if (!files.isEmpty()) {
                                            result.setUserChecksheetAnswerFiles(files);
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Optional<UsrChksheetAnsJudgement> usrChksheetAnsJudgement = usrChksheetAnsJudgementRepository.findByChksQuestionIdAndInspection_Id(question.getId(), userChecksheetDTO.getId());
                    if (usrChksheetAnsJudgement.isEmpty()) {
                        // Derive judgement from the answer rows when no separate
                        // judgement record exists (the seed and the mobile app
                        // currently only write to user_checksheet_answers, not
                        // usr_chksheet_ans_judgements).
                        boolean anyNotOk = false;
                        boolean anyOk = false;
                        if (question.getChksQuestionResults() != null) {
                            for (ChksQuestionResultDTO r : question.getChksQuestionResults()) {
                                UserChecksheetAnswerDTO ans = regularAnswers.get(r.getId());
                                if (ans != null && ans.getJudgement() != null) {
                                    if (ans.getJudgement() == 2) anyNotOk = true;
                                    else if (ans.getJudgement() == 1) anyOk = true;
                                }
                            }
                        }
                        if (anyNotOk) question.setJudgement("NOT OK");
                        else if (anyOk) question.setJudgement("OK");
                    }
                    if(usrChksheetAnsJudgement.isPresent()) {
                        question.setRemarks(usrChksheetAnsJudgement.get().getRemarks());
                        question.setJudgement(usrChksheetAnsJudgement.get().getJudgement());
                        List<UsrChksheetAnsJudgementFile> usrChksheetAnsJudgementFiles = usrChksheetAnsJudgementFileRepository.findByInspection_IdAndUsrChksheetAnsJudgement_Id(userChecksheetDTO.getId(), usrChksheetAnsJudgement.get().getId());
                        if(!Objects.isNull(usrChksheetAnsJudgementFiles) && !usrChksheetAnsJudgementFiles.isEmpty()) {
                            List<ChksHeaderDataFileDTO> chksHeaderDataFileDTO = new ArrayList<>();
                            for(UsrChksheetAnsJudgementFile usrChksheetAnsJudgementFile: usrChksheetAnsJudgementFiles) {
                                ChksHeaderDataFileDTO fileDTO = new ChksHeaderDataFileDTO();
                                fileDTO.setId(usrChksheetAnsJudgementFile.getId());
                                fileDTO.setPath(usrChksheetAnsJudgementFile.getPath());
                                if (!Objects.isNull(usrChksheetAnsJudgementFile.getPath())) {
//                                    fileDTO.setUrl(awss3Service.getDocs(usrChksheetAnsJudgementFile.getPath()).toString());
                                    fileDTO.setUrl(FileStorageUtil.getFileURL(usrChksheetAnsJudgementFile.getPath()));
                                }
                                chksHeaderDataFileDTO.add(fileDTO);
                            }
                            question.setJudgementFiles(chksHeaderDataFileDTO);
                        }
                    }
                }
            }
            if (headerData.getChildren() != null) {
                mergeAnswersWithContent(headerData.getChildren(), regularAnswers, aiByResult, userChecksheetDTO);
            }
        }
    }

    private void mergeMatrixAnswers(ChksQuestionResultDTO result, 
                                  Map<String, UserChecksheetMatrixAnswerDTO> matrixAnswers) {
        if (result.getChksQuestionResultMatrices() != null) {
            List<UserChecksheetMatrixAnswerDTO> matrixAnswersList = new ArrayList<>();
            
            for (ChksQuestionResultMatrixDTO matrix : result.getChksQuestionResultMatrices()) {
                String key = result.getId() + "_" + matrix.getId() + "_" + matrix.getOrderNo();
                UserChecksheetMatrixAnswerDTO answer = matrixAnswers.get(key);
                
                if (answer != null) {
                    UserChecksheetMatrixAnswerDTO matrixAnswer = new UserChecksheetMatrixAnswerDTO();
                    matrixAnswer.setId(answer.getId());
                    matrixAnswer.setChksQuestionResultId(result.getId());
                    matrixAnswer.setChksQuestionResultMatrixId(matrix.getId());
                    matrixAnswer.setRowId(matrix.getRowId());
                    matrixAnswer.setColumnId(matrix.getColumnId());
                    matrixAnswer.setResult(answer.getResult());
                    matrixAnswer.setMcResult(answer.getMcResult());
                    matrixAnswer.setOrderNo(answer.getOrderNo());
                    matrixAnswer.setJudgement(answer.getJudgement());
                    
                    matrixAnswersList.add(matrixAnswer);

                    matrix.setUserAnswer(answer.getAnswer());
                    matrix.setJudgement(answer.getJudgement());
                }
            }
            
            if (!matrixAnswersList.isEmpty()) {
                result.setMatrixAnswers(matrixAnswersList);
            }
        }
    }

    /** Shared visibility gate for any read of a single user_checksheet
     *  (audit instance) by id. Throws 403 unless the caller is one of:
     *   - holder of the AUDIT_VIEW permission (Dept Head, Section Head, Super Admin)
     *   - the operator who filled this UC
     *   - the Dealer Principal of the dealership this UC was performed at
     *
     *  Without this check, an authenticated user can read any UC by guessing
     *  the id. Callers of the audit-report API stack (getUserChecksheetWithAnswers,
     *  the two download endpoints, userChecksheetMeta, improvement-overlay)
     *  must run this before returning content. */
    private void assertUserChecksheetVisible(Inspection uc) throws CustomException {
        if (uc == null) {
            throw new CustomException("user_checksheet not found", HttpStatus.NOT_FOUND);
        }
        User caller = utilityService.getCurrentLoggedInUser()
            .orElseThrow(() -> new CustomException("Not authenticated", HttpStatus.UNAUTHORIZED));
        Long callerId = caller.getId();
        // (a) senior with AUDIT_VIEW
        if (permissionService.hasPermission(callerId, "AUDIT_VIEW")) {
            return;
        }
        // (b) operator who owns this UC
        if (uc.getOperatorUser() != null
                && Objects.equals(uc.getOperatorUser().getId(), callerId)) {
            return;
        }
        // (c) Dealer Principal of the dealership this UC was performed at
        if (uc.getDealerPrincipalUser() != null
                && Objects.equals(uc.getDealerPrincipalUser().getId(), callerId)) {
            return;
        }
        throw new CustomException("You do not have access to this user_checksheet", HttpStatus.FORBIDDEN);
    }
}
