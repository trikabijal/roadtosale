package com.checkSheet.controller;

import com.checkSheet.DTO.AddAuditAssignmentsRequestDTO;
import com.checkSheet.DTO.AuditDTO;
import com.checkSheet.DTO.response.ResponseDTO;
import com.checkSheet.exception.CustomException;
import com.checkSheet.service.AuditService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

@Slf4j
@RestController
@CrossOrigin(origins = "*", allowedHeaders = "*")
@RequestMapping("/api/audit")
public class AuditController {

    @Autowired
    private AuditService auditService;

    // ─── Management ─────────────────────────────────────────────────────────

    @PostMapping("/createAudit")
    public ResponseEntity<?> createAudit(@RequestBody AuditDTO auditDTO) {
        try {
            return ResponseEntity.ok(auditService.createAudit(auditDTO));
        } catch (CustomException e) {
            return ResponseEntity.status(e.getHttpStatus()).body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    @PostMapping("/addAuditAssignments")
    public ResponseEntity<?> addAuditAssignments(@RequestBody AddAuditAssignmentsRequestDTO body) {
        try {
            return ResponseEntity.ok(auditService.addAuditAssignments(body.getAuditId(), body.getAssignments()));
        } catch (CustomException e) {
            return ResponseEntity.status(e.getHttpStatus()).body(new ResponseDTO<>(false, e.getMessage()));
        } catch (Exception e) {
            // Log full exception; return generic message — don't leak internals to client.
            log.error("addAuditAssignments failed for auditId={}", body == null ? null : body.getAuditId(), e);
            return ResponseEntity.badRequest().body(new ResponseDTO<>(false, "Invalid request"));
        }
    }

    @GetMapping("/list")
    public ResponseEntity<?> listAudits() {
        try {
            return ResponseEntity.ok(auditService.listAudits());
        } catch (CustomException e) {
            return ResponseEntity.status(e.getHttpStatus()).body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    @GetMapping("/{auditId}")
    public ResponseEntity<?> getAuditDetail(@PathVariable Long auditId) {
        try {
            return ResponseEntity.ok(auditService.getAuditDetail(auditId));
        } catch (CustomException e) {
            return ResponseEntity.status(e.getHttpStatus()).body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    // ─── BI stats ───────────────────────────────────────────────────────────

    @GetMapping("/{auditId}/stats/national")
    public ResponseEntity<?> nationalStats(
            @PathVariable Long auditId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        try {
            return ResponseEntity.ok(auditService.getNationalStats(auditId, startDate, endDate));
        } catch (CustomException e) {
            return ResponseEntity.status(e.getHttpStatus()).body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    @GetMapping("/{auditId}/stats/region/{regionId}")
    public ResponseEntity<?> regionStats(
            @PathVariable Long auditId, @PathVariable Long regionId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        try {
            return ResponseEntity.ok(auditService.getRegionStats(auditId, regionId, startDate, endDate));
        } catch (CustomException e) {
            return ResponseEntity.status(e.getHttpStatus()).body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    @GetMapping("/{auditId}/stats/dealer/{auditeeId}")
    public ResponseEntity<?> dealerStats(
            @PathVariable Long auditId, @PathVariable Long auditeeId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        try {
            return ResponseEntity.ok(auditService.getDealerStats(auditId, auditeeId, startDate, endDate));
        } catch (CustomException e) {
            return ResponseEntity.status(e.getHttpStatus()).body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    @GetMapping("/{auditId}/stats/location/{locationId}")
    public ResponseEntity<?> locationStats(
            @PathVariable Long auditId, @PathVariable Long locationId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        try {
            return ResponseEntity.ok(auditService.getLocationStats(auditId, locationId, startDate, endDate));
        } catch (CustomException e) {
            return ResponseEntity.status(e.getHttpStatus()).body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    @GetMapping("/userChecksheetMeta")
    public ResponseEntity<?> userChecksheetMeta(@RequestParam Long userChecksheetId) {
        try {
            return ResponseEntity.ok(auditService.getUserChecksheetMeta(userChecksheetId));
        } catch (CustomException e) {
            return ResponseEntity.status(e.getHttpStatus()).body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    /** Intervention BI summary scoped by audit + (optional) region/dealer/location.
     *  Reads the same audit_signal CTE used by every other panel. */
    @GetMapping("/{auditId}/intervention-summary/national")
    public ResponseEntity<?> interventionSummaryNational(@PathVariable Long auditId) {
        return interventionSummary(auditId, "national", null);
    }

    @GetMapping("/{auditId}/intervention-summary/region/{regionId}")
    public ResponseEntity<?> interventionSummaryRegion(@PathVariable Long auditId, @PathVariable Long regionId) {
        return interventionSummary(auditId, "region", regionId);
    }

    @GetMapping("/{auditId}/intervention-summary/dealer/{auditeeId}")
    public ResponseEntity<?> interventionSummaryDealer(@PathVariable Long auditId, @PathVariable Long auditeeId) {
        return interventionSummary(auditId, "dealer", auditeeId);
    }

    @GetMapping("/{auditId}/intervention-summary/location/{locationId}")
    public ResponseEntity<?> interventionSummaryLocation(@PathVariable Long auditId, @PathVariable Long locationId) {
        return interventionSummary(auditId, "location", locationId);
    }

    private ResponseEntity<?> interventionSummary(Long auditId, String level, Long scopeId) {
        try {
            return ResponseEntity.ok(new ResponseDTO<>("ok", auditService.getInterventionSummary(auditId, level, scopeId)));
        } catch (CustomException e) {
            return ResponseEntity.status(e.getHttpStatus()).body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    /** Audit-report overlay (PRD §4.6 + intervention scoring principle).
     *  For an audit UC, returns:
     *   - questionFlags: per-question pills naming the interventions covering it
     *   - reAuditAnswers: per-question temporal chain of post-audit answers
     *     (each tagged with intervention name, operator, judgement, photos)
     *   - headerSnapshots: original→current score deltas, per intervention */
    @GetMapping("/userChecksheet/{userChecksheetId}/improvement-overlay")
    public ResponseEntity<?> improvementOverlay(@PathVariable Long userChecksheetId) {
        try {
            return ResponseEntity.ok(new ResponseDTO<>("ok", auditService.getImprovementOverlay(userChecksheetId)));
        } catch (CustomException e) {
            return ResponseEntity.status(e.getHttpStatus()).body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    /** Category drill-down — for a single category (e.g. "EV & Sustainability"),
     *  return per-region and per-dealer failure rates so the National
     *  dashboard's "What's Failing" can expose "click EV → who's worst at EV". */
    @GetMapping("/{auditId}/stats/category-drill")
    public ResponseEntity<?> categoryDrill(
            @PathVariable Long auditId,
            @RequestParam("category") String category,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        try {
            return ResponseEntity.ok(auditService.getCategoryDrill(auditId, category, startDate, endDate));
        } catch (CustomException e) {
            return ResponseEntity.status(e.getHttpStatus()).body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    @GetMapping("/myAssignments")
    public ResponseEntity<?> myAssignments() {
        try {
            return ResponseEntity.ok(auditService.getMyAssignments());
        } catch (CustomException e) {
            return ResponseEntity.status(e.getHttpStatus()).body(new ResponseDTO<>(false, e.getMessage()));
        }
    }
}
