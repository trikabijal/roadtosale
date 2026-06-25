package com.auditpro.roadtosale.dto;

import com.auditpro.roadtosale.domain.SessionStatus;
import com.auditpro.roadtosale.domain.SessionType;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Full session shape: summary fields + transcript + derived outcomes. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SessionDTO(
        UUID id,
        SessionType type,
        SessionStatus status,
        String checksheetCode,
        OffsetDateTime startedAt,
        OffsetDateTime endedAt,
        SessionContextDTO context,
        SessionProgressDTO progress,
        String transcript,
        List<SessionOutcomeDTO> outcomes) {
}
