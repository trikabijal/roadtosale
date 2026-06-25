package com.auditpro.roadtosale.dto;

/** {@code POST /auth/login} data payload. */
public record LoginResponse(String accessToken, String refreshToken, UserDTO user) {
}
