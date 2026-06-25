package com.auditpro.roadtosale.dto;

import com.auditpro.roadtosale.domain.SessionStatus;
import com.auditpro.roadtosale.domain.SessionType;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Session list shape (no transcript, no outcomes). */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SessionSummaryDTO(
        UUID id,
        SessionType type,
        SessionStatus status,
        String checksheetCode,
        OffsetDateTime startedAt,
        OffsetDateTime endedAt,
        SessionContextDTO context,
        SessionProgressDTO progress) {
}
