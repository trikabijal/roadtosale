package com.auditpro.roadtosale.controller;

import com.auditpro.roadtosale.dto.CreateSessionRequest;
import com.auditpro.roadtosale.dto.PostEventsRequest;
import com.auditpro.roadtosale.dto.PostEventsResponse;
import com.auditpro.roadtosale.dto.SessionDTO;
import com.auditpro.roadtosale.dto.SessionSummaryDTO;
import com.auditpro.roadtosale.dto.SubmitSessionRequest;
import com.auditpro.roadtosale.security.AuthenticatedUser;
import com.auditpro.roadtosale.security.CurrentUser;
import com.auditpro.roadtosale.service.SessionService;
import com.auditpro.roadtosale.web.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Session lifecycle + cue events. All routes scoped to the caller's dealership. */
@RestController
@RequestMapping("/api/v1/sessions")
@Tag(name = "sessions")
@SecurityRequirement(name = "bearerAuth")
public class SessionController {

    private final SessionService sessionService;

    public SessionController(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    @Operation(summary = "List the authenticated user's sessions (most recent first, paginated)")
    @GetMapping
    public ApiResponse<List<SessionSummaryDTO>> list(
            @CurrentUser AuthenticatedUser caller,
            @RequestParam(name = "page", required = false) Integer page,
            @RequestParam(name = "size", required = false) Integer size,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "offset", required = false) Integer offset) {
        return ApiResponse.ok(sessionService.list(caller, page, size, limit, offset));
    }

    @Operation(summary = "Create a new session (status starts ACTIVE)")
    @PostMapping
    public ApiResponse<SessionDTO> create(@CurrentUser AuthenticatedUser caller,
                                          @Valid @RequestBody CreateSessionRequest req) {
        return ApiResponse.ok(sessionService.create(caller, req));
    }

    @Operation(summary = "Get a single session by id (with derived per-question outcomes)")
    @GetMapping("/{id}")
    public ApiResponse<SessionDTO> get(@CurrentUser AuthenticatedUser caller, @PathVariable UUID id) {
        return ApiResponse.ok(sessionService.get(caller, id));
    }

    @Operation(summary = "Submit (complete) a session")
    @PostMapping("/{id}/submit")
    public ApiResponse<SessionDTO> submit(@CurrentUser AuthenticatedUser caller,
                                          @PathVariable UUID id,
                                          @RequestBody(required = false) SubmitSessionRequest req) {
        String transcript = req == null ? null : req.transcript();
        return ApiResponse.ok(sessionService.submit(caller, id, transcript));
    }

    @Operation(summary = "Append a batch of detected cue events (idempotent by cueId)")
    @PostMapping("/{id}/events")
    public ApiResponse<PostEventsResponse> appendEvents(@CurrentUser AuthenticatedUser caller,
                                                        @PathVariable UUID id,
                                                        @Valid @RequestBody PostEventsRequest req) {
        return ApiResponse.ok(sessionService.appendEvents(caller, id, req.events()));
    }
}
