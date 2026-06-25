package com.auditpro.roadtosale.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Derived per-question outcome (never stored). {@code satisfied} is true when the
 * highest-confidence event for the question meets the configured threshold.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SessionOutcomeDTO(
        String questionId,
        int stepNo,
        boolean satisfied,
        Double confidence,
        String transcriptSpan) {
}
