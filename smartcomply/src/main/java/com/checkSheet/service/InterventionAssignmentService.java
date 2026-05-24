package com.checkSheet.service;

import com.checkSheet.DTO.InterventionAssignmentDTO;
import com.checkSheet.DTO.NonCompliantClosureDTO;
import com.checkSheet.DTO.response.ResponseDTO;
import com.checkSheet.entity.Inspection;
import com.checkSheet.entity.InterventionAssignmentTarget;
import com.checkSheet.entity.UserChecksheetAnswer;
import com.checkSheet.exception.CustomException;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Public contract for the per-dealership "Improvement Plan" instances. */
public interface InterventionAssignmentService {

    /** "My Plans" — plans visible to the current user via their
     *  dealer-principal link on the underlying audit_assignment. */
    List<InterventionAssignmentDTO> myPlans() throws CustomException;

    /** All plans for an intervention (admin / management view). */
    List<InterventionAssignmentDTO> findByIntervention(Long interventionId) throws CustomException;

    /** All plans for a dealership (rolls up across that dealer's locations). */
    List<InterventionAssignmentDTO> findByDealer(Long auditeeId) throws CustomException;

    /** All plans on a specific physical location. */
    List<InterventionAssignmentDTO> findByLocation(Long auditeeLocationId) throws CustomException;

    InterventionAssignmentDTO findById(Long id) throws CustomException;

    /** Dealer Principal acknowledges the plan — flips status PENDING → IN_PROGRESS. */
    ResponseDTO<InterventionAssignmentDTO> acknowledge(Long id) throws CustomException;

    /** Explicit Non-Compliant closure (PRD §3.3.2) — a reason is required. */
    ResponseDTO<InterventionAssignmentDTO> closeAsNonCompliant(Long id, NonCompliantClosureDTO body) throws CustomException;

    /** Cron entrypoint — flips PENDING/IN_PROGRESS to NON_COMPLIANT once
     *  past target_date. Idempotent. */
    int markNonCompliantIfOverdue() throws CustomException;

    /** Listener entrypoint — when a UC on an audit_assignment goes APPROVED,
     *  instantiate plans for any ACTIVE intervention whose target set
     *  includes this audit_assignment AND whose question scope intersects
     *  the failing answers. Returns the number of plans created. */
    int instantiateForApprovedAudit(Inspection uc) throws CustomException;

    /** Listener entrypoint — when a UC on an intervention_assignment goes
     *  APPROVED, evaluate the plan-completion rule (all tracked questions
     *  OK on this latest UC). Returns true iff status flipped to COMPLETED. */
    boolean evaluatePlanCompletion(Inspection uc) throws CustomException;

    /**
     * Per-target slice of {@link #instantiateForApprovedAudit(Inspection)}.
     * Public on the interface ONLY so the Spring proxy honors its
     * {@code REQUIRES_NEW} transaction on the self-injected call from
     * {@code instantiateForApprovedAudit}. Not for outside callers.
     *
     * @return 1 if a plan row was created, 0 if skipped (inactive intervention,
     *         no failing/scope overlap, or pre-existing plan).
     */
    int instantiatePlanForTarget(Inspection uc,
                                  InterventionAssignmentTarget target,
                                  Set<Long> failingQuestionIds,
                                  Map<Long, UserChecksheetAnswer> answerByQuestionId,
                                  Map<Long, List<com.checkSheet.entity.InterventionQuestion>> ivQuestionsByIv)
        throws CustomException;
}
