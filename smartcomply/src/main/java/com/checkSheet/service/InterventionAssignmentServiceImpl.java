package com.checkSheet.service;

import com.checkSheet.DTO.InterventionAssignmentDTO;
import com.checkSheet.DTO.NonCompliantClosureDTO;
import com.checkSheet.DTO.response.ResponseDTO;
import com.checkSheet.constant.InterventionPermissions;
import com.checkSheet.entity.Audit;
import com.checkSheet.entity.AuditeeLocation;
import com.checkSheet.entity.ChksQuestion;
import com.checkSheet.entity.Inspection;
import com.checkSheet.entity.Intervention;
import com.checkSheet.entity.InterventionAssignmentQuestion;
import com.checkSheet.entity.InterventionAssignmentTarget;
import com.checkSheet.entity.InterventionQuestion;
import com.checkSheet.entity.User;
import com.checkSheet.entity.UserChecksheetAnswer;
import com.checkSheet.exception.CustomException;
import com.checkSheet.repository.AuditAssignmentRepository;
import com.checkSheet.repository.InterventionAssignmentQuestionRepository;
import com.checkSheet.repository.InterventionAssignmentRepository;
import com.checkSheet.repository.InterventionAssignmentTargetRepository;
import com.checkSheet.repository.InterventionQuestionRepository;
import com.checkSheet.repository.InterventionRepository;
import com.checkSheet.repository.UserChecksheetAnswerRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
public class InterventionAssignmentServiceImpl implements InterventionAssignmentService {

    private static final Short JUDGEMENT_OK = (short) 1;

    // Pre-V1.28 plan-status constants removed (PENDING/IN_PROGRESS/COMPLETED/NON_COMPLIANT).
    // Post-V1.28 the plan IS an Inspection with kind=INTERVENTION; its status flows
    // ASSIGNED → IN_PROGRESS → SUBMITTED → VALIDATED → APPROVED via the regular
    // inspection lifecycle. Acknowledge / non-compliant flows are deferred
    // (M-ACK-001, M-COMP-001).

    @Autowired private InterventionRepository interventionRepository;
    @Autowired private InterventionQuestionRepository interventionQuestionRepository;
    @Autowired private InterventionAssignmentRepository interventionAssignmentRepository;
    @Autowired private InterventionAssignmentTargetRepository targetRepository;
    @Autowired private InterventionAssignmentQuestionRepository iaQuestionRepository;
    @Autowired private UserChecksheetAnswerRepository userChecksheetAnswerRepository;
    @Autowired private AuditAssignmentRepository auditAssignmentRepository;
    @Autowired private UtilityService utilityService;
    @Autowired private PermissionService permissionService;

    /**
     * Self-injection for {@code REQUIRES_NEW} transaction boundaries. Calls to
     * {@code instantiatePlanForTarget(...)} must go through the proxy (not via
     * {@code this}) for Spring's transaction interceptor to see them and start
     * a fresh transaction per target. {@code @Lazy} breaks the bean-init cycle.
     */
    @Autowired @Lazy private InterventionAssignmentService self;

    private User requireView() throws CustomException {
        User u = utilityService.getCurrentLoggedInUser()
            .orElseThrow(() -> new CustomException("Not authenticated", HttpStatus.UNAUTHORIZED));
        if (!permissionService.hasPermission(u.getId(), InterventionPermissions.INTERVENTION_ASSIGNMENT_VIEW)) {
            throw new CustomException("Missing permission INTERVENTION_ASSIGNMENT_VIEW", HttpStatus.FORBIDDEN);
        }
        return u;
    }

    private User requireAck() throws CustomException {
        User u = utilityService.getCurrentLoggedInUser()
            .orElseThrow(() -> new CustomException("Not authenticated", HttpStatus.UNAUTHORIZED));
        if (!permissionService.hasPermission(u.getId(), InterventionPermissions.INTERVENTION_ASSIGNMENT_ACKNOWLEDGE)) {
            throw new CustomException("Missing permission INTERVENTION_ASSIGNMENT_ACKNOWLEDGE", HttpStatus.FORBIDDEN);
        }
        return u;
    }

