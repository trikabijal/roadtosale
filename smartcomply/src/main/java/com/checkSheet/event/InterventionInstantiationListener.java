package com.checkSheet.event;

import com.checkSheet.entity.Inspection;
import com.checkSheet.exception.CustomException;
import com.checkSheet.repository.UserChecksheetRepository;
import com.checkSheet.service.InterventionAssignmentService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Reacts to Inspection APPROVED events and routes them to the right
 * intervention-side handler based on which parent the UC is bound to.
 *
 * After-commit transactional dispatch ensures we never write Plans for an
 * approval that ultimately rolled back.
 */
@Slf4j
@Component
public class InterventionInstantiationListener {

    @Autowired private UserChecksheetRepository userChecksheetRepository;
    @Autowired private InterventionAssignmentService interventionAssignmentService;

    @Async("interventionListenerExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onApproved(UserChecksheetApprovedEvent ev) {
        Inspection uc = userChecksheetRepository.findById(ev.getInspectionId()).orElse(null);
        if (uc == null) {
            log.warn("InterventionInstantiationListener: uc {} not found", ev.getInspectionId());
            return;
        }
        try {
            if (ev.getAuditAssignmentId() != null) {
                interventionAssignmentService.instantiateForApprovedAudit(uc);
            } else if (ev.getInterventionAssignmentId() != null) {
                interventionAssignmentService.evaluatePlanCompletion(uc);
            } else {
                log.warn("InterventionInstantiationListener: uc {} has neither parent — ignoring", uc.getId());
            }
        } catch (DataIntegrityViolationException dive) {
            // Concurrent approvals against the same (intervention, location) can race
            // on the inspections unique partial index
            //   UNIQUE(intervention_id, auditee_location_id)
            //     WHERE kind='INTERVENTION' AND deleted_at IS NULL
            // The index is authoritative — if another thread won the race and created
            // the plan first, our duplicate save is benign and skipping is the correct
            // behavior. Log at info: this is expected under burst load, not an error.
            log.info("InterventionInstantiationListener: intervention plan creation race (idempotent skip) for uc {} — {}",
                uc.getId(), dive.getMessage());
        } catch (CustomException ce) {
            log.error("InterventionInstantiationListener: failed for uc {} — {}", uc.getId(), ce.getMessage(), ce);
        }
    }
}
