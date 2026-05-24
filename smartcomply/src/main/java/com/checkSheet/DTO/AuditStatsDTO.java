package com.checkSheet.DTO;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Aggregated stats for the BI dashboards. Same conceptual shape at every drill
 * level (national / region / dealer / location); the service fills in the
 * pieces relevant to that scope.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditStatsDTO {

    /** scope identifier — "national" or e.g. "region:3", "dealer:1042", "location:5078". */
    private String scope;

    /** Human label, e.g. "National", "South Region", "Mahamaya Kia, MG Road, Ambikapur". */
    private String scopeLabel;

    /** Total locations in scope. */
    private Long totalLocations;

    /** Total audited locations (have at least one user_checksheet row). */
    private Long auditedLocations;

    /** Total never-audited locations. */
    private Long neverAudited;

    /** Aggregate audit count (inspections in scope). */
    private Long totalAudits;

    /** Band counts. */
    private Long greenCount;
    private Long amberCount;
    private Long redCount;

    /** Score average across audits in scope. */
    private Double avgScore;

    /** What's failing — descending list of {category, failurePct, total, fails}. */
    private List<Map<String, Object>> whatsFailing;

    /** Per-region (national view) or per-dealer (regional view) or per-location (dealer view) table rows. */
    private List<Map<String, Object>> table;

    /** Top failing checkpoints in scope. */
    private List<Map<String, Object>> topFailingCheckpoints;

    /** AI insight callouts (correlated failures). */
    private List<String> aiInsights;

    /** Red-band dealers in scope (national view): bottom-N by avg score. */
    private List<Map<String, Object>> redDealers;
}
