package com.auditpro.roadtosale.controller;

import com.auditpro.roadtosale.dto.LoginRequest;
import com.auditpro.roadtosale.dto.LoginResponse;
import com.auditpro.roadtosale.dto.RefreshRequest;
import com.auditpro.roadtosale.dto.RefreshResponse;
import com.auditpro.roadtosale.service.AuthService;
import com.auditpro.roadtosale.web.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public auth endpoints: login and refresh. */
@RestController
@RequestMapping("/auth")
@Tag(name = "auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @Operation(summary = "Log in with username + password")
    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest req) {
        return ApiResponse.ok(authService.login(req.username(), req.password()));
    }

    @Operation(summary = "Exchange a refresh token for a new token pair")
    @PostMapping("/refresh")
    public ApiResponse<RefreshResponse> refresh(@Valid @RequestBody RefreshRequest req) {
        return ApiResponse.ok(authService.refresh(req.refreshToken()));
    }
}
