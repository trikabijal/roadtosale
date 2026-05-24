package com.checkSheet.service;

import com.checkSheet.DTO.InterventionCreateDTO;
import com.checkSheet.DTO.InterventionDTO;
import com.checkSheet.DTO.InterventionUpdateDTO;
import com.checkSheet.DTO.QuestionConflictDTO;
import com.checkSheet.DTO.response.ResponseDTO;
import com.checkSheet.constant.InterventionPermissions;
import com.checkSheet.entity.Audit;
import com.checkSheet.entity.Inspection;
import com.checkSheet.entity.AuditeeLocation;
import com.checkSheet.entity.ChksQuestion;
import com.checkSheet.entity.Intervention;
import com.checkSheet.entity.InterventionAssignmentTarget;
import com.checkSheet.entity.InterventionQuestion;
import com.checkSheet.entity.User;
import com.checkSheet.entity.Inspection;
import com.checkSheet.exception.CustomException;
import com.checkSheet.repository.AuditAssignmentRepository;
import com.checkSheet.repository.AuditRepository;
import com.checkSheet.repository.ChksQuestionRepository;
import com.checkSheet.repository.InterventionAssignmentRepository;
import com.checkSheet.repository.InterventionAssignmentTargetRepository;
import com.checkSheet.repository.InterventionQuestionRepository;
import com.checkSheet.repository.InterventionRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
public class InterventionServiceImpl implements InterventionService {

    private static final Set<String> VALID_PRIORITIES = Set.of("P1", "P2", "P3");
    private static final Set<String> VALID_TARGETING_MODES = Set.of("ALL", "BY_REGION", "MANUAL");

    private static final String STATUS_DRAFT  = "DRAFT";
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_CLOSED = "CLOSED";

    @Autowired private InterventionRepository interventionRepository;
    @Autowired private InterventionQuestionRepository interventionQuestionRepository;
    @Autowired private InterventionAssignmentTargetRepository targetRepository;
    @Autowired private InterventionAssignmentRepository interventionAssignmentRepository;
    @Autowired private AuditRepository auditRepository;
    @Autowired private AuditAssignmentRepository auditAssignmentRepository;
    @Autowired private ChksQuestionRepository chksQuestionRepository;
    @Autowired private com.checkSheet.repository.UserChecksheetRepository userChecksheetRepository;
    @Autowired private InterventionAssignmentService interventionAssignmentService;
    @Autowired private UtilityService utilityService;
    @Autowired private PermissionService permissionService;

    @PersistenceContext private EntityManager em;

    // ── auth helper ──────────────────────────────────────────────────────────

    private User requireManage() throws CustomException {
        User u = utilityService.getCurrentLoggedInUser()
            .orElseThrow(() -> new CustomException("Not authenticated", HttpStatus.UNAUTHORIZED));
        if (!permissionService.hasPermission(u.getId(), InterventionPermissions.INTERVENTION_MANAGE)) {
            throw new CustomException("Missing permission INTERVENTION_MANAGE", HttpStatus.FORBIDDEN);
        }
        return u;
    }

