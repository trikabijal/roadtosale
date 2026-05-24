package com.checkSheet.scheduler;

import com.checkSheet.exception.CustomException;
import com.checkSheet.service.InterventionAssignmentService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Daily sweep that flags overdue Improvement Plans Non-Compliant.
 *  Cron expression is server-local timezone (the JVM uses Asia/Kolkata for
 *  AuditPro). Runs every day at 02:30. */
@Slf4j
@Component
public class InterventionOverdueScheduler {

    @Autowired private InterventionAssignmentService interventionAssignmentService;

    @Scheduled(cron = "0 30 2 * * *")
    public void run() {
        try {
            int n = interventionAssignmentService.markNonCompliantIfOverdue();
            log.info("InterventionOverdueScheduler: marked {} plans NON_COMPLIANT", n);
        } catch (CustomException e) {
            log.error("InterventionOverdueScheduler: sweep failed — {}", e.getMessage(), e);
        }
    }
}
