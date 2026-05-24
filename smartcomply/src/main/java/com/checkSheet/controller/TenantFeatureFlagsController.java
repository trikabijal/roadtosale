package com.checkSheet.controller;

import com.checkSheet.DTO.response.ResponseDTO;
import com.checkSheet.config.TenantFeatureFlags;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** Exposes the active tenant's feature flags to the frontend so it can
 *  render conditionally (e.g., hide the snapshot chain when
 *  scoreDisplayMode=CURRENT_ONLY). PRD §5. */
@Slf4j
@RestController
@CrossOrigin(origins = "*", allowedHeaders = "*")
@RequestMapping("/api/tenant")
public class TenantFeatureFlagsController {

    @Autowired
    private TenantFeatureFlags flags;

    @GetMapping("/feature-flags")
    public ResponseEntity<?> get() {
        return ResponseEntity.ok(new ResponseDTO<>("OK", Map.of(
            "actionPlanRequired", flags.isActionPlanRequired(),
            "scoreDisplayMode",   flags.getScoreDisplayMode().name()
        )));
    }
}
