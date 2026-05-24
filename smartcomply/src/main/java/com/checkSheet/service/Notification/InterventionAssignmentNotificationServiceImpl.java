package com.checkSheet.service.Notification;

import com.checkSheet.entity.Inspection;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** Log-only no-op implementation. Real integrations live in tenant overlays. */
@Slf4j
@Service
public class InterventionAssignmentNotificationServiceImpl implements InterventionAssignmentNotificationService {

    @Override
    public void notifyPlanCreated(Inspection ia) {
        log.info("[notify] plan created — id={} aaId={}", id(ia), aaId(ia));
    }

    @Override
    public void notifyPlanAcknowledged(Inspection ia) {
        log.info("[notify] plan acknowledged — id={} aaId={}", id(ia), aaId(ia));
    }

    @Override
    public void notifyPlanOverdue(Inspection ia) {
        log.info("[notify] plan overdue → NON_COMPLIANT — id={} aaId={}", id(ia), aaId(ia));
    }

    @Override
    public void notifyPlanCompleted(Inspection ia) {
        log.info("[notify] plan completed — id={} aaId={}", id(ia), aaId(ia));
    }

    private Long id(Inspection ia)   { return ia == null ? null : ia.getId(); }
    /** Post-V1.28: the plan IS the inspection; legacy "aaId" log field is just the inspection id. */
    private Long aaId(Inspection ia) { return ia == null ? null : ia.getId(); }
}
