package com.checkSheet.controller;

import com.checkSheet.DTO.response.ResponseDTO;
import com.checkSheet.entity.User;
import com.checkSheet.exception.CustomException;
import com.trika.llm.BenchmarkingLlmClient;
import com.trika.llm.LlmClient;
import com.trika.llm.SpendingGuard;
import com.checkSheet.service.PermissionService;
import com.checkSheet.service.UtilityService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@CrossOrigin(origins = "*", allowedHeaders = "*")
@RequestMapping("/api/ai/admin")
@ConditionalOnProperty(name = "ai.photo-assessment.enabled", havingValue = "true")
public class AiAdminController {

    @Autowired
    private LlmClient llmClient;

    @Autowired
    private UtilityService utilityService;

    @Autowired
    private PermissionService permissionService;

    private User requireAdmin() throws CustomException {
        User user = utilityService.getCurrentLoggedInUser()
                .orElseThrow(() -> new CustomException("User not authenticated", HttpStatus.UNAUTHORIZED));
        if (!permissionService.hasPermission(user.getId(), "SUPER_ADMIN")) {
            throw new CustomException("Admin access required", HttpStatus.FORBIDDEN);
        }
        return user;
    }

    @PostMapping("benchmark")
    public ResponseEntity<?> toggleBenchmark(@RequestParam("enabled") boolean enabled) {
        try {
            requireAdmin();
            if (llmClient instanceof BenchmarkingLlmClient benchmarking) {
                benchmarking.setEnabled(enabled);
                return ResponseEntity.ok(new ResponseDTO<>("Benchmarking " + (enabled ? "enabled" : "disabled"),
                        Map.of("benchmarkEnabled", enabled)));
            }
            return ResponseEntity.ok(new ResponseDTO<>(false, "Benchmarking not available"));
        } catch (CustomException e) {
            return ResponseEntity.status(e.getHttpStatus()).body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    @GetMapping("status")
    public ResponseEntity<?> getStatus() {
        try {
            requireAdmin();
            Map<String, Object> status = new LinkedHashMap<>();
            status.put("provider", llmClient.getProvider().getValue());
            status.put("model", llmClient.getModel());

            if (llmClient instanceof BenchmarkingLlmClient benchmarking) {
                status.put("benchmarkEnabled", benchmarking.isEnabled());

                SpendingGuard guard = benchmarking.getSpendingGuard();
                Map<String, Object> spending = new LinkedHashMap<>();
                spending.put("spentCents", String.format("%.4f", guard.getSpentUsd() * 100));
                spending.put("limitCents", String.format("%.1f", guard.getMaxSpendUsd() * 100));
                spending.put("calls", guard.getCallCount());
                spending.put("callLimit", guard.getMaxCalls());
                spending.put("tripped", guard.isTripped());
                status.put("spendingGuard", spending);
            }

            return ResponseEntity.ok(new ResponseDTO<>("LLM status", status));
        } catch (CustomException e) {
            return ResponseEntity.status(e.getHttpStatus()).body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    @PostMapping("spending/reset")
    public ResponseEntity<?> resetSpendingGuard() {
        try {
            requireAdmin();
            if (llmClient instanceof BenchmarkingLlmClient benchmarking) {
                benchmarking.getSpendingGuard().reset();
                return ResponseEntity.ok(new ResponseDTO<>("Spending guard reset",
                        Map.of("spentCents", "0.0000", "calls", 0, "tripped", false)));
            }
            return ResponseEntity.ok(new ResponseDTO<>(false, "Spending guard not available"));
        } catch (CustomException e) {
            return ResponseEntity.status(e.getHttpStatus()).body(new ResponseDTO<>(false, e.getMessage()));
        }
    }
}
