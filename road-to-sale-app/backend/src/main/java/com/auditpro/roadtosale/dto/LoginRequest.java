package com.auditpro.roadtosale.dto;

import jakarta.validation.constraints.NotBlank;

/** {@code POST /auth/login} body. */
public record LoginRequest(
        @NotBlank String username,
        @NotBlank String password,
        @NotBlank String deviceType) {
}
