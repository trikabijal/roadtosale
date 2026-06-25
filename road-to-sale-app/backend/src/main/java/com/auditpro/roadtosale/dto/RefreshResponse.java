package com.auditpro.roadtosale.dto;

/** {@code POST /auth/refresh} data payload. */
public record RefreshResponse(String accessToken, String refreshToken) {
}
