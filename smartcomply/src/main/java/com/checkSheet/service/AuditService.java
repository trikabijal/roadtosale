package com.checkSheet.service;

import com.checkSheet.DTO.AuditAssignmentCreateDTO;
import com.checkSheet.DTO.AuditDTO;
import com.checkSheet.DTO.AuditAssignmentDTO;
import com.checkSheet.DTO.AuditStatsDTO;
import com.checkSheet.DTO.response.ResponseDTO;
import com.checkSheet.exception.CustomException;

import java.time.LocalDate;
import java.util.List;

public interface AuditService {

    /** Create a new audit (campaign) referencing one checksheet template. */
    ResponseDTO<AuditDTO> createAudit(AuditDTO auditDTO) throws CustomException;

    /** Bulk-attach assignments to an existing audit. Idempotent skip on duplicate (auditId, auditeeLocationId). */
    ResponseDTO<?> addAuditAssignments(Long auditId, List<AuditAssignmentCreateDTO> assignments) throws CustomException;

    /** List all audits with summary stats. */
    ResponseDTO<List<AuditDTO>> listAudits() throws CustomException;

    /** Full detail of one audit including its locations and per-location user_checksheet status. */
    ResponseDTO<AuditDTO> getAuditDetail(Long auditId) throws CustomException;

    /** BI: national-scope stats. */
    ResponseDTO<AuditStatsDTO> getNationalStats(Long auditId, LocalDate startDate, LocalDate endDate) throws CustomException;

    /** BI: regional-scope stats. */
    ResponseDTO<AuditStatsDTO> getRegionStats(Long auditId, Long regionId, LocalDate startDate, LocalDate endDate) throws CustomException;

    /** BI: dealer-scope stats (auditee = dealer). */
    ResponseDTO<AuditStatsDTO> getDealerStats(Long auditId, Long auditeeId, LocalDate startDate, LocalDate endDate) throws CustomException;

    /** BI: per-location stats. */
    ResponseDTO<AuditStatsDTO> getLocationStats(Long auditId, Long locationId, LocalDate startDate, LocalDate endDate) throws CustomException;

    /** Audit detail header context: location/dealer/region names, dates, score —
     *  used by the BI Audit Report screen to render breadcrumb + summary. */
    ResponseDTO<java.util.Map<String, Object>> getUserChecksheetMeta(Long userChecksheetId) throws CustomException;

    /** All audit assignments owned by the currently authenticated operator —
     *  the mobile app's "my today" list. Each row carries enough context
     *  (audit name, location label, status, progress) to render a card. */
    ResponseDTO<java.util.List<java.util.Map<String, Object>>> getMyAssignments() throws CustomException;

    /** Drill-down for one category — returns per-region and per-dealer failure
     *  rates so the National "What's Failing" panel can answer "click EV →
     *  who's worst at EV?". */
    ResponseDTO<java.util.Map<String, Object>> getCategoryDrill(Long auditId, String category, LocalDate startDate, LocalDate endDate) throws CustomException;

    /** Audit-report improvement overlay (PRD §4.6 + intervention scoring
     *  principle): given an audit UC, returns the per-question temporal
     *  chain (original → wave 1 by intervention X → wave 2 by intervention Y)
     *  plus original→current score deltas. Drives the audit-report screen's
     *  per-row chips and the page-header score chip. */
    java.util.Map<String, Object> getImprovementOverlay(Long userChecksheetId) throws CustomException;

    /** Intervention BI summary scoped by audit + (optional) region/dealer/location.
     *  Returns: activeCampaigns, p1CompletionRate, dealersWithCompletedP1,
     *  dealersNonCompliantP1, networkScoreDelta + networkScoreDeltaDealerCount,
     *  topCampaigns. Reads the same audit_signal CTE used by every other
     *  panel — guarantees the rollup matches. */
    java.util.Map<String, Object> getInterventionSummary(Long auditId, String level, Long scopeId) throws CustomException;
}