    // ── CRUD ─────────────────────────────────────────────────────────────────

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseDTO<InterventionDTO> createDraft(InterventionCreateDTO dto) throws CustomException {
        if (dto == null) throw new CustomException("Body required", HttpStatus.UNPROCESSABLE_ENTITY);
        User loginUser = requireManage();
        validateName(dto.getName());
        validatePriority(dto.getPriority());
        validateTargetingMode(dto.getTargetingMode());
        if (dto.getAuditId() == null) {
            throw new CustomException("auditId required", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        Audit audit = auditRepository.findById(dto.getAuditId())
            .orElseThrow(() -> new CustomException("Invalid auditId", HttpStatus.UNPROCESSABLE_ENTITY));
        if (audit.getDeletedAt() != null || "CLOSED".equals(audit.getStatus())) {
            throw new CustomException("Audit is closed/deleted", HttpStatus.UNPROCESSABLE_ENTITY);
        }

        Intervention i = new Intervention();
        i.setAudit(audit);
        i.setName(dto.getName().trim());
        i.setTheme(dto.getTheme());
        i.setPriority(dto.getPriority());
        i.setTargetDate(dto.getTargetDate());
        i.setStatus(STATUS_DRAFT);
        i.setCreatedBy(loginUser);
        interventionRepository.save(i);

        if (dto.getQuestionIds() != null && !dto.getQuestionIds().isEmpty()) {
            persistQuestions(i, dto.getQuestionIds(), loginUser);
        }
        return new ResponseDTO<>("Draft created", toDTO(i, true));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseDTO<InterventionDTO> update(Long id, InterventionUpdateDTO dto) throws CustomException {
        if (dto == null) throw new CustomException("Body required", HttpStatus.UNPROCESSABLE_ENTITY);
        User loginUser = requireManage();
        Intervention i = mustFindDraft(id);
        if (dto.getName() != null && !dto.getName().isBlank()) i.setName(dto.getName().trim());
        if (dto.getTheme() != null) i.setTheme(dto.getTheme());
        if (dto.getPriority() != null) {
            validatePriority(dto.getPriority());
            i.setPriority(dto.getPriority());
        }
        if (dto.getTargetDate() != null) i.setTargetDate(dto.getTargetDate());
        i.setUpdatedBy(loginUser);
        interventionRepository.save(i);
        return new ResponseDTO<>("Updated", toDTO(i, true));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseDTO<InterventionDTO> setQuestions(Long id, List<Long> questionIds) throws CustomException {
        User loginUser = requireManage();
        Intervention i = mustFindDraft(id);
        // wipe & rewrite for a draft — simplest mutation contract.
        List<InterventionQuestion> existing = interventionQuestionRepository.findByInterventionIdAndDeletedAtIsNull(id);
        Date now = new Date();
        for (InterventionQuestion q : existing) {
            q.setDeletedAt(now);
            q.setDeletedBy(loginUser);
        }
        interventionQuestionRepository.saveAll(existing);
        if (questionIds != null && !questionIds.isEmpty()) {
            persistQuestions(i, questionIds, loginUser);
        }
        return new ResponseDTO<>("Questions set", toDTO(i, true));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseDTO<InterventionDTO> addQuestions(Long id, List<Long> questionIds) throws CustomException {
        User loginUser = requireManage();
        Intervention i = interventionRepository.findByIdAndDeletedAtIsNull(id)
            .orElseThrow(() -> new CustomException("Intervention not found", HttpStatus.NOT_FOUND));
        if (STATUS_CLOSED.equals(i.getStatus())) {
            throw new CustomException("Cannot edit closed intervention", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        if (questionIds != null && !questionIds.isEmpty()) {
            persistQuestions(i, questionIds, loginUser);
        }
        return new ResponseDTO<>("Questions added", toDTO(i, true));
    }

    // ── activation ───────────────────────────────────────────────────────────

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseDTO<InterventionDTO> activate(Long id) throws CustomException {
        User loginUser = requireManage();
        Intervention i = mustFindDraft(id);
        List<Long> qIds = currentQuestionIds(id);
        if (qIds.isEmpty()) {
            throw new CustomException("Cannot activate without any questions", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        // PRD §3.1.5 conflict check — controller is expected to call
        // previewActivationConflicts first and surface 409 to the caller;
        // we re-check here as a defensive guard.
        List<QuestionConflictDTO> conflicts = computeConflicts(i.getAudit().getId(), qIds, id);
        if (!conflicts.isEmpty()) {
            throw new CustomException(
                "Activation conflicts with another active intervention — call previewActivationConflicts to see details",
                HttpStatus.CONFLICT);
        }

        // Materialise targets — single source of truth, no runtime branching.
        InterventionCreateDTO targeting = pendingTargetingFromMemory(id);
        List<Inspection> targets = resolveTargetAssignments(i.getAudit().getId(), targeting);
        Date now = new Date();
        for (Inspection aa : targets) {
            // idempotency: skip if already materialised (shouldn't happen on
            // first activation; defensive for re-runs after partial failure).
            Optional<InterventionAssignmentTarget> existing =
                targetRepository.findByInterventionIdAndInspectionIdAndDeletedAtIsNull(id, aa.getId());
            if (existing.isPresent()) continue;
            InterventionAssignmentTarget t = new InterventionAssignmentTarget();
            t.setIntervention(i);
            t.setInspection(aa);
            t.setCreatedBy(loginUser);
            t.setCreatedAt(now);
            targetRepository.save(t);
        }
        i.setStatus(STATUS_ACTIVE);
        i.setActivatedAt(now);
        i.setUpdatedBy(loginUser);
        interventionRepository.save(i);

        // Back-fill — see activateWithTargeting for rationale. Same sweep so
        // both code paths behave consistently.
        backfillPlansForExistingApprovals(targets);

        return new ResponseDTO<>("Activated", toDTO(i, true));
    }

    @Override
    public List<QuestionConflictDTO> previewActivationConflicts(Long id) throws CustomException {
        requireManage();
        Intervention i = interventionRepository.findByIdAndDeletedAtIsNull(id)
            .orElseThrow(() -> new CustomException("Intervention not found", HttpStatus.NOT_FOUND));
        return computeConflicts(i.getAudit().getId(), currentQuestionIds(id), id);
    }

    /** Returns one row per (chksQuestionId × claiming intervention) for every
     *  question that is already covered by an ACTIVE or DRAFT intervention
     *  belonging to {@code auditId}. The "New Intervention" form uses this to
     *  pre-filter the question picker so the user only sees questions that are
     *  still up for grabs, and to render a "X questions hidden, claimed by Y"
     *  banner that names the rival intervention. */
    @SuppressWarnings("unchecked")
    public List<java.util.Map<String, Object>> claimedQuestions(Long auditId) throws CustomException {
        requireManage();
        if (auditId == null) {
            throw new CustomException("auditId required", HttpStatus.BAD_REQUEST);
        }
        java.util.List<Object[]> rows = em.createNativeQuery("""
            SELECT iq.chks_question_id, iv.id, iv.name, iv.priority, iv.status
              FROM intervention_questions iq
              JOIN interventions iv ON iv.id = iq.intervention_id
             WHERE iv.audit_id = :auditId
               AND iv.deleted_at IS NULL
               AND iv.status IN ('ACTIVE', 'DRAFT')
               AND iq.deleted_at IS NULL
             ORDER BY iq.chks_question_id, iv.id
            """).setParameter("auditId", auditId).getResultList();
        java.util.List<java.util.Map<String, Object>> out = new java.util.ArrayList<>();
        for (Object[] r : rows) {
            java.util.Map<String, Object> entry = new java.util.LinkedHashMap<>();
            entry.put("chksQuestionId", r[0]);
            entry.put("interventionId", r[1]);
            entry.put("interventionName", r[2]);
            entry.put("priority", r[3]);
            entry.put("status", r[4]);
            out.add(entry);
        }
        return out;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseDTO<InterventionDTO> close(Long id) throws CustomException {
        User loginUser = requireManage();
        Intervention i = interventionRepository.findByIdAndDeletedAtIsNull(id)
            .orElseThrow(() -> new CustomException("Intervention not found", HttpStatus.NOT_FOUND));
        if (!STATUS_ACTIVE.equals(i.getStatus())) {
            throw new CustomException("Only ACTIVE interventions can be closed", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        i.setStatus(STATUS_CLOSED);
        i.setClosedAt(new Date());
        i.setUpdatedBy(loginUser);
        interventionRepository.save(i);
        return new ResponseDTO<>("Closed", toDTO(i, true));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseDTO<Void> deleteDraft(Long id) throws CustomException {
        User loginUser = requireManage();
        Intervention i = mustFindDraft(id);
        i.setDeletedAt(new Date());
        i.setDeletedBy(loginUser);
        interventionRepository.save(i);
        return new ResponseDTO<>("Deleted", (Void) null);
    }

    // ── reads ────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<InterventionDTO> list() throws CustomException {
        requireManage();
        return interventionRepository.findByDeletedAtIsNullOrderByCreatedAtDesc().stream()
            .map(i -> toDTO(i, false))
            .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public InterventionDTO findById(Long id) throws CustomException {
        requireManage();
        Intervention i = interventionRepository.findByIdAndDeletedAtIsNull(id)
            .orElseThrow(() -> new CustomException("Intervention not found", HttpStatus.NOT_FOUND));
        return toDTO(i, true);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private Intervention mustFindDraft(Long id) throws CustomException {
        Intervention i = interventionRepository.findByIdAndDeletedAtIsNull(id)
            .orElseThrow(() -> new CustomException("Intervention not found", HttpStatus.NOT_FOUND));
        if (!STATUS_DRAFT.equals(i.getStatus())) {
            throw new CustomException("Only Drafts can be modified", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        return i;
    }

    private void validateName(String name) throws CustomException {
        if (name == null || name.isBlank())
            throw new CustomException("name required", HttpStatus.UNPROCESSABLE_ENTITY);
    }
    private void validatePriority(String p) throws CustomException {
        if (p == null || !VALID_PRIORITIES.contains(p))
            throw new CustomException("priority must be one of " + VALID_PRIORITIES, HttpStatus.UNPROCESSABLE_ENTITY);
    }
    private void validateTargetingMode(String mode) throws CustomException {
        if (mode == null) return; // optional on create — defaults to ALL at activation
        if (!VALID_TARGETING_MODES.contains(mode))
            throw new CustomException("targetingMode must be one of " + VALID_TARGETING_MODES, HttpStatus.UNPROCESSABLE_ENTITY);
    }

    private void persistQuestions(Intervention i, List<Long> questionIds, User loginUser) throws CustomException {
        Set<Long> deduped = new HashSet<>(questionIds);
        List<ChksQuestion> qs = chksQuestionRepository.findByIdIn(new ArrayList<>(deduped));
        if (qs.size() != deduped.size()) {
            throw new CustomException("One or more chksQuestionIds invalid", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        for (ChksQuestion q : qs) {
            if (interventionQuestionRepository.existsByInterventionIdAndChksQuestionIdAndDeletedAtIsNull(i.getId(), q.getId())) {
                continue; // idempotent on append
            }
            InterventionQuestion iq = new InterventionQuestion();
            iq.setIntervention(i);
            iq.setChksQuestion(q);
            iq.setCreatedBy(loginUser);
            interventionQuestionRepository.save(iq);
        }
    }

    private List<Long> currentQuestionIds(Long interventionId) {
        return interventionQuestionRepository.findByInterventionIdAndDeletedAtIsNull(interventionId).stream()
            .map(q -> q.getChksQuestion().getId())
            .collect(Collectors.toList());
    }

    private List<QuestionConflictDTO> computeConflicts(Long auditId, List<Long> questionIds, Long excludeInterventionId) {
        if (questionIds.isEmpty()) return Collections.emptyList();
        List<Intervention> rivals = interventionRepository
            .findActiveInterventionsClaimingQuestions(auditId, questionIds, excludeInterventionId);
        List<QuestionConflictDTO> out = new ArrayList<>();
        for (Intervention r : rivals) {
            List<Long> overlap = interventionQuestionRepository.findByInterventionIdAndDeletedAtIsNull(r.getId()).stream()
                .map(q -> q.getChksQuestion().getId())
                .filter(questionIds::contains)
                .collect(Collectors.toList());
            out.add(new QuestionConflictDTO(r.getId(), r.getName(), r.getPriority(), overlap));
        }
        return out;
    }

    /** Hold the targeting context until activation. We don't persist the
     *  ALL/BY_REGION/MANUAL hint anywhere — instead the create DTO arrives
     *  with the desired mode and we resolve to assignment ids on activate.
     *  Callers that activate via API supply the targeting on the activate
     *  call by setting it on the intervention's create payload re-sent — but
     *  for the DTO-only path we accept that the only persistent record of
     *  intent is the materialised target set. To keep the contract simple,
     *  the activate() endpoint also accepts an optional override targeting
     *  (see controller). For now: default to ALL when no targeting hint
     *  was retained — admins can use addAuditAssignments style workflow if
     *  they want a different scope. */
    private InterventionCreateDTO pendingTargetingFromMemory(Long interventionId) {
        // Defensive default: ALL (every assignment in the audit). Controller
        // overrides this when activate() is called with a targeting payload.
        InterventionCreateDTO c = new InterventionCreateDTO();
        c.setTargetingMode("ALL");
        return c;
    }

    private List<Inspection> resolveTargetAssignments(Long auditId, InterventionCreateDTO targeting) throws CustomException {
        String mode = targeting.getTargetingMode() == null ? "ALL" : targeting.getTargetingMode();
        switch (mode) {
            case "ALL":
                return auditAssignmentRepository.findByAuditIdAndDeletedAtIsNull(auditId);
            case "BY_REGION":
                if (targeting.getTargetRegionId() == null)
                    throw new CustomException("targetRegionId required for BY_REGION", HttpStatus.UNPROCESSABLE_ENTITY);
                @SuppressWarnings("unchecked")
                List<Long> aaIds = em.createNativeQuery("""
                        SELECT aa.id FROM inspections aa
                          JOIN auditee_locations aloc ON aloc.id = aa.auditee_location_id
                          JOIN cities c ON c.id = aloc.city_id
                          JOIN states s ON s.id = c.state_id
                         WHERE aa.audit_id = :auditId
                           AND aa.deleted_at IS NULL
                           AND s.region_id = :regionId
                        """)
                    .setParameter("auditId", auditId)
                    .setParameter("regionId", targeting.getTargetRegionId())
                    .getResultList();
                List<Inspection> regionScope = new ArrayList<>();
                for (Object idObj : aaIds) {
                    Long aaId = ((Number) idObj).longValue();
                    auditAssignmentRepository.findById(aaId).ifPresent(regionScope::add);
                }
                return regionScope;
            case "MANUAL":
                if (targeting.getTargetAuditAssignmentIds() == null || targeting.getTargetAuditAssignmentIds().isEmpty())
                    throw new CustomException("targetAuditAssignmentIds required for MANUAL", HttpStatus.UNPROCESSABLE_ENTITY);
                Set<Long> wanted = new HashSet<>(targeting.getTargetAuditAssignmentIds());
                List<Inspection> all = auditAssignmentRepository.findByAuditIdAndDeletedAtIsNull(auditId);
                return all.stream().filter(aa -> wanted.contains(aa.getId())).collect(Collectors.toList());
            default:
                throw new CustomException("Unknown targetingMode " + mode, HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    /** Activation entrypoint that lets the controller pass an explicit
     *  targeting override. Avoids the in-memory hand-off problem. */
    @Transactional(rollbackFor = Exception.class)
    public ResponseDTO<InterventionDTO> activateWithTargeting(Long id, InterventionCreateDTO targetingOverride) throws CustomException {
        User loginUser = requireManage();
        Intervention i = mustFindDraft(id);
        List<Long> qIds = currentQuestionIds(id);
        if (qIds.isEmpty()) {
            throw new CustomException("Cannot activate without any questions", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        List<QuestionConflictDTO> conflicts = computeConflicts(i.getAudit().getId(), qIds, id);
        if (!conflicts.isEmpty()) {
            throw new CustomException(
                "Activation conflicts with another active intervention — call previewActivationConflicts to see details",
                HttpStatus.CONFLICT);
        }
        InterventionCreateDTO targeting = targetingOverride != null ? targetingOverride : pendingTargetingFromMemory(id);
        List<Inspection> targets = resolveTargetAssignments(i.getAudit().getId(), targeting);
        Date now = new Date();
        for (Inspection aa : targets) {
            Optional<InterventionAssignmentTarget> existing =
                targetRepository.findByInterventionIdAndInspectionIdAndDeletedAtIsNull(id, aa.getId());
            if (existing.isPresent()) continue;
            InterventionAssignmentTarget t = new InterventionAssignmentTarget();
            t.setIntervention(i);
            t.setInspection(aa);
            t.setCreatedBy(loginUser);
            t.setCreatedAt(now);
            targetRepository.save(t);
        }
        i.setStatus(STATUS_ACTIVE);
        i.setActivatedAt(now);
        i.setUpdatedBy(loginUser);
        interventionRepository.save(i);

        // Back-fill: an OEM may activate this intervention AFTER the audit
        // is partially or fully complete. The instantiation listener only
        // fires on NEW approvals, so without this sweep, any UC already
        // APPROVED on a target audit_assignment would never get a plan.
        // Run the same instantiation logic the listener uses, idempotent
        // (existing plans are skipped by instantiateForApprovedAudit's
        // findByInterventionIdAndInspectionIdAndDeletedAtIsNull check).
        backfillPlansForExistingApprovals(targets);

        return new ResponseDTO<>("Activated", toDTO(i, true));
    }

    /** For each target audit_assignment, find its already-APPROVED audit
     *  UCs and run the listener's instantiation logic against them.
     *  Idempotent: re-runs are no-ops because instantiateForApprovedAudit
     *  skips plans that already exist for the (intervention, aa) pair. */
    private void backfillPlansForExistingApprovals(List<Inspection> targets) throws CustomException {
        for (Inspection aa : targets) {
            List<Inspection> approvedUcs =
                userChecksheetRepository.findByAuditAssignmentIdAndDeletedAtIsNullOrdered(aa.getId())
                    .stream().filter(uc -> "APPROVED".equals(uc.getStatus()))
                    .collect(java.util.stream.Collectors.toList());
            for (Inspection uc : approvedUcs) {
                interventionAssignmentService.instantiateForApprovedAudit(uc);
            }
        }
    }

    // ── DTO mapping ──────────────────────────────────────────────────────────

    private InterventionDTO toDTO(Intervention i, boolean withDetail) {
        InterventionDTO.InterventionDTOBuilder b = InterventionDTO.builder()
            .id(i.getId())
            .auditId(i.getAudit() == null ? null : i.getAudit().getId())
            .auditName(i.getAudit() == null ? null : i.getAudit().getName())
            .name(i.getName())
            .theme(i.getTheme())
            .priority(i.getPriority())
            .targetDate(i.getTargetDate())
            .status(i.getStatus())
            .activatedAt(i.getActivatedAt())
            .closedAt(i.getClosedAt())
            .createdAt(i.getCreatedAt())
            .updatedAt(i.getUpdatedAt());

        // Stats — cheap aggregate counts via repository.
        long totalTargets = targetRepository.findByInterventionIdAndDeletedAtIsNull(i.getId()).size();
        b.totalAssignmentTargets(totalTargets);
        List<com.checkSheet.entity.Inspection> assigns =
            interventionAssignmentRepository.findByInterventionIdAndDeletedAtIsNullOrderByCreatedAtDesc(i.getId());
        b.assignmentsInstantiated(assigns.size());
        // Post-V1.28 the plan IS an Inspection of kind=INTERVENTION; "completed"
        // means the regular validate/approve flow reached APPROVED. The legacy
        // COMPLETED/NON_COMPLIANT statuses no longer exist (CHECK constraint
        // forbids them). NON_COMPLIANT is deferred per M-COMP-001 — it's a
        // derived state, not stored, so the count stays 0 until that PR lands.
        b.assignmentsCompleted(assigns.stream().filter(a -> "APPROVED".equals(a.getStatus())).count());
        b.assignmentsNonCompliant(0L);

        if (withDetail) {
            b.questionIds(currentQuestionIds(i.getId()));
            b.targetAuditAssignmentIds(
                targetRepository.findByInterventionIdAndDeletedAtIsNull(i.getId()).stream()
                    .map(t -> t.getInspection().getId())
                    .collect(Collectors.toList()));
        }
        return b.build();
    }
}