    private User requireManage() throws CustomException {
        User u = utilityService.getCurrentLoggedInUser()
            .orElseThrow(() -> new CustomException("Not authenticated", HttpStatus.UNAUTHORIZED));
        if (!permissionService.hasPermission(u.getId(), InterventionPermissions.INTERVENTION_ASSIGNMENT_MANAGE)) {
            throw new CustomException("Missing permission INTERVENTION_ASSIGNMENT_MANAGE", HttpStatus.FORBIDDEN);
        }
        return u;
    }

    // ── reads ────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<InterventionAssignmentDTO> myPlans() throws CustomException {
        User u = requireView();
        return interventionAssignmentRepository.findMineByDealerPrincipal(u.getId()).stream()
            .map(this::toDTO).collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<InterventionAssignmentDTO> findByIntervention(Long interventionId) throws CustomException {
        requireView();
        return interventionAssignmentRepository.findByInterventionIdAndDeletedAtIsNullOrderByCreatedAtDesc(interventionId).stream()
            .map(this::toDTO).collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<InterventionAssignmentDTO> findByDealer(Long auditeeId) throws CustomException {
        requireView();
        return interventionAssignmentRepository.findByAuditeeIdAndDeletedAtIsNull(auditeeId).stream()
            .map(this::toDTO).collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<InterventionAssignmentDTO> findByLocation(Long auditeeLocationId) throws CustomException {
        requireView();
        return interventionAssignmentRepository.findByAuditeeLocationIdAndDeletedAtIsNull(auditeeLocationId).stream()
            .map(this::toDTO).collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public InterventionAssignmentDTO findById(Long id) throws CustomException {
        requireView();
        Inspection ia = interventionAssignmentRepository.findByIdAndDeletedAtIsNull(id)
            .orElseThrow(() -> new CustomException("Plan not found", HttpStatus.NOT_FOUND));
        return toDTO(ia);
    }

    // ── lifecycle ────────────────────────────────────────────────────────────

    // Acknowledge: V1.30 enabled this once `dealer_principal_user_id` was
    // available on the inspections row. Soft signal — stamps acknowledged_at
    // and acknowledged_by but does NOT advance the inspection status. The
    // operator's createOrUpdate is what moves status ASSIGNED → IN_PROGRESS.
    //
    // The close-as-non-compliant / overdue-sweep flows remain deferred —
    // NON_COMPLIANT is a derived state (compliance thresholds vary per tenant),
    // tracked at M-COMP-001. Those endpoints continue to return 501.

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseDTO<InterventionAssignmentDTO> acknowledge(Long id) throws CustomException {
        User caller = requireAck();
        Inspection plan = interventionAssignmentRepository.findByIdAndDeletedAtIsNull(id)
            .orElseThrow(() -> new CustomException("Plan not found", HttpStatus.NOT_FOUND));
        if (!"INTERVENTION".equals(plan.getKind())) {
            throw new CustomException("Inspection is not an intervention plan", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        // Ownership gate: only the dealer principal stamped on the plan may
        // acknowledge. The INTERVENTION_ASSIGNMENT_ACKNOWLEDGE permission
        // (checked in requireAck) is a coarse gate; this row-level check is
        // what stops one dealership's principal from acking another's plan.
        if (plan.getDealerPrincipalUser() == null
                || !Objects.equals(plan.getDealerPrincipalUser().getId(), caller.getId())) {
            throw new CustomException(
                "Only the dealer principal of this dealership can acknowledge this plan",
                HttpStatus.FORBIDDEN);
        }
        // Idempotent — re-acking just refreshes the timestamp. FE shouldn't
        // call this twice (button hides after ack) but if it does, no harm.
        plan.setAcknowledgedAt(new Date());
        plan.setAcknowledgedBy(caller);
        plan.setUpdatedBy(caller);
        interventionAssignmentRepository.save(plan);
        return new ResponseDTO<>("Plan acknowledged", toDTO(plan));
    }

    @Override
    public ResponseDTO<InterventionAssignmentDTO> closeAsNonCompliant(Long id, NonCompliantClosureDTO body) throws CustomException {
        throw new CustomException(
            "Plan close-as-non-compliant is not implemented in V1.28 — NON_COMPLIANT is a derived state, tracked at GitLab issue M-COMP-001",
            HttpStatus.NOT_IMPLEMENTED);
    }

    @Override
    public int markNonCompliantIfOverdue() throws CustomException {
        // No-op. Overdue sweep is part of the M-COMP-001 deferred work.
        return 0;
    }

    // ── instantiation (audit-side approval) ──────────────────────────────────

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int instantiateForApprovedAudit(Inspection uc) throws CustomException {
        if (uc == null || !"AUDIT".equals(uc.getKind())) return 0;
        Long auditAssignmentId = uc.getId();    // post-V1.28: same row

        // Failing questions (judgement != OK) on this UC.
        // Pre-bucket the answer list by chks_question_id so the inner loop
        // below is O(K) lookup instead of O(K * answers).
        List<UserChecksheetAnswer> answers = userChecksheetAnswerRepository.findByInspectionId(uc.getId());
        java.util.Map<Long, UserChecksheetAnswer> answerByQuestionId = new java.util.HashMap<>();
        Set<Long> failingQuestionIds = new java.util.HashSet<>();
        for (UserChecksheetAnswer a : answers) {
            if (a.getChksQuestion() == null) continue;
            answerByQuestionId.putIfAbsent(a.getChksQuestion().getId(), a);
            if (a.getJudgement() == null || !JUDGEMENT_OK.equals(a.getJudgement())) {
                failingQuestionIds.add(a.getChksQuestion().getId());
            }
        }
        if (failingQuestionIds.isEmpty()) {
            log.debug("instantiateForApprovedAudit: uc {} has no failing answers, skipping", uc.getId());
            return 0;
        }

        List<InterventionAssignmentTarget> targets =
            targetRepository.findByInspectionIdAndDeletedAtIsNull(auditAssignmentId);
        if (targets.isEmpty()) {
            log.debug("instantiateForApprovedAudit: uc {} aa {} has no campaigns targeting it",
                uc.getId(), auditAssignmentId);
            return 0;
        }

        // Batch-load every InterventionQuestion for the targeted interventions
        // in one round-trip, then bucket by intervention id. Avoids N queries
        // when the same audit_assignment is in scope for many campaigns.
        List<Long> interventionIds = targets.stream()
            .map(t -> t.getIntervention())
            .filter(java.util.Objects::nonNull)
            .map(Intervention::getId)
            .distinct()
            .collect(Collectors.toList());
        java.util.Map<Long, List<InterventionQuestion>> ivQuestionsByIv = new java.util.HashMap<>();
        if (!interventionIds.isEmpty()) {
            for (InterventionQuestion iq : interventionQuestionRepository
                    .findByInterventionIdInAndDeletedAtIsNull(interventionIds)) {
                ivQuestionsByIv
                    .computeIfAbsent(iq.getIntervention().getId(), k -> new java.util.ArrayList<>())
                    .add(iq);
            }
        }

        // Per-target work runs in its own REQUIRES_NEW transaction (via the
        // self-injected proxy) so one bad target — e.g. a DataIntegrityViolation
        // from a concurrent approval racing on the unique partial index — does
        // NOT roll back sibling plans created earlier in this loop. We swallow
        // per-target failures here and continue; the outer @Transactional only
        // wraps the read-side batch loads above.
        int created = 0;
        for (InterventionAssignmentTarget t : targets) {
            try {
                created += self.instantiatePlanForTarget(
                    uc, t, failingQuestionIds, answerByQuestionId, ivQuestionsByIv);
            } catch (Exception ex) {
                Long ivId = t.getIntervention() == null ? null : t.getIntervention().getId();
                log.warn("instantiateForApprovedAudit: per-target plan creation failed (iv {} aa {}) — continuing — {}",
                    ivId, auditAssignmentId, ex.getMessage());
            }
        }
        if (created > 0) log.info("instantiateForApprovedAudit: created {} plans for aa {}", created, auditAssignmentId);
        return created;
    }

    /**
     * Creates a single intervention plan (Inspection row, kind=INTERVENTION)
     * plus its tracked-question rows for one target. Runs in a fresh
     * transaction (REQUIRES_NEW) so failure here rolls back ONLY this target
     * — siblings created in the outer loop are preserved.
     *
     * Returns 1 if a plan was created, 0 if skipped (inactive intervention,
     * empty scope overlap, or pre-existing plan).
     *
     * <p><b>Must be called via the self-proxy</b> (see {@code self} field)
     * — calling {@code this.instantiatePlanForTarget(...)} bypasses the
     * Spring transaction interceptor and the REQUIRES_NEW boundary is lost.
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public int instantiatePlanForTarget(Inspection uc,
                                         InterventionAssignmentTarget t,
                                         Set<Long> failingQuestionIds,
                                         Map<Long, UserChecksheetAnswer> answerByQuestionId,
                                         Map<Long, List<InterventionQuestion>> ivQuestionsByIv)
            throws CustomException {
        if (uc == null || t == null) return 0;
        Intervention iv = t.getIntervention();
        if (iv == null || !"ACTIVE".equals(iv.getStatus()) || iv.getDeletedAt() != null) return 0;

        Long auditAssignmentId = uc.getId();   // post-V1.28: same row

        // Question-scope intersection (read from the batch bucket)
        List<InterventionQuestion> ivQuestions = ivQuestionsByIv.getOrDefault(iv.getId(), List.of());
        Set<Long> ivQids = ivQuestions.stream()
            .map(q -> q.getChksQuestion().getId())
            .collect(Collectors.toSet());
        Set<Long> overlap = new HashSet<>(failingQuestionIds);
        overlap.retainAll(ivQids);
        if (overlap.isEmpty()) return 0;

        // Idempotency: skip if a plan already exists.
        // Note this is a best-effort pre-check — concurrent approvals can still
        // race past this and collide on the unique partial index at save time.
        // The DataIntegrityViolationException that throws is caught by the
        // async listener (idempotent-skip), and our REQUIRES_NEW boundary
        // ensures the failed slice rolls back without affecting siblings.
        if (interventionAssignmentRepository
            .findByInterventionIdAndAuditAssignmentIdAndDeletedAtIsNull(iv.getId(), auditAssignmentId)
            .isPresent()) {
            return 0;
        }

        // Create the plan as a kind=INTERVENTION inspection, snapshotting
        // priority + target_date and inheriting location / operator / principal
        // from the underlying audit-kind inspection.
        Inspection ia = new Inspection();
        ia.setKind("INTERVENTION");                       // V1.28 CHECK: NOT NULL + IN (AUDIT|INTERVENTION)
        ia.setStatus("ASSIGNED");                         // V1.28 CHECK: starting state
        ia.setIntervention(iv);
        ia.setAuditeeLocation(uc.getAuditeeLocation());
        ia.setOperatorUser(uc.getOperatorUser());
        ia.setDealerPrincipalUser(uc.getDealerPrincipalUser());
        ia.setRegionOwnerUser(uc.getRegionOwnerUser());
        ia.setPriority(iv.getPriority());
        ia.setTargetDate(iv.getTargetDate() == null ? new Date() : iv.getTargetDate());
        ia.setCreatedBy(iv.getCreatedBy());
        interventionAssignmentRepository.save(ia);

        // Persist the failing-question subset on the plan.
        for (InterventionQuestion ivq : ivQuestions) {
            if (!overlap.contains(ivq.getChksQuestion().getId())) continue;
            InterventionAssignmentQuestion iaq = new InterventionAssignmentQuestion();
            iaq.setInspection(ia);
            iaq.setChksQuestion(ivq.getChksQuestion());
            // Carry the chks_question_result the answer was bound to (helps re-inspection UI).
            UserChecksheetAnswer matchedAnswer = answerByQuestionId.get(ivq.getChksQuestion().getId());
            if (matchedAnswer != null) {
                iaq.setChksQuestionResult(matchedAnswer.getChksQuestionResult());
            }
            iaq.setCreatedBy(iv.getCreatedBy());
            iaQuestionRepository.save(iaq);
        }
        return 1;
    }

    // ── completion (intervention-side approval) ──────────────────────────────

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean evaluatePlanCompletion(Inspection uc) throws CustomException {
        // Post-V1.28: the plan IS the inspection. The inspection reaching
        // status=APPROVED via the regular validate/approve flow IS the
        // completion. There's no separate "ia" row to update or a distinct
        // COMPLETED state; APPROVED is terminal for the kind=INTERVENTION
        // inspection just like for the kind=AUDIT one.
        //
        // We still emit a log + the tracked-question sanity check so a
        // misconfigured intervention (no tracked questions) is visible.
        if (uc == null || !"INTERVENTION".equals(uc.getKind())) return false;
        if (!"APPROVED".equals(uc.getStatus())) return false;

        List<InterventionAssignmentQuestion> tracked =
            iaQuestionRepository.findByInspectionIdAndDeletedAtIsNull(uc.getId());
        if (tracked.isEmpty()) {
            log.warn("evaluatePlanCompletion: intervention inspection {} has no tracked questions — misconfigured", uc.getId());
            return false;
        }

        List<UserChecksheetAnswer> ans = userChecksheetAnswerRepository.findByInspectionId(uc.getId());
        Set<Long> okQids = ans.stream()
            .filter(a -> a.getChksQuestion() != null && JUDGEMENT_OK.equals(a.getJudgement()))
            .map(a -> a.getChksQuestion().getId())
            .collect(Collectors.toSet());

        boolean allOk = tracked.stream()
            .allMatch(q -> q.getChksQuestion() != null && okQids.contains(q.getChksQuestion().getId()));
        log.info("evaluatePlanCompletion: intervention inspection {} APPROVED, all-tracked-OK={}", uc.getId(), allOk);
        return allOk;
    }

    // ── DTO mapping ──────────────────────────────────────────────────────────

    private InterventionAssignmentDTO toDTO(Inspection ia) {
        // Post-V1.28: the plan IS an Inspection of kind=INTERVENTION. All the
        // fields we used to walk through the AA→Audit chain for live directly
        // on the inspection (location, principal, region owner) or on the
        // intervention (audit context).
        Intervention iv = ia.getIntervention();
        AuditeeLocation loc = ia.getAuditeeLocation();
        Audit auditCtx = iv == null ? null : iv.getAudit();

        // C2 fix: derive the actual source audit inspection id (kind=AUDIT)
        // at the same (audit, location) as this plan. Pre-V1.28, AA and UC
        // were separate tables and auditAssignmentId was a true FK. Post-
        // V1.28 the plan IS its own Inspection row — its id is NOT the
        // source audit's id. Returning ia.getId() here was a false foreign
        // key that poisoned any FE consumer treating auditAssignmentId as
        // an anchor back to the original audit. Look up the AUDIT-kind
        // inspection at the same (audit_id, auditee_location_id).
        Long sourceAuditAssignmentId = null;
        if (auditCtx != null && loc != null) {
            sourceAuditAssignmentId = auditAssignmentRepository
                .findByAuditIdAndAuditeeLocationIdAndDeletedAtIsNull(auditCtx.getId(), loc.getId())
                .map(Inspection::getId)
                .orElse(null);
        }

        InterventionAssignmentDTO.InterventionAssignmentDTOBuilder b =
            InterventionAssignmentDTO.builder()
                .id(ia.getId())
                .interventionId(iv == null ? null : iv.getId())
                .interventionName(iv == null ? null : iv.getName())
                .theme(iv == null ? null : iv.getTheme())
                .priority(ia.getPriority())
                .targetDate(ia.getTargetDate())
                .status(ia.getStatus())
                .acknowledgedAt(ia.getAcknowledgedAt())
                .acknowledgedByUserId(ia.getAcknowledgedBy() == null ? null : ia.getAcknowledgedBy().getId())
                // closeAsNonCompliant fields remain unset (M-COMP-001 deferred);
                // serialized as null which the FE handles.
                .auditAssignmentId(sourceAuditAssignmentId)
                .auditId(auditCtx == null ? null : auditCtx.getId())
                .auditName(auditCtx == null ? null : auditCtx.getName())
                .createdAt(ia.getCreatedAt())
                .updatedAt(ia.getUpdatedAt());

        if (loc != null) {
            b.auditeeLocationId(loc.getId());
            b.locationAddress(loc.getAddress());
            if (loc.getAuditee() != null) {
                b.auditeeId(loc.getAuditee().getId());
                b.auditeeName(loc.getAuditee().getName());
                b.auditeeCode(loc.getAuditee().getCode());
            }
        }
        if (ia.getDealerPrincipalUser() != null) {
            b.dealerPrincipalUserId(ia.getDealerPrincipalUser().getId());
            b.dealerPrincipalName(buildName(ia.getDealerPrincipalUser()));
        }
        if (ia.getRegionOwnerUser() != null) {
            b.regionOwnerUserId(ia.getRegionOwnerUser().getId());
            b.regionOwnerName(buildName(ia.getRegionOwnerUser()));
        }
        if (ia.getOperatorUser() != null) {
            b.operatorUserId(ia.getOperatorUser().getId());
        }

        // Failing-question subset on this plan.
        List<InterventionAssignmentQuestion> tracked =
            iaQuestionRepository.findByInspectionIdAndDeletedAtIsNull(ia.getId());
        b.questionIds(tracked.stream()
            .filter(q -> q.getChksQuestion() != null)
            .map(q -> q.getChksQuestion().getId())
            .collect(Collectors.toList()));

        // Post-V1.28 the plan IS the inspection — there are no separate
        // "waves". The DTO fields below are kept for FE back-compat (the
        // my-plans template still reads latestReinspectionStatus). The
        // plan inspection is itself the re-inspection, so its id IS the
        // latest re-inspection id by definition.
        b.reinspectionInspectionIds(java.util.Collections.singletonList(ia.getId()));
        b.latestReinspectionInspectionId(ia.getId());
        b.latestReinspectionStatus(ia.getStatus());
        // C2 fix: previously hard-coded null. A true "approved at" would
        // come from user_checksheet_approvals (M-APPROVAL-001), but the
        // plan inspection's updatedAt is set at the same transaction that
        // flips status to APPROVED — it's the best available approximation
        // post-collapse. Stays null while the plan is not yet APPROVED.
        b.latestReinspectionApprovedAt("APPROVED".equals(ia.getStatus()) ? ia.getUpdatedAt() : null);
        return b.build();
    }

    private String buildName(User u) {
        if (u == null) return null;
        String f = u.getFirstName() == null ? "" : u.getFirstName();
        String l = u.getLastName() == null ? "" : u.getLastName();
        String full = (f + " " + l).trim();
        return full.isEmpty() ? u.getUsername() : full;
    }
}
