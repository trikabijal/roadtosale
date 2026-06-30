package com.auditpro.roadtosale.controller;

import com.auditpro.roadtosale.web.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.Map;

/** Liveness/readiness probe — also verifies the DB is reachable. */
@RestController
@Tag(name = "health")
public class HealthController {

    private final DataSource dataSource;

    public HealthController(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Operation(summary = "Liveness/readiness probe (checks DB reachability)")
    @GetMapping("/health")
    public ResponseEntity<ApiResponse<Map<String, String>>> health() {
        boolean dbUp;
        try (Connection c = dataSource.getConnection()) {
            dbUp = c.isValid(2);
        } catch (Exception e) {
            dbUp = false;
        }
        if (!dbUp) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(ApiResponse.of(503, "DB unreachable", Map.of("status", "down")));
        }
        return ResponseEntity.ok(ApiResponse.of(200, "OK", Map.of("status", "ok")));
    }
}
