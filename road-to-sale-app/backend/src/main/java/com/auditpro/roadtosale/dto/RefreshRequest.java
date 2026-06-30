package com.auditpro.roadtosale.dto;

import jakarta.validation.constraints.NotBlank;

/** {@code POST /auth/refresh} body. */
public record RefreshRequest(@NotBlank String refreshToken) {
}
