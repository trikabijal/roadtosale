package com.auditpro.roadtosale.dto;

import com.auditpro.roadtosale.domain.SessionType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

/**
 * {@code POST /sessions} body. {@code context} is free-form and stored verbatim
 * as jsonb; {@code dealershipId} is never accepted here (taken from the token).
 */
public record CreateSessionRequest(
        @NotNull SessionType type,
        @NotBlank String checksheetCode,
        Map<String, Object> context) {
}
