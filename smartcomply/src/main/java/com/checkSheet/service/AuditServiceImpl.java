package com.checkSheet.service;

import com.checkSheet.DTO.AuditAssignmentCreateDTO;
import com.checkSheet.DTO.AuditDTO;
import com.checkSheet.DTO.AuditAssignmentDTO;
import com.checkSheet.DTO.AuditStatsDTO;
import com.checkSheet.DTO.response.ResponseDTO;
import com.checkSheet.entity.Audit;
import com.checkSheet.entity.Inspection;
import com.checkSheet.entity.AuditeeLocation;
import com.checkSheet.entity.Checksheet;
import com.checkSheet.entity.User;
import com.checkSheet.entity.Inspection;
import com.checkSheet.exception.CustomException;
import com.checkSheet.repository.AuditAssignmentRepository;
import com.checkSheet.repository.AuditRepository;
import com.checkSheet.repository.AuditeeLocationRepository;
import com.checkSheet.repository.ChecksheetRepository;
import com.checkSheet.repository.UserChecksheetRepository;
import com.checkSheet.repository.UserRepository;
import java.util.Objects;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.PersistenceException;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class AuditServiceImpl implements AuditService {

    // ── Band thresholds (% of OK answers in a UC). ────────────────────────────
    // green ≥ 75%, amber ≥ 60% and < 75%, red < 60%.
    private static final int BAND_GREEN_PCT = 75;
    private static final int BAND_AMBER_PCT = 60;
    private static final int TOP_FAILING_CHECKPOINTS_LIMIT = 10;

    // ── UC status literals — kept here so a typo doesn't silently break BI. ────
    private static final String UC_APPROVED      = "APPROVED";
    private static final String UC_IN_PROGRESS   = "IN_PROGRESS";
    private static final String UC_SUBMITTED     = "SUBMITTED";
    private static final String UC_VALIDATED     = "VALIDATED";

    // ── Audit status literals (the campaign's own state). ─────────────────────
    private static final Set<String> VALID_AUDIT_STATUSES = Set.of("ACTIVE", "DRAFT", "CLOSED");

    @Autowired private com.checkSheet.config.TenantInsightsConfig tenantInsights;

    @Autowired private AuditRepository auditRepository;
    @Autowired private AuditAssignmentRepository auditAssignmentRepository;
    @Autowired private ChecksheetRepository checksheetRepository;
    @Autowired private AuditeeLocationRepository auditeeLocationRepository;
    @Autowired private UserChecksheetRepository userChecksheetRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private UtilityService utilityService;
    @Autowired private PermissionService permissionService;

    // ── Permission constants used by the auth gates below. AUDIT_VIEW
    //    distinguishes admins / data-validators / data-approvers (who manage
    //    audits) from plain operators (who fill them). All BI dashboard
    //    endpoints require it.
    private static final String PERM_AUDIT_VIEW = "AUDIT_VIEW";

    /** Throws FORBIDDEN if the current logged-in user lacks AUDIT_VIEW.
     *  Used to gate every read endpoint that exposes BI / admin data. */
    private void requireAuditView() throws CustomException {
        User u = utilityService.getCurrentLoggedInUser()
            .orElseThrow(() -> new CustomException("Not authenticated", HttpStatus.UNAUTHORIZED));
        if (!permissionService.hasPermission(u.getId(), PERM_AUDIT_VIEW)) {
            throw new CustomException(
                "You do not have permission to view audit data", HttpStatus.FORBIDDEN);
        }
    }

    @PersistenceContext
    private EntityManager em;

    // ─── Management ─────────────────────────────────────────────────────────

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseDTO<AuditDTO> createAudit(AuditDTO auditDTO) throws CustomException {
        // F2 — audit-management writes gated behind AUDIT_VIEW. Without this,
        // any authenticated user (operators, dealer principals) could POST
        // here and spawn audit campaigns. A stricter AUDIT_MANAGE permission
        // is a follow-up — for now AUDIT_VIEW at least excludes operators/DPs.
        requireAuditView();
        if (auditDTO == null || auditDTO.getName() == null || auditDTO.getName().isBlank()) {
            throw new CustomException("Please provide audit name", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        if (auditDTO.getChecksheetId() == null) {
            throw new CustomException("Please provide checksheetId", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        Optional<Audit> existing = auditRepository.findByNameAndDeletedAtIsNull(auditDTO.getName());
        if (existing.isPresent()) {
            return new ResponseDTO<>("Audit already exists", toBriefDTO(existing.get()));
        }
        Optional<Checksheet> checksheet = checksheetRepository.findById(auditDTO.getChecksheetId());
        if (checksheet.isEmpty()) {
            throw new CustomException("Invalid checksheetId", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        if (!checksheet.get().getStatus().getValue().equals(UC_APPROVED)) {
            throw new CustomException("Checksheet template must be APPROVED before being used in an audit",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
        String requestedStatus = auditDTO.getStatus() == null ? "ACTIVE" : auditDTO.getStatus();
        if (!VALID_AUDIT_STATUSES.contains(requestedStatus)) {
            throw new CustomException("Invalid audit status. Allowed: " + VALID_AUDIT_STATUSES,
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
        User loginUser = utilityService.getCurrentLoggedInUser()
                .orElseThrow(() -> new CustomException("User not authenticated", HttpStatus.UNAUTHORIZED));

        Audit a = new Audit();
        a.setName(auditDTO.getName());
        a.setChecksheet(checksheet.get());
        a.setStatus(requestedStatus);
        a.setStartDate(auditDTO.getStartDate());
        a.setEndDate(auditDTO.getEndDate());
        a.setCreatedBy(loginUser);
        a = auditRepository.save(a);
        return new ResponseDTO<>("Audit created", toBriefDTO(a));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseDTO<?> addAuditAssignments(Long auditId, List<AuditAssignmentCreateDTO> assignments) throws CustomException {
        // F2 — same gate as createAudit. Without this, any authenticated user
        // could attach assignments to arbitrary audits (and DoS the system or
        // backdoor inspections into someone else's campaign).
        requireAuditView();
        if (auditId == null) {
            throw new CustomException("Please provide auditId", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        if (assignments == null || assignments.isEmpty()) {
            throw new CustomException("Please provide assignments array", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        Audit audit = auditRepository.findById(auditId)
                .orElseThrow(() -> new CustomException("Invalid auditId", HttpStatus.UNPROCESSABLE_ENTITY));
        User loginUser = utilityService.getCurrentLoggedInUser()
                .orElseThrow(() -> new CustomException("User not authenticated", HttpStatus.UNAUTHORIZED));

        // Dedupe input by auditeeLocationId — duplicate entries in one call would
        // otherwise both pass the pre-flight check, and the unique constraint would
        // roll back the whole transaction.
        Set<Long> seen = new HashSet<>();
        int added = 0;
        for (AuditAssignmentCreateDTO entry : assignments) {
            if (entry == null || entry.getAuditeeLocationId() == null) {
                throw new CustomException("Each assignment requires auditeeLocationId", HttpStatus.UNPROCESSABLE_ENTITY);
            }
            Long auditeeLocationId = entry.getAuditeeLocationId();
            Long operatorUserId = entry.getOperatorUserId();    // DTO field, not entity getter

            if (!seen.add(auditeeLocationId)) {
                continue; // duplicate within same request — idempotent skip
            }
            if (auditAssignmentRepository.findByAuditIdAndAuditeeLocationIdAndDeletedAtIsNull(auditId, auditeeLocationId).isPresent()) {
                continue; // already exists from a prior call — idempotent skip
            }
            AuditeeLocation loc = auditeeLocationRepository.findById(auditeeLocationId)
                    .orElseThrow(() -> new CustomException("Invalid auditeeLocationId: " + auditeeLocationId, HttpStatus.UNPROCESSABLE_ENTITY));

            Inspection aa = new Inspection();
            aa.setKind("AUDIT");                    // V1.28 NOT NULL + CHECK
            aa.setStatus("ASSIGNED");               // V1.28 NOT NULL + CHECK
            aa.setAudit(audit);
            aa.setAuditeeLocation(loc);
            if (operatorUserId != null) {
                User operator = userRepository.findById(operatorUserId)
                        .orElseThrow(() -> new CustomException("Invalid operatorUserId: " + operatorUserId, HttpStatus.UNPROCESSABLE_ENTITY));
                aa.setOperatorUser(operator);
            }
            // F9 — capture DP at assignment-creation time. InterventionAssignmentServiceImpl
            // .instantiateForApprovedAudit copies this onto each intervention plan, and
            // InterventionAssignmentServiceImpl.acknowledge returns 403 when it's null.
            // Optional on input; assignments created without a DP will need to be patched
            // before their intervention plans can be acknowledged.
            Long dealerPrincipalUserId = entry.getDealerPrincipalUserId();
            if (dealerPrincipalUserId != null) {
                User dp = userRepository.findById(dealerPrincipalUserId)
                        .orElseThrow(() -> new CustomException("Invalid dealerPrincipalUserId: " + dealerPrincipalUserId, HttpStatus.UNPROCESSABLE_ENTITY));
                aa.setDealerPrincipalUser(dp);
            }
            aa.setCreatedBy(loginUser);
            try {
                auditAssignmentRepository.save(aa);
                auditAssignmentRepository.flush();
                added++;
            } catch (DataIntegrityViolationException e) {
                // Concurrent insert with the same (auditId, auditeeLocationId) — idempotent skip.
                log.debug("Skip duplicate assignment for audit {} location {}: {}", auditId, auditeeLocationId, e.getMessage());
            }
        }
        return new ResponseDTO<>(true, "Added " + added + " assignment(s) to audit");
    }

    @Override
    public ResponseDTO<List<AuditDTO>> listAudits() throws CustomException {
        requireAuditView();
        List<Audit> audits = auditRepository.findByDeletedAtIsNullOrderByCreatedAtDesc();
        List<AuditDTO> out = audits.stream().map(this::toDetailDTO).collect(Collectors.toList());
        return new ResponseDTO<>("Audits fetched", out);
    }

    @Override
    public ResponseDTO<AuditDTO> getAuditDetail(Long auditId) throws CustomException {
        requireAuditView();
        Audit audit = auditRepository.findById(auditId)
                .orElseThrow(() -> new CustomException("Invalid auditId", HttpStatus.UNPROCESSABLE_ENTITY));
        AuditDTO dto = toDetailDTO(audit);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("""
            SELECT ins.id, ins.auditee_location_id, a.name AS auditee_name, aloc.address,
                   c.name AS city, s.name AS state, r.name AS region,
                   ins.operator_user_id, u.username,
                   CASE WHEN ins.status = 'ASSIGNED' THEN NULL ELSE ins.id END     AS uc_id,
                   CASE WHEN ins.status = 'ASSIGNED' THEN NULL ELSE ins.status END AS uc_status
              FROM inspections ins
              JOIN auditee_locations aloc ON aloc.id = ins.auditee_location_id
              JOIN auditees a ON a.id = aloc.auditee_id
              LEFT JOIN cities c ON c.id = aloc.city_id
              LEFT JOIN states s ON s.id = c.state_id
              LEFT JOIN regions r ON r.id = s.region_id
              LEFT JOIN users u ON u.id = ins.operator_user_id
             WHERE ins.audit_id = :auditId AND ins.kind = 'AUDIT' AND ins.deleted_at IS NULL
             ORDER BY ins.id
            """).setParameter("auditId", auditId).getResultList();

        List<AuditAssignmentDTO> assignments = new ArrayList<>();
        for (Object[] r : rows) {
            assignments.add(AuditAssignmentDTO.builder()
                    .id(toLong(r[0]))
                    .auditId(auditId)
                    .auditeeLocationId(toLong(r[1]))
                    .auditeeName((String) r[2])
                    .locationAddress((String) r[3])
                    .city((String) r[4])
                    .state((String) r[5])
                    .region((String) r[6])
                    .operatorUserId(toLong(r[7]))
                    .operatorUsername((String) r[8])
                    .inspectionId(toLong(r[9]))
                    .userChecksheetStatus(r[10] == null ? "NOT_STARTED" : (String) r[10])
                    .build());
        }
        dto.setAssignments(assignments);
        return new ResponseDTO<>("Audit detail", dto);
    }

    // ─── Stats (BI) ─────────────────────────────────────────────────────────

    @Override
    public ResponseDTO<AuditStatsDTO> getNationalStats(Long auditId, LocalDate startDate, LocalDate endDate) throws CustomException {
        requireAuditView();
        return new ResponseDTO<>("National stats", buildStats(auditId, "national", null, startDate, endDate));
    }

    @Override
    public ResponseDTO<AuditStatsDTO> getRegionStats(Long auditId, Long regionId, LocalDate startDate, LocalDate endDate) throws CustomException {
        requireAuditView();
        return new ResponseDTO<>("Region stats", buildStats(auditId, "region", regionId, startDate, endDate));
    }

    @Override
    public ResponseDTO<AuditStatsDTO> getDealerStats(Long auditId, Long auditeeId, LocalDate startDate, LocalDate endDate) throws CustomException {
        requireAuditView();
        return new ResponseDTO<>("Dealer stats", buildStats(auditId, "dealer", auditeeId, startDate, endDate));
    }

    @Override
    public ResponseDTO<AuditStatsDTO> getLocationStats(Long auditId, Long locationId, LocalDate startDate, LocalDate endDate) throws CustomException {
        requireAuditView();
        return new ResponseDTO<>("Location stats", buildStats(auditId, "location", locationId, startDate, endDate));
    }

    /**
     * Drill-down for a single category at the National scope. Returns:
     *   { category, regions: [...], dealers: [...] }
     *
     * Both lists are ranked by failure rate DESC (worst first), restricted to
     * rows that actually have at least one NOT-OK answer for the category, and
     * sourced from the same in-scope CTE every other BI panel uses (so counts
     * line up). Dealers list is rolled up across each dealership's locations.
     *
     * Used by the National dashboard's "What's Failing" panel — clicking a
     * category opens a drill panel that answers "who's worst at this?".
     */
    @Override
    @SuppressWarnings("unchecked")
    public ResponseDTO<java.util.Map<String, Object>> getCategoryDrill(Long auditId, String category, LocalDate startDate, LocalDate endDate) throws CustomException {
        requireAuditView();
        if (auditId == null) {
            throw new CustomException("auditId required", org.springframework.http.HttpStatus.BAD_REQUEST);
        }
        if (category == null || category.isBlank()) {
            throw new CustomException("category required", org.springframework.http.HttpStatus.BAD_REQUEST);
        }
        String dateFilter = "";
        if (startDate != null && endDate != null) {
            dateFilter = " AND DATE(ins.submitted_at) BETWEEN :startDate AND :endDate";
        }
        String cte = scopeCte("national", dateFilter);

        // Per-region — LOCATION-level fail count for this category.
        // The chip reads "X / Y failing" where X = locations with ≥1
        // failure in this category, Y = locations audited in this
        // region. % is the location-level failure rate. Sort:
        // worst-first by failure rate.
        //
        // scopeCte() already produces `WITH audit_signal AS (...)` —
        // can't open a second WITH, so the per-location roll-up is an
        // inline subquery instead.
        String regionSql = cte + """
            SELECT region_id,
                   region_name,
                   100.0 * SUM(CASE WHEN has_failure THEN 1 ELSE 0 END) / NULLIF(COUNT(*),0) AS failure_pct,
                   SUM(CASE WHEN has_failure THEN 1 ELSE 0 END) AS fails,
                   COUNT(*) AS total
              FROM (
                SELECT sig.region_id,
                       sig.region_name,
                       sig.location_id,
                       BOOL_OR(uca.judgement != 1) AS has_failure
                  FROM audit_signal sig
                  JOIN user_checksheet_answers uca ON uca.inspection_id = sig.uc_id
                  JOIN chks_question_results qr ON qr.id = uca.chks_question_result_id
                  JOIN chks_questions q ON q.id = qr.chks_question_id
                  JOIN chks_header_data elt ON elt.id = q.chks_header_data_id
                  JOIN chks_header_data cat ON cat.id = elt.chks_header_data_id
                 WHERE LOWER(cat.name) = LOWER(:category)
                   AND sig.region_id IS NOT NULL
                 GROUP BY sig.region_id, sig.region_name, sig.location_id
              ) per_location
             GROUP BY region_id, region_name
            HAVING SUM(CASE WHEN has_failure THEN 1 ELSE 0 END) > 0
             ORDER BY failure_pct DESC, fails DESC
             LIMIT 20
            """;
        var regionQ = em.createNativeQuery(regionSql)
            .setParameter("auditId", auditId)
            .setParameter("category", category);
        if (startDate != null && endDate != null) {
            regionQ.setParameter("startDate", startDate);
            regionQ.setParameter("endDate", endDate);
        }
        java.util.List<Object[]> rRows = regionQ.getResultList();
        java.util.List<java.util.Map<String, Object>> regions = new java.util.ArrayList<>();
        for (Object[] r : rRows) {
            java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("regionId", toLong(r[0]));
            m.put("regionName", r[1]);
            m.put("failurePct", r[2] == null ? 0 : ((Number) r[2]).doubleValue());
            m.put("fails", toLong(r[3]));
            m.put("total", toLong(r[4]));
            regions.add(m);
        }

        // Per-dealer COMPLIANCE rate for this category, worst-first
        // (lowest compliance first). QUESTION-level — counts checkpoint
        // answers that are OK over all checkpoint answers for this
        // category across the dealer's locations. A dealer with 3
        // category checkpoints where all 3 failed shows "0/3 = 0%".
        String dealerSql = cte + """
            SELECT sig.auditee_id,
                   sig.auditee_name,
                   COUNT(DISTINCT sig.location_id) AS location_count,
                   (ARRAY_AGG(sig.region_name ORDER BY sig.location_id))[1] AS region_name,
                   (ARRAY_AGG(sig.city_name   ORDER BY sig.location_id))[1] AS city_name,
                   100.0 * SUM(CASE WHEN uca.judgement = 1 THEN 1 ELSE 0 END) / NULLIF(COUNT(*),0) AS compliance_pct,
                   SUM(CASE WHEN uca.judgement = 1 THEN 1 ELSE 0 END) AS compliant,
                   COUNT(*) AS total
              FROM audit_signal sig
              JOIN user_checksheet_answers uca ON uca.inspection_id = sig.uc_id
              JOIN chks_question_results qr ON qr.id = uca.chks_question_result_id
              JOIN chks_questions q ON q.id = qr.chks_question_id
              JOIN chks_header_data elt ON elt.id = q.chks_header_data_id
              JOIN chks_header_data cat ON cat.id = elt.chks_header_data_id
             WHERE LOWER(cat.name) = LOWER(:category)
             GROUP BY sig.auditee_id, sig.auditee_name
            HAVING SUM(CASE WHEN uca.judgement != 1 THEN 1 ELSE 0 END) > 0
             ORDER BY compliance_pct ASC, total DESC
             LIMIT 15
            """;
        var dealerQ = em.createNativeQuery(dealerSql)
            .setParameter("auditId", auditId)
            .setParameter("category", category);
        if (startDate != null && endDate != null) {
            dealerQ.setParameter("startDate", startDate);
            dealerQ.setParameter("endDate", endDate);
        }
        java.util.List<Object[]> dRows = dealerQ.getResultList();
        java.util.List<java.util.Map<String, Object>> dealers = new java.util.ArrayList<>();
        for (Object[] r : dRows) {
            java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("auditeeId", toLong(r[0]));
            m.put("dealer", r[1]);
            m.put("locationCount", toLong(r[2]));
            m.put("region", r[3]);
            m.put("city", r[4]);
            double compliancePct = r[5] == null ? 0 : ((Number) r[5]).doubleValue();
            long compliant = toLong(r[6]);
            long total = toLong(r[7]);
            m.put("compliancePct", compliancePct);
            m.put("compliant", compliant);
            m.put("total", total);
            // Legacy failure-first keys until the FE finishes migrating.
            m.put("failurePct", 100.0 - compliancePct);
            m.put("fails", total - compliant);
            dealers.add(m);
        }

        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("category", category);
        out.put("regions", regions);
        out.put("dealers", dealers);
        return new ResponseDTO<>("Category drill", out);
    }

    @Override
    @SuppressWarnings("unchecked")
    public ResponseDTO<java.util.Map<String, Object>> getUserChecksheetMeta(Long userChecksheetId) throws CustomException {
        // Authorization: allow either (a) any user with AUDIT_VIEW (admins,
        // dept heads, validators, approvers — they read all audits as part
        // of their job), or (b) the operator who owns this specific UC
        // (the mobile app calls this for its own audit). Anything else gets
        // 403. Without this check any operator could read any other operator's
        // audit by guessing the userChecksheetId.
        User caller = utilityService.getCurrentLoggedInUser()
            .orElseThrow(() -> new CustomException("Not authenticated", HttpStatus.UNAUTHORIZED));
        boolean hasAuditView = permissionService.hasPermission(caller.getId(), PERM_AUDIT_VIEW);
        if (!hasAuditView) {
            Inspection uc = userChecksheetRepository.findById(userChecksheetId)
                .orElseThrow(() -> new CustomException("user_checksheet not found: " + userChecksheetId, HttpStatus.NOT_FOUND));
            boolean isOperator = uc.getOperatorUser() != null
                    && Objects.equals(uc.getOperatorUser().getId(), caller.getId());
            // Dealer Principal scope: every Inspection carries the dealer
            // principal user-id at write time (V1.30 + UC entity). A DP viewing
            // an audit for *their own* dealership is fully expected — this is
            // the read path behind their "/my-dealership" page.
            boolean isDealerPrincipal = uc.getDealerPrincipalUser() != null
                    && Objects.equals(uc.getDealerPrincipalUser().getId(), caller.getId());
            if (!isOperator && !isDealerPrincipal) {
                throw new CustomException(
                    "You do not have access to this user_checksheet", HttpStatus.FORBIDDEN);
            }
        }

        // One join chain: UC → audit_assignment → auditee_locations → cities → states → regions.
        //
        // OK / NOT-OK counts are computed at the QUESTION level (DISTINCT chks_question_id),
        // not the answer-row level. The audit-report body groups its checkpoint cards
        // by question, so a question with 2 NOT-OK answer rows still renders as 1 NOT-OK
        // card. Counting answer rows here was producing summary numbers (e.g. 4 failed)
        // that disagreed with what the user could see in the body (3 cards).
        //
        // Score still uses raw answer rows so the % matches the BI dashboards' band
        // calculation (which is also answer-level).
        // pct_ok / ok_count / not_ok_count use the LATEST-effective answer per
        // (chks_question_id) across this audit-kind UC AND any APPROVED
        // intervention-kind inspections at the same location for the same audit.
        // Aligns the audit-report header with the post-intervention BI rollup
        // ("currentScore" in /improvement-overlay).
        String sql = """
        SELECT uc.id,
               uc.started_at,
               uc.submitted_at,
               aloc.id   AS location_id,
               aloc.address AS location_address,
               c.name    AS city_name,
               a.id      AS auditee_id,
               a.name    AS auditee_name,
               r.id      AS region_id,
               r.name    AS region_name,
               eff.pct_ok,
               eff.ok_count,
               eff.not_ok_count
          FROM inspections uc
          JOIN auditee_locations aloc ON aloc.id = uc.auditee_location_id
          LEFT JOIN cities c ON c.id = aloc.city_id
          LEFT JOIN auditees a ON a.id = aloc.auditee_id
          LEFT JOIN states  s ON s.id = c.state_id
          LEFT JOIN regions r ON r.id = s.region_id
          LEFT JOIN LATERAL (
            WITH latest_per_q AS (
              SELECT DISTINCT ON (uca.chks_question_id)
                     uca.chks_question_id, uca.judgement
                FROM inspections ins2
                LEFT JOIN interventions iv ON iv.id = ins2.intervention_id
                JOIN user_checksheet_answers uca ON uca.inspection_id = ins2.id
                                                AND uca.deleted_at IS NULL
                                                AND uca.chks_question_id IS NOT NULL
               WHERE ins2.deleted_at IS NULL
                 AND ins2.auditee_location_id = uc.auditee_location_id
                 AND ((ins2.kind = 'AUDIT'        AND ins2.audit_id = uc.audit_id AND ins2.id = uc.id)
                   OR (ins2.kind = 'INTERVENTION' AND iv.audit_id   = uc.audit_id AND ins2.status = 'APPROVED'))
               ORDER BY uca.chks_question_id, ins2.submitted_at DESC NULLS LAST, uca.id DESC
            )
            SELECT 100.0 * SUM(CASE WHEN judgement=1 THEN 1 ELSE 0 END) / NULLIF(COUNT(*),0) AS pct_ok,
                   COUNT(*) FILTER (WHERE judgement = 1) AS ok_count,
                   COUNT(*) FILTER (WHERE judgement IS NOT NULL AND judgement <> 1) AS not_ok_count
              FROM latest_per_q
          ) eff ON TRUE
         WHERE uc.id = :ucId
        """;
        var query = em.createNativeQuery(sql);
        query.setParameter("ucId", userChecksheetId);
        java.util.List<Object[]> rows = query.getResultList();
        java.util.Map<String, Object> meta = new java.util.HashMap<>();
        if (rows.isEmpty()) {
            throw new CustomException("user_checksheet not found: " + userChecksheetId, org.springframework.http.HttpStatus.NOT_FOUND);
        }
        Object[] r = rows.get(0);
        String city = (String) r[5];
        String dealerName = (String) r[7];
        // Short location label — "<dealer> · <city>". The full street address
        // (`aloc.address`) is too long for the breadcrumb / report subtitle and
        // visually overwhelms the rest of the header.
        String label = dealerName != null && city != null
            ? dealerName + " · " + city
            : (dealerName != null ? dealerName : (city != null ? city : "Location " + r[3]));
        meta.put("userChecksheetId", r[0]);
        meta.put("startedAt", r[1]);
        meta.put("submittedAt", r[2]);
        meta.put("locationId", r[3]);
        meta.put("locationLabel", label);
        meta.put("dealerId", r[6]);
        meta.put("dealerName", r[7]);
        meta.put("regionId", r[8]);
        meta.put("regionName", r[9]);
        meta.put("score", r[10]);
        meta.put("okCount", r[11]);
        meta.put("notOkCount", r[12]);
        return new ResponseDTO<>("user_checksheet meta", meta);
    }

    /** Audit-report overlay: returns the per-question temporal chain across
     *  every intervention re-inspection wave on this audit_assignment, plus
     *  header-level original→current score deltas.
     *
     *  Response shape (matches the audit-report frontend's stub):
     *  <pre>
     *  {
     *    "userChecksheetId": 81,
     *    "originalScore": 55.56,
     *    "currentScore":  64.81,
     *    "scoreDelta":     9.25,
     *    "questionFlags": {
     *      "<chksQuestionId>": [{ planId, campaignName, priority, planStatus }]
     *    },
     *    "reAuditAnswers": {
     *      "<chksQuestionId>": [{
     *        reAuditAnswerId, reAuditId, auditorName, judgement,
     *        comment, answeredAt, photoUrls, interventionName, interventionId
     *      }]
     *    },
     *    "headerSnapshots": [
     *      { interventionId, interventionName, contributedDelta, completedAt }
     *    ]
     *  }
     *  </pre>
     */
    @Override
    @SuppressWarnings("unchecked")
    public java.util.Map<String, Object> getImprovementOverlay(Long userChecksheetId) throws CustomException {
        if (userChecksheetId == null) {
            throw new CustomException("userChecksheetId required", HttpStatus.BAD_REQUEST);
        }
        Inspection auditUc = userChecksheetRepository.findById(userChecksheetId)
            .orElseThrow(() -> new CustomException("Inspection not found", HttpStatus.NOT_FOUND));
        // Same visibility gate as the main report content: senior with
        // AUDIT_VIEW, OR the operator who filled this UC, OR the Dealer
        // Principal of this UC's dealership. Replaces the previous
        // AUDIT_VIEW-only gate which wrongly blocked operators and DPs
        // from viewing their own audit's intervention overlay.
        User caller = utilityService.getCurrentLoggedInUser()
            .orElseThrow(() -> new CustomException("Not authenticated", HttpStatus.UNAUTHORIZED));
        boolean hasAuditView = permissionService.hasPermission(caller.getId(), PERM_AUDIT_VIEW);
        boolean isOperator = auditUc.getOperatorUser() != null
                && Objects.equals(auditUc.getOperatorUser().getId(), caller.getId());
        boolean isDealerPrincipal = auditUc.getDealerPrincipalUser() != null
                && Objects.equals(auditUc.getDealerPrincipalUser().getId(), caller.getId());
        if (!hasAuditView && !isOperator && !isDealerPrincipal) {
            throw new CustomException(
                "You do not have access to this user_checksheet", HttpStatus.FORBIDDEN);
        }
        if (!"AUDIT".equals(auditUc.getKind())) {
            // Re-inspection-wave inspection has no overlay of its own.
            throw new CustomException("Inspection is a re-inspection wave, no overlay available", HttpStatus.BAD_REQUEST);
        }
        // Post-V1.28: aaId == ucId after collapse. Both refer to the same row.
        Long auditAssignmentId = auditUc.getId();

        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("userChecksheetId", userChecksheetId);

        // Original score from the audit UC alone
        Object origScore = em.createNativeQuery("""
            SELECT 100.0 * SUM(CASE WHEN judgement = 1 THEN 1 ELSE 0 END) / NULLIF(COUNT(*),0)
              FROM user_checksheet_answers WHERE inspection_id = :ucId AND deleted_at IS NULL
            """).setParameter("ucId", userChecksheetId).getSingleResult();
        out.put("originalScore", origScore);

        // Current score using the latest-per-(chks_question_result_id) rollup
        // across this audit-kind inspection AND any intervention-kind inspections
        // at the same location belonging to interventions of the same audit_id.
        Object currScore = em.createNativeQuery("""
            SELECT 100.0 * SUM(CASE WHEN latest.judgement = 1 THEN 1 ELSE 0 END) / NULLIF(COUNT(*),0)
              FROM (
                SELECT DISTINCT ON (qrid) judgement
                  FROM (
                    SELECT uca.chks_question_result_id AS qrid,
                           uca.judgement,
                           ins2.submitted_at AS sub_at,
                           uca.id AS uca_id
                      FROM inspections base
                      JOIN inspections ins2 ON ins2.deleted_at IS NULL
                                            AND ins2.status = 'APPROVED'
                                            AND ins2.auditee_location_id = base.auditee_location_id
                      LEFT JOIN interventions iv ON iv.id = ins2.intervention_id
                      JOIN user_checksheet_answers uca ON uca.inspection_id = ins2.id
                                                      AND uca.deleted_at IS NULL
                                                      AND uca.chks_question_result_id IS NOT NULL
                     WHERE base.id = :aaId AND base.kind = 'AUDIT'
                       AND (
                            (ins2.kind = 'AUDIT'        AND ins2.audit_id = base.audit_id)
                         OR (ins2.kind = 'INTERVENTION' AND iv.audit_id   = base.audit_id)
                       )
                  ) all_ans
                 ORDER BY qrid, sub_at DESC NULLS LAST, uca_id DESC
              ) latest
            """)
            .setParameter("aaId", auditAssignmentId)
            .getSingleResult();
        out.put("currentScore", currScore);
        out.put("scoreDelta",
            (origScore == null || currScore == null) ? null
                : ((java.math.BigDecimal) currScore).subtract((java.math.BigDecimal) origScore));

        // ── questionFlags ─────────────────────────────────────────────────────
        // For every intervention targeting THIS audit UC, list the questions
        // that intervention's re-inspection wave covers — keyed by
        // chks_question_id, value = list of { interventionId, campaignName,
        // priority, interventionStatus }. Same question may flag from
        // multiple interventions.
        //
        // C3 fix: SQL emits intervention id/status (iv.id, iv.status). The
        // map previously serialized them as planId/planStatus — those keys
        // lied: the values are the intervention CAMPAIGN id/status, NOT a
        // plan-inspection id/status. FE click-throughs that fed planId to
        // plan endpoints would 404 / hit the wrong entity. Renamed to
        // interventionId/interventionStatus so the key names match the
        // columns. CONTRACT CHANGE — Angular audit-report consumer needs a
        // matching rename.
        java.util.List<Object[]> flagRows = em.createNativeQuery("""
            SELECT DISTINCT iaq.chks_question_id, iv.id, iv.name, iv.priority, iv.status
              FROM inspections base
              JOIN intervention_assignment_targets iat ON iat.inspection_id = base.id
                                                       AND iat.deleted_at IS NULL
              JOIN interventions iv ON iv.id = iat.intervention_id AND iv.deleted_at IS NULL
              JOIN inspections ri ON ri.intervention_id = iv.id
                                  AND ri.kind = 'INTERVENTION'
                                  AND ri.auditee_location_id = base.auditee_location_id
                                  AND ri.deleted_at IS NULL
              JOIN intervention_assignment_questions iaq ON iaq.inspection_id = ri.id
                                                         AND iaq.deleted_at IS NULL
             WHERE base.id = :ucId AND base.kind = 'AUDIT'
            """).setParameter("ucId", userChecksheetId).getResultList();
        java.util.Map<String, java.util.List<java.util.Map<String, Object>>> questionFlags = new java.util.LinkedHashMap<>();
        for (Object[] fr : flagRows) {
            String qid = String.valueOf(fr[0]);
            java.util.Map<String, Object> flag = new java.util.LinkedHashMap<>();
            flag.put("interventionId", fr[1]);
            flag.put("campaignName", fr[2]);
            flag.put("priority", fr[3]);
            flag.put("interventionStatus", fr[4]);
            questionFlags.computeIfAbsent(qid, k -> new java.util.ArrayList<>()).add(flag);
        }
        out.put("questionFlags", questionFlags);

        // ── reAuditAnswers ────────────────────────────────────────────────────
        // For each tracked question, the temporal chain of re-inspection
        // answers across every intervention wave at this UC's location.
        // Joined with judgement remarks + photos so the audit-report card can
        // render the post-intervention block under the original auditor row.
        java.util.List<Object[]> raRows = em.createNativeQuery("""
            SELECT iaq.chks_question_id,
                   uca.id              AS uca_id,
                   uca.judgement,
                   COALESCE(j.remarks, '') AS remarks,
                   COALESCE(ri.submitted_at, uca.answered_at) AS answered_at,
                   ri.id               AS ri_id,
                   iv.id               AS intv_id,
                   iv.name             AS intv_name,
                   COALESCE(u.first_name, '') AS first_name,
                   COALESCE(u.last_name, '')  AS last_name
              FROM inspections base
              JOIN intervention_assignment_targets iat ON iat.inspection_id = base.id
                                                       AND iat.deleted_at IS NULL
              JOIN interventions iv ON iv.id = iat.intervention_id AND iv.deleted_at IS NULL
              JOIN inspections ri ON ri.intervention_id = iv.id
                                  AND ri.kind = 'INTERVENTION'
                                  AND ri.auditee_location_id = base.auditee_location_id
                                  AND ri.deleted_at IS NULL
              JOIN intervention_assignment_questions iaq ON iaq.inspection_id = ri.id
                                                         AND iaq.deleted_at IS NULL
              JOIN user_checksheet_answers uca ON uca.inspection_id = ri.id
                                               AND uca.chks_question_id = iaq.chks_question_id
                                               AND uca.deleted_at IS NULL
              LEFT JOIN usr_chksheet_ans_judgements j ON j.inspection_id = ri.id
                                                     AND j.chks_question_id = iaq.chks_question_id
                                                     AND j.deleted_at IS NULL
              LEFT JOIN users u ON u.id = ri.operator_user_id
             WHERE base.id = :ucId AND base.kind = 'AUDIT'
             ORDER BY iaq.chks_question_id, answered_at ASC, uca.id ASC
            """).setParameter("ucId", userChecksheetId).getResultList();

        // Collect uca ids so we can fetch photos in one query
        java.util.Set<Long> ucaIds = new java.util.LinkedHashSet<>();
        for (Object[] r2 : raRows) ucaIds.add(((Number) r2[1]).longValue());
        java.util.Map<Long, java.util.List<String>> photosByUca = new java.util.HashMap<>();
        if (!ucaIds.isEmpty()) {
            java.util.List<Object[]> photoRows = em.createNativeQuery("""
                SELECT user_checksheet_answer_id, path
                  FROM user_checksheet_answer_files
                 WHERE user_checksheet_answer_id IN (:ids) AND deleted_at IS NULL AND path IS NOT NULL
                 ORDER BY user_checksheet_answer_id, id
                """).setParameter("ids", ucaIds).getResultList();
            for (Object[] pr : photoRows) {
                Long ucaId = ((Number) pr[0]).longValue();
                String path = (String) pr[1];
                if (path == null || path.isBlank()) continue;
                photosByUca.computeIfAbsent(ucaId, k -> new java.util.ArrayList<>())
                    .add(com.checkSheet.helper.FileStorageUtil.getFileURL(path));
            }
        }

        java.util.Map<String, java.util.List<java.util.Map<String, Object>>> reAuditAnswers = new java.util.LinkedHashMap<>();
        for (Object[] r3 : raRows) {
            String qid = String.valueOf(r3[0]);
            Long ucaId = ((Number) r3[1]).longValue();
            Number judg = (Number) r3[2];
            String remarks = (String) r3[3];
            Object answeredAt = r3[4];
            Long riId = ((Number) r3[5]).longValue();
            Long intvId = ((Number) r3[6]).longValue();
            String intvName = (String) r3[7];
            String firstName = (String) r3[8];
            String lastName = (String) r3[9];
            String auditor = (firstName + " " + lastName).trim();

            java.util.Map<String, Object> ra = new java.util.LinkedHashMap<>();
            ra.put("reAuditAnswerId", ucaId);
            ra.put("reAuditId", riId);
            ra.put("interventionId", intvId);
            ra.put("interventionName", intvName);
            ra.put("auditorName", auditor);
            ra.put("judgement", judg != null ? judg.intValue() : null);
            ra.put("comment", remarks == null ? "" : remarks);
            ra.put("answeredAt", answeredAt);
            ra.put("photoUrls", photosByUca.getOrDefault(ucaId, java.util.Collections.emptyList()));
            reAuditAnswers.computeIfAbsent(qid, k -> new java.util.ArrayList<>()).add(ra);
        }
        out.put("reAuditAnswers", reAuditAnswers);

        // ── headerSnapshots ───────────────────────────────────────────────────
        // Per-intervention completion summary — one row per intervention that
        // has at least one APPROVED re-inspection at this UC's location.
        java.util.List<Object[]> snapRows = em.createNativeQuery("""
            SELECT iv.id, iv.name, MAX(ri.submitted_at) AS completed_at, COUNT(DISTINCT ri.id) AS waves
              FROM inspections base
              JOIN intervention_assignment_targets iat ON iat.inspection_id = base.id
                                                       AND iat.deleted_at IS NULL
              JOIN interventions iv ON iv.id = iat.intervention_id AND iv.deleted_at IS NULL
              JOIN inspections ri ON ri.intervention_id = iv.id
                                  AND ri.kind = 'INTERVENTION'
                                  AND ri.auditee_location_id = base.auditee_location_id
                                  AND ri.deleted_at IS NULL
                                  AND ri.status = 'APPROVED'
             WHERE base.id = :ucId AND base.kind = 'AUDIT'
             GROUP BY iv.id, iv.name
             ORDER BY completed_at DESC
            """).setParameter("ucId", userChecksheetId).getResultList();
        java.util.List<java.util.Map<String, Object>> headerSnapshots = new java.util.ArrayList<>();
        for (Object[] sr : snapRows) {
            java.util.Map<String, Object> snap = new java.util.LinkedHashMap<>();
            snap.put("interventionId", sr[0]);
            snap.put("interventionName", sr[1]);
            snap.put("completedAt", sr[2]);
            snap.put("wavesCount", sr[3]);
            headerSnapshots.add(snap);
        }
        out.put("headerSnapshots", headerSnapshots);
        return out;
    }

    /** Intervention BI summary — reads from {@code audit_signal} so the
     *  numbers stay consistent with the rest of the BI. The "current" score
     *  used to compute network delta is the rolled-up post-intervention
     *  score (per the scoring principle); the original is the same UC's
     *  pre-intervention score. */
    @Override
    @SuppressWarnings("unchecked")
    public java.util.Map<String, Object> getInterventionSummary(Long auditId, String level, Long scopeId) throws CustomException {
        requireAuditView();
        if (auditId == null) {
            throw new CustomException("auditId required", HttpStatus.BAD_REQUEST);
        }
        String levelKey = (level == null || level.isBlank()) ? "national" : level;
        String cte = scopeCte(levelKey);

        // active interventions on this audit
        Number activeCount = (Number) em.createNativeQuery("""
            SELECT COUNT(*) FROM interventions
             WHERE audit_id = :auditId AND status='ACTIVE' AND deleted_at IS NULL
            """).setParameter("auditId", auditId).getSingleResult();

        // P1 completion rate across all P1 INTERVENTION-kind inspections in scope
        // (filter by audit_signal's location). COMPLETED is the renamed APPROVED.
        // NON_COMPLIANT is no longer a stored state in V1.28 — counts as 0 here.
        jakarta.persistence.Query p1Q = em.createNativeQuery(cte + """
            SELECT
              COUNT(DISTINCT ia.id) AS total_p1,
              COUNT(DISTINCT ia.id) FILTER (WHERE ia.status = 'APPROVED') AS completed_p1,
              COUNT(DISTINCT CASE WHEN ia.status = 'APPROVED' THEN sig.auditee_id END) AS dealers_with_completed_p1,
              0 AS dealers_noncompliant_p1
            FROM inspections ia
            JOIN interventions iv ON iv.id = ia.intervention_id AND iv.audit_id = :auditId
            JOIN audit_signal sig ON sig.location_id = ia.auditee_location_id
            WHERE ia.kind = 'INTERVENTION'
              AND ia.priority = 'P1'
              AND ia.deleted_at IS NULL
            """);
        bindAuditAndScope(p1Q, auditId, levelKey, scopeId);
        Object[] p1 = (Object[]) p1Q.getSingleResult();

        long totalP1     = p1[0] == null ? 0L : ((Number) p1[0]).longValue();
        long completedP1 = p1[1] == null ? 0L : ((Number) p1[1]).longValue();
        long dealersDone = p1[2] == null ? 0L : ((Number) p1[2]).longValue();
        long dealersNc   = p1[3] == null ? 0L : ((Number) p1[3]).longValue();
        int p1Pct = totalP1 == 0 ? 0 : (int) Math.round(100.0 * completedP1 / totalP1);

        // network delta: per dealer, current pct_ok (post-intervention,
        // already in audit_signal) vs that dealer's "original" pct_ok
        // computed from audit-only answers. Avg of (current - original) per
        // dealer where any movement happened.
        jakarta.persistence.Query netQ = em.createNativeQuery(cte + """
            ,
            original AS (
              SELECT sig.auditee_id,
                     AVG(orig.pct_ok) AS orig_pct
                FROM audit_signal sig
                JOIN LATERAL (
                  SELECT 100.0 * SUM(CASE WHEN uca.judgement = 1 THEN 1 ELSE 0 END) / NULLIF(COUNT(*),0) AS pct_ok
                    FROM user_checksheet_answers uca
                   WHERE uca.inspection_id = sig.uc_id
                     AND uca.deleted_at IS NULL
                ) orig ON TRUE
               GROUP BY sig.auditee_id
            ),
            current_per_dealer AS (
              SELECT auditee_id, AVG(pct_ok) AS curr_pct FROM audit_signal GROUP BY auditee_id
            )
            SELECT
              ROUND(CAST(AVG(c.curr_pct - o.orig_pct) AS numeric), 2),
              COUNT(*) FILTER (WHERE c.curr_pct - o.orig_pct > 0)
            FROM current_per_dealer c JOIN original o ON o.auditee_id = c.auditee_id
            WHERE c.curr_pct IS NOT NULL AND o.orig_pct IS NOT NULL
            """);
        bindAuditAndScope(netQ, auditId, levelKey, scopeId);
        Object[] netDelta = (Object[]) netQ.getSingleResult();

        Object netAvg = netDelta == null ? null : netDelta[0];
        long netCount = (netDelta == null || netDelta[1] == null) ? 0L : ((Number) netDelta[1]).longValue();

        jakarta.persistence.Query topQ = em.createNativeQuery(cte + """
            SELECT i.id, i.name, ia.priority,
                   COUNT(ia.id) AS plan_count,
                   COUNT(ia.id) FILTER (WHERE ia.status='APPROVED') AS completed_count
              FROM interventions i
              JOIN inspections ia ON ia.kind = 'INTERVENTION'
                                  AND ia.intervention_id = i.id
                                  AND ia.deleted_at IS NULL
              JOIN audit_signal sig ON sig.location_id = ia.auditee_location_id
             WHERE i.audit_id = :auditId AND i.deleted_at IS NULL AND i.status='ACTIVE'
             GROUP BY i.id, i.name, ia.priority
             ORDER BY plan_count DESC
             LIMIT 5
            """);
        bindAuditAndScope(topQ, auditId, levelKey, scopeId);
        java.util.List<Object[]> topRows = topQ.getResultList();
        java.util.List<java.util.Map<String, Object>> topCampaigns = new java.util.ArrayList<>();
        for (Object[] r : topRows) {
            java.util.Map<String, Object> e = new java.util.LinkedHashMap<>();
            e.put("id", r[0]);
            e.put("name", r[1]);
            e.put("priority", r[2]);
            e.put("planCount", r[3]);
            e.put("planCompletedCount", r[4]);
            topCampaigns.add(e);
        }

        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("activeCampaigns",            activeCount);
        out.put("activeInterventions",        activeCount); // alias
        out.put("totalAssignments",           totalP1);
        out.put("totalPlans",                 totalP1); // alias
        out.put("p1CompletionRate",           p1Pct);
        out.put("dealersWithCompletedP1",     dealersDone);
        out.put("dealersNonCompliantP1",      dealersNc);
        out.put("networkScoreDelta",          netAvg);
        out.put("networkScoreDeltaDealerCount", netCount);
        out.put("topCampaigns",               topCampaigns);
        out.put("topInterventions",           topCampaigns); // alias
        out.put("redDealersWithPlan",         0); // not yet computed; see follow-up
        out.put("redDealersWithoutPlan",      0);
        return out;
    }

    /** Bind auditId + (when applicable) the scope-level filter parameter
     *  into a query whose SQL was prefixed with {@code scopeCte(level)}.
     *  Centralised so callers don't have to repeat the level→param-name
     *  mapping or accidentally over-bind on national queries. */
    private void bindAuditAndScope(jakarta.persistence.Query q, Long auditId, String level, Long scopeId) {
        q.setParameter("auditId", auditId);
        switch (level) {
            case "region"   -> q.setParameter("regionId",   scopeId == null ? -1L : scopeId);
            case "dealer"   -> q.setParameter("auditeeId",  scopeId == null ? -1L : scopeId);
            case "location" -> q.setParameter("locationId", scopeId == null ? -1L : scopeId);
            default         -> { /* national — auditId already bound above */ }
        }
    }

    /** Scope-id param name for binding into {@code scopeCte()} queries.
     *  Kept for callers that don't yet use {@link #bindAuditAndScope}. */
    private String scopeIdParamName(String level) {
        return switch (level) {
            case "region"   -> "regionId";
            case "dealer"   -> "auditeeId";
            case "location" -> "locationId";
            default         -> "auditId"; // unused for national, but bind something
        };
    }

    @Override
    @SuppressWarnings("unchecked")
    public ResponseDTO<java.util.List<java.util.Map<String, Object>>> getMyAssignments() throws CustomException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null) {
            throw new CustomException("Not authenticated", HttpStatus.UNAUTHORIZED);
        }
        String username = auth.getName();
        User operator = userRepository.findByUsernameIgnoreCase(username)
            .orElseThrow(() -> new CustomException("User not found: " + username, HttpStatus.NOT_FOUND));

        // Per-assignment row + latest user_checksheet status (NULL = not started).
        // UNION: audit-side assignments + intervention-side plans (re-inspection
        // waves). Mobile app sees both kinds in one list and picks the right
        // create-payload field (auditAssignmentId vs interventionAssignmentId)
        // based on `assignmentKind`.
        //
        // The "latest UC" subselect on each side is a LATERAL with submitted_at
        // ORDER BY DESC LIMIT 1, so retries (e.g. NOT_APPROVED rework leaving
        // an old row behind) don't duplicate the assignment in the response.
        // Post-V1.28: one inspections row per assignment (collapsed). Audit-kind
        // inspections set audit_id directly; intervention-kind set
        // intervention_id and inherit audit_id via the interventions FK.
        String sql = """
        SELECT CASE WHEN ins.kind = 'AUDIT' THEN 'audit' ELSE 'intervention' END AS assignment_kind,
               ins.id   AS assignment_id,
               COALESCE(ins.audit_id, iv.audit_id) AS audit_id,
               aud.name AS audit_name,
               cs.id    AS checksheet_id,
               cs.name  AS checksheet_name,
               aud.start_date, aud.end_date,
               aloc.id  AS location_id,
               aloc.address AS location_address,
               c.name   AS city_name,
               a.id     AS auditee_id,
               a.name   AS auditee_name,
               CASE WHEN ins.status = 'ASSIGNED' THEN NULL ELSE ins.id END     AS uc_id,
               CASE WHEN ins.status = 'ASSIGNED' THEN NULL ELSE ins.status END AS uc_status,
               ins.started_at AS uc_started_at,
               (SELECT COUNT(*) FROM chks_questions q
                  JOIN chks_header_data h ON h.id = q.chks_header_data_id
                 WHERE h.checksheet_id = cs.id AND q.deleted_at IS NULL) AS total_questions,
               (SELECT COUNT(DISTINCT uca.chks_question_id)
                  FROM user_checksheet_answers uca
                 WHERE uca.inspection_id = ins.id AND uca.judgement IS NOT NULL) AS answered_questions
          FROM inspections ins
          LEFT JOIN interventions iv ON iv.id = ins.intervention_id
          JOIN audits aud           ON aud.id = COALESCE(ins.audit_id, iv.audit_id) AND aud.deleted_at IS NULL
          JOIN checksheets cs       ON cs.id = aud.checksheet_id
          JOIN auditee_locations aloc ON aloc.id = ins.auditee_location_id AND aloc.deleted_at IS NULL
          LEFT JOIN cities c        ON c.id = aloc.city_id
          LEFT JOIN auditees a      ON a.id = aloc.auditee_id
         WHERE ins.operator_user_id = :operatorId
           AND ins.deleted_at IS NULL
           AND aud.status = 'ACTIVE'
           AND ins.status NOT IN ('APPROVED', 'VALIDATED')
         ORDER BY
           CASE WHEN ins.status = 'IN_PROGRESS' THEN 0
                WHEN ins.status = 'ASSIGNED'    THEN 1
                ELSE 2 END,
           aud.end_date NULLS LAST,
           ins.id
        """;
        var query = em.createNativeQuery(sql);
        query.setParameter("operatorId", operator.getId());
        java.util.List<Object[]> rows = query.getResultList();
        java.util.List<java.util.Map<String, Object>> out = new java.util.ArrayList<>();
        for (Object[] r : rows) {
            java.util.Map<String, Object> m = new java.util.HashMap<>();
            m.put("assignmentKind", r[0]);   // "audit" or "intervention"
            m.put("assignmentId", r[1]);
            m.put("auditId", r[2]);
            m.put("auditName", r[3]);
            m.put("checksheetId", r[4]);
            m.put("checksheetName", r[5]);
            m.put("startDate", r[6]);
            m.put("endDate", r[7]);
            m.put("locationId", r[8]);
            String addr = r[9] == null ? null : r[9].toString();
            String city = r[10] == null ? null : r[10].toString();
            m.put("locationAddress", addr);
            m.put("locationCity", city);
            m.put("locationLabel", addr != null ? (city != null ? city + " — " + addr : addr) : ("Location " + r[8]));
            m.put("auditeeId", r[11]);
            m.put("auditeeName", r[12]);
            m.put("userChecksheetId", r[13]);
            m.put("status", r[14] == null ? "SCHEDULED" : r[14]);
            m.put("startedAt", r[15]);
            m.put("totalQuestions", r[16]);
            m.put("answeredQuestions", r[17]);
            out.add(m);
        }
        return new ResponseDTO<>("My assignments", out);
    }

    // ─── Helpers ────────────────────────────────────────────────────────────

    /**
     * BI stats. Every metric is derived from a single shared "audit_signal" CTE
     * — the set of in-scope APPROVED inspections enriched with their
     *  region/dealer/location context and their pct_ok score. Band counts,
     *  per-region/per-dealer/per-location tables, red list, and the answer-side
     *  drill-downs (what's-failing, top-failing-checkpoints) all SELECT from
     *  this CTE. That guarantees the same row set drives every panel and stops
     *  the "15 vs 13" inconsistencies that crept in when each query hand-rolled
     *  its own join chain.
     */
    @SuppressWarnings("unchecked")
    private AuditStatsDTO buildStats(Long auditId, String level, Long contextId, LocalDate startDate, LocalDate endDate) {
        Map<String, Object> params = new HashMap<>();
        params.put("auditId", auditId);
        String scopeLabel = switch (level) {
            case "region"   -> { params.put("regionId", contextId);   yield lookupRegionName(contextId); }
            case "dealer"   -> { params.put("auditeeId", contextId);  yield lookupAuditeeName(contextId); }
            case "location" -> { params.put("locationId", contextId); yield lookupLocationLabel(contextId); }
            default -> "National";
        };
        // Optional date-range filter on ins.submitted_at
        String dateFilter = "";
        if (startDate != null && endDate != null) {
            params.put("startDate", startDate);
            params.put("endDate", endDate);
            dateFilter = " AND DATE(ins.submitted_at) BETWEEN :startDate AND :endDate";
        }
        // The shared CTE — composed once, prepended to every SELECT below.
        String cte = scopeCte(level, dateFilter);

        // Band counts + avg score. Thresholds come from BAND_GREEN_PCT / BAND_AMBER_PCT.
        String bandSql = cte + String.format("""
            SELECT
              COUNT(*) FILTER (WHERE pct_ok >= %d) AS green,
              COUNT(*) FILTER (WHERE pct_ok >= %d AND pct_ok < %d) AS amber,
              COUNT(*) FILTER (WHERE pct_ok < %d) AS red,
              AVG(pct_ok) AS avg_score,
              COUNT(*) AS total
            FROM audit_signal
            """, BAND_GREEN_PCT, BAND_AMBER_PCT, BAND_GREEN_PCT, BAND_AMBER_PCT);
        var bandQ = em.createNativeQuery(bandSql);
        params.forEach(bandQ::setParameter);
        Object[] bandRow = (Object[]) bandQ.getSingleResult();
        Long green = toLong(bandRow[0]);
        Long amber = toLong(bandRow[1]);
        Long red = toLong(bandRow[2]);
        Double avgScore = bandRow[3] == null ? null : ((Number) bandRow[3]).doubleValue();
        Long totalAudits = toLong(bandRow[4]);

        // What's failing by category — joins answers to in-scope UCs through
        // the CTE so the category roll-up only counts in-scope audits.
        String whatsFailingSql = cte + """
            SELECT cat.name AS category,
                   100.0 * SUM(CASE WHEN uca.judgement != 1 THEN 1 ELSE 0 END) / NULLIF(COUNT(*),0) AS failure_pct,
                   COUNT(*) AS total,
                   SUM(CASE WHEN uca.judgement != 1 THEN 1 ELSE 0 END) AS fails
              FROM audit_signal sig
              JOIN user_checksheet_answers uca ON uca.inspection_id = sig.uc_id
              JOIN chks_question_results qr ON qr.id = uca.chks_question_result_id
              JOIN chks_questions q ON q.id = qr.chks_question_id
              JOIN chks_header_data elt ON elt.id = q.chks_header_data_id
              JOIN chks_header_data cat ON cat.id = elt.chks_header_data_id
             GROUP BY cat.name ORDER BY failure_pct DESC
            """;
        var wfQ = em.createNativeQuery(whatsFailingSql);
        params.forEach(wfQ::setParameter);
        List<Object[]> wfRows = wfQ.getResultList();
        List<Map<String, Object>> whatsFailing = new ArrayList<>();
        for (Object[] r : wfRows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("category", r[0]);
            m.put("failurePct", r[1] == null ? 0 : ((Number) r[1]).doubleValue());
            m.put("total", toLong(r[2]));
            m.put("fails", toLong(r[3]));
            whatsFailing.add(m);
        }

        // Top failing checkpoints — same in-scope CTE. Only rows with at least
        // one NOT-OK answer are returned, so a single audit with 3 NOT-OKs
        // produces 3 rows here instead of LIMIT-many low-or-zero-failure
        // checkpoints (which previously made the panel show 7-8 entries even
        // when the audit body had only 3 cards flagged).
        //
        // Each row carries the full 4-level hierarchy (zone → category →
        // element → checkpoint) so the BI panel can show the auditor where
        // the failure sits. The template's header tree is 3 levels deep
        // (zone is the section root, then category, then element); we walk
        // up via chks_header_data.chks_header_data_id (parent_id).
        String topCkSql = cte + String.format("""
            SELECT q.id AS question_id,
                   q.name AS checkpoint,
                   100.0 * SUM(CASE WHEN uca.judgement != 1 THEN 1 ELSE 0 END) / NULLIF(COUNT(*),0) AS failure_pct,
                   COUNT(*) AS total,
                   SUM(CASE WHEN uca.judgement != 1 THEN 1 ELSE 0 END) AS fails,
                   elt.name AS element,
                   cat.name AS category,
                   zone.name AS zone
              FROM audit_signal sig
              JOIN user_checksheet_answers uca ON uca.inspection_id = sig.uc_id
              JOIN chks_question_results qr ON qr.id = uca.chks_question_result_id
              JOIN chks_questions q ON q.id = qr.chks_question_id
              JOIN chks_header_data elt ON elt.id = q.chks_header_data_id
              LEFT JOIN chks_header_data cat ON cat.id = elt.chks_header_data_id
              LEFT JOIN chks_header_data zone ON zone.id = cat.chks_header_data_id
             GROUP BY q.id, q.name, elt.name, cat.name, zone.name
             HAVING SUM(CASE WHEN uca.judgement != 1 THEN 1 ELSE 0 END) > 0
             ORDER BY fails DESC, failure_pct DESC
             LIMIT %d
            """, TOP_FAILING_CHECKPOINTS_LIMIT);
        var tcQ = em.createNativeQuery(topCkSql);
        params.forEach(tcQ::setParameter);
        List<Object[]> tcRows = tcQ.getResultList();
        List<Map<String, Object>> topFailing = new ArrayList<>();
        for (Object[] r : tcRows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("questionId", toLong(r[0]));
            m.put("checkpoint", r[1]);
            m.put("failurePct", r[2] == null ? 0 : ((Number) r[2]).doubleValue());
            m.put("total", toLong(r[3]));
            m.put("fails", toLong(r[4]));
            m.put("element", r[5]);
            m.put("category", r[6]);
            m.put("zone", r[7]);
            topFailing.add(m);
        }

        // Per-level table — also driven by the same CTE.
        List<Map<String, Object>> table = buildLevelTable(level, cte, params);

        // Audit recency — total + audited locations IN SCOPE.
        // The recency totals must respect the same scope filter (region / dealer /
        // location), otherwise a regional view shows national counts.
        String recencySql = String.format("""
            SELECT
              (SELECT COUNT(*) FROM auditee_locations al
                 LEFT JOIN cities cc ON cc.id = al.city_id
                 LEFT JOIN states ss ON ss.id = cc.state_id
                WHERE al.deleted_at IS NULL AND (%s)) AS total_loc,
              (SELECT COUNT(DISTINCT aa.auditee_location_id)
                 FROM inspections aa
                 LEFT JOIN auditee_locations aloc2 ON aloc2.id = aa.auditee_location_id
                 LEFT JOIN cities c2 ON c2.id = aloc2.city_id
                 LEFT JOIN states s2 ON s2.id = c2.state_id
                WHERE aa.audit_id = :auditId AND aa.deleted_at IS NULL %s) AS in_audit
            """,
            recencyScopeForAllLocations(level, contextId),
            recencyScopeForAuditAssignments(level));
        var recencyQ = em.createNativeQuery(recencySql).setParameter("auditId", auditId);
        if (level.equals("region"))   recencyQ.setParameter("regionId", contextId);
        if (level.equals("dealer"))   recencyQ.setParameter("auditeeId", contextId);
        if (level.equals("location")) recencyQ.setParameter("locationId", contextId);
        Object[] recRow = (Object[]) recencyQ.getSingleResult();
        Long totalLoc = toLong(recRow[0]);
        Long inAudit = toLong(recRow[1]);

        // AI insights — paired-element co-occurrence callouts. The SQL shape
        // is generic ("when X fails, how often does Y also fail in the same
        // audit?"); the patterns + message text come from the active tenant's
        // tenants/<id>/config/insights.yaml (loaded by TenantInsightsConfig).
        // National scope only — drill levels render their own panels.
        List<String> insights = new ArrayList<>();
        if (level.equals("national")) {
            for (var corr : tenantInsights.getCorrelations()) {
                try {
                    Object[] coRow = (Object[]) em.createNativeQuery(String.format("""
                        WITH lhs AS (
                            SELECT uc.id AS uc_id, BOOL_OR(uca.judgement != 1) AS fail
                              FROM inspections uc
                              JOIN user_checksheet_answers uca ON uca.inspection_id = uc.id
                              JOIN chks_question_results qr ON qr.id = uca.chks_question_result_id
                              JOIN chks_questions q ON q.id = qr.chks_question_id
                              JOIN chks_header_data elt ON elt.id = q.chks_header_data_id
                             WHERE uc.kind = 'AUDIT' AND uc.audit_id = :auditId AND uc.status = '%s'
                               AND LOWER(elt.name) LIKE :lhsElement
                               AND LOWER(q.name)   LIKE :lhsQuestion
                             GROUP BY uc.id
                        ),
                        rhs AS (
                            SELECT uc.id AS uc_id, BOOL_OR(uca.judgement != 1) AS fail
                              FROM inspections uc
                              JOIN user_checksheet_answers uca ON uca.inspection_id = uc.id
                              JOIN chks_question_results qr ON qr.id = uca.chks_question_result_id
                              JOIN chks_questions q ON q.id = qr.chks_question_id
                              JOIN chks_header_data elt ON elt.id = q.chks_header_data_id
                             WHERE uc.kind = 'AUDIT' AND uc.audit_id = :auditId AND uc.status = '%s'
                               AND LOWER(elt.name) LIKE :rhsElement
                               AND LOWER(q.name)   LIKE :rhsQuestion
                             GROUP BY uc.id
                        )
                        SELECT
                          100.0 * COUNT(*) FILTER (WHERE l.fail AND r.fail) /
                              NULLIF(COUNT(*) FILTER (WHERE l.fail OR r.fail), 0) AS pct,
                          COUNT(*) FILTER (WHERE l.fail OR r.fail) AS total
                        FROM lhs l FULL OUTER JOIN rhs r ON l.uc_id = r.uc_id
                        """, UC_APPROVED, UC_APPROVED))
                            .setParameter("auditId", auditId)
                            .setParameter("lhsElement",  corr.left.elementPattern)
                            .setParameter("lhsQuestion", corr.left.questionPattern)
                            .setParameter("rhsElement",  corr.right.elementPattern)
                            .setParameter("rhsQuestion", corr.right.questionPattern)
                            .getSingleResult();
                    if (coRow[0] != null && coRow[1] != null && ((Number) coRow[1]).intValue() > 0) {
                        int pct = (int) Math.round(((Number) coRow[0]).doubleValue());
                        insights.add(corr.message.replace("{pct}", Integer.toString(pct)));
                    }
                } catch (PersistenceException e) {
                    // Schema drift or bad pattern would land here. Log so it
                    // surfaces; non-fatal — the rest of the dashboard renders
                    // without this single insight.
                    log.warn("Insight correlation '{}' failed for audit {}: {}",
                            corr.id, auditId, e.getMessage());
                }
            }
        }

        // Lowest-scoring dealers — bottom 10 regardless of band, sourced from
        // the same in-scope CTE as everything else (no parallel join chain).
        List<Map<String, Object>> redDealers = new ArrayList<>();
        // One row per dealership (auditee), aggregated across all of its
        // physical locations. We carry `locationCount` so the panel can show
        // "Modi Kia · 3 locations" — the user reads the same row as a
        // dealership rollup, not a single location. City/region pick one
        // representative (the city of the lowest-id location).
        String redDealersSql = cte + """
            SELECT auditee_id, auditee_name,
                   COUNT(DISTINCT location_id) AS location_count,
                   (ARRAY_AGG(city_name ORDER BY location_id))[1] AS city_name,
                   (ARRAY_AGG(region_name ORDER BY location_id))[1] AS region_name,
                   AVG(pct_ok) AS avg_score,
                   MAX(submitted_at) AS last_audited
              FROM audit_signal
             GROUP BY auditee_id, auditee_name
             ORDER BY avg_score ASC NULLS LAST
             LIMIT 10
            """;
        try {
            var rdQ = em.createNativeQuery(redDealersSql);
            params.forEach(rdQ::setParameter);
            @SuppressWarnings("unchecked")
            List<Object[]> rdRows = rdQ.getResultList();
            for (Object[] r : rdRows) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("auditeeId", toLong(r[0]));
                m.put("dealer", r[1]);
                m.put("locationCount", toLong(r[2]));
                m.put("city", r[3]);
                m.put("region", r[4]);
                m.put("score", r[5] == null ? null : (int) Math.round(((Number) r[5]).doubleValue()));
                m.put("lastAudited", r[6]);
                redDealers.add(m);
            }
        } catch (PersistenceException e) {
            log.warn("Red-dealers query failed for audit {}: {}", auditId, e.getMessage());
        }

        return AuditStatsDTO.builder()
                .scope(level + (contextId == null ? "" : ":" + contextId))
                .scopeLabel(scopeLabel)
                .totalLocations(totalLoc)
                .auditedLocations(inAudit)
                .neverAudited(totalLoc - inAudit)
                .totalAudits(totalAudits)
                .greenCount(green)
                .amberCount(amber)
                .redCount(red)
                .avgScore(avgScore)
                .whatsFailing(whatsFailing)
                .topFailingCheckpoints(topFailing)
                .table(table)
                .aiInsights(insights)
                .redDealers(redDealers)
                .build();
    }

    /**
     * Per-level breakdown table — every variant SELECTs FROM the same
     * audit_signal CTE built by scopeCte(level). The shape (column order +
     * meaning) is preserved per the existing frontend contract:
     *
     *   national  → c0=region_id   c1=region_name      c2=audited (=G+A+R) c3=audited (alias) c4=avg_score c5=green c6=amber c7=red
     *   region    → c0=auditee_id  c1=auditee_name     c2=auditee_code     c3=audited         c4=audited (alias) c5=avg_score c6=last_audited
     *   dealer    → c0=location_id c1=location_address c2=city             c3=audited         c4=avg_score      c5=last_audited
     *   location  → c0=uc_id       c1=started_at       c2=submitted_at     c3=uc_status       c4=pct_ok
     *
     * The "national.c2/c3" and "region.c3/c4" columns are duplicated solely
     * for backward-compatibility with the frontend's existing column index;
     * once the frontend migrates to typed keys (issue #1) we can drop the
     * doubles.
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> buildLevelTable(String level, String cte, Map<String, Object> params) {
        String sql;
        switch (level) {
            case "national" -> sql = cte + """
                SELECT region_id, region_name,
                       COUNT(*) AS audited_total,
                       COUNT(*) AS audited_total_dup,
                       AVG(pct_ok) AS avg_score,
                       COUNT(*) FILTER (WHERE pct_ok >= 75) AS green_count,
                       COUNT(*) FILTER (WHERE pct_ok >= 60 AND pct_ok < 75) AS amber_count,
                       COUNT(*) FILTER (WHERE pct_ok < 60) AS red_count
                  FROM audit_signal
                 GROUP BY region_id, region_name
                 ORDER BY region_name
                """;
            case "region" -> sql = cte + """
                SELECT auditee_id, auditee_name, auditee_code,
                       COUNT(*) AS audited_total,
                       COUNT(*) AS audited_total_dup,
                       AVG(pct_ok) AS avg_score,
                       MAX(submitted_at) AS last_audited
                  FROM audit_signal
                 GROUP BY auditee_id, auditee_name, auditee_code
                 ORDER BY avg_score NULLS LAST
                """;
            case "dealer" -> sql = cte + """
                SELECT location_id, location_address, city_name,
                       COUNT(*) AS total_audits,
                       AVG(pct_ok) AS avg_score,
                       MAX(submitted_at) AS last_audited
                  FROM audit_signal
                 GROUP BY location_id, location_address, city_name
                 ORDER BY avg_score NULLS LAST
                """;
            case "location" -> sql = cte + """
                SELECT uc_id, started_at, submitted_at, uc_status, pct_ok
                  FROM audit_signal
                 ORDER BY started_at DESC
                """;
            default -> { return List.of(); }
        }
        var q = em.createNativeQuery(sql);
        params.forEach(q::setParameter);
        List<Object[]> rows = q.getResultList();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            for (int i = 0; i < r.length; i++) {
                m.put("c" + i, r[i]);
            }
            out.add(m);
        }
        return out;
    }

    /**
     * The single shared in-scope CTE. Every BI metric query starts with this.
     * `audit_signal` is the set of APPROVED inspections that pass the
     * (audit, scope) filter, enriched with their location / dealer / region
     * context.
     *
     * <p>{@code pct_ok} reflects the <b>current effective state</b> per
     * audit_assignment — not the original audit's score. For each
     * {@code chks_question_result_id}, the most recent APPROVED answer wins
     * across:
     * <ul>
     *   <li>the original audit UC, AND</li>
     *   <li>every {@code intervention_assignments} row whose
     *       {@code audit_assignment_id} matches this assignment, joined to
     *       its APPROVED re-inspection-wave UCs.</li>
     * </ul>
     * So if an audit started at 67% and two interventions later the dealer
     * has flipped six failing questions to OK, pct_ok = 81% — every BI
     * panel that selects FROM audit_signal inherits this rollup
     * automatically (PRD: BI shows post-intervention current state).
     *
     * <p>Filter parameters are named (:auditId, :regionId, :auditeeId,
     * :locationId) and bound by the caller.
     */
    private String scopeCte(String level) {
        return scopeCte(level, "");
    }

    /** Variant that appends an optional date-range filter on ins.submitted_at.
     *  Pass empty string for no date filter.
     *  dateFilter example: " AND DATE(ins.submitted_at) BETWEEN :startDate AND :endDate" */
    private String scopeCte(String level, String dateFilter) {
        String scopeFilter = switch (level) {
            case "region"   -> "AND s.region_id = :regionId";
            case "dealer"   -> "AND aloc.auditee_id = :auditeeId";
            case "location" -> "AND aloc.id = :locationId";
            default -> "";
        };
        return String.format("""
            WITH audit_signal AS (
              SELECT
                ins.id        AS uc_id,
                ins.id        AS aa_id,
                ins.status    AS uc_status,
                ins.started_at, ins.submitted_at,
                ins.audit_id,
                aloc.id      AS location_id,
                aloc.address AS location_address,
                a.id         AS auditee_id,
                a.name       AS auditee_name,
                a.code       AS auditee_code,
                c.id         AS city_id,
                c.name       AS city_name,
                s.id         AS state_id,
                r.id         AS region_id,
                r.name       AS region_name,
                score.pct_ok
              FROM inspections ins
              JOIN auditee_locations aloc ON aloc.id = ins.auditee_location_id AND aloc.deleted_at IS NULL
              JOIN auditees a            ON a.id = aloc.auditee_id
              LEFT JOIN cities c         ON c.id = aloc.city_id
              LEFT JOIN states s         ON s.id = c.state_id
              LEFT JOIN regions r        ON r.id = s.region_id
              LEFT JOIN LATERAL (
                  -- Per-(chks_question_result) latest APPROVED judgement across:
                  --   (a) this AUDIT-kind inspection's own answers, AND
                  --   (b) any INTERVENTION-kind inspections at the same location
                  --       belonging to interventions tied to this audit_id.
                  SELECT 100.0 * SUM(CASE WHEN latest.judgement = 1 THEN 1 ELSE 0 END)
                                / NULLIF(COUNT(*),0) AS pct_ok
                    FROM (
                      SELECT DISTINCT ON (qrid) judgement
                        FROM (
                          SELECT uca.chks_question_result_id AS qrid,
                                 uca.judgement,
                                 ins2.submitted_at           AS sub_at,
                                 uca.id                      AS uca_id
                            FROM inspections ins2
                            LEFT JOIN interventions iv ON iv.id = ins2.intervention_id
                            JOIN user_checksheet_answers uca
                                 ON uca.inspection_id = ins2.id
                                AND uca.deleted_at IS NULL
                                AND uca.chks_question_result_id IS NOT NULL
                           WHERE ins2.deleted_at IS NULL
                             AND ins2.auditee_location_id = ins.auditee_location_id
                             AND ins2.status = '%%1$s'
                             AND (
                                  (ins2.kind = 'AUDIT'        AND ins2.audit_id = ins.audit_id)
                               OR (ins2.kind = 'INTERVENTION' AND iv.audit_id   = ins.audit_id)
                             )
                        ) all_ans
                       ORDER BY qrid, sub_at DESC NULLS LAST, uca_id DESC
                    ) latest
              ) score ON TRUE
             WHERE ins.deleted_at IS NULL
               AND ins.kind = 'AUDIT'
               AND ins.status = '%%1$s'
               AND ins.audit_id = :auditId
               %%2$s
               %%3$s
            )
            """.replace("%%", "%"), UC_APPROVED, scopeFilter, dateFilter);
    }

    /** Resolve a region id to its display name. Falls back to "Region <id>" if
     *  the row is missing (shouldn't happen on seeded data — log a warning
     *  on lookup failure so ops sees the corruption instead of just seeing
     *  fallback labels in the dashboard). */
    private String lookupRegionName(Long regionId) {
        if (regionId == null) return "Region";
        try {
            Object name = em.createNativeQuery("SELECT name FROM regions WHERE id = :id")
                    .setParameter("id", regionId).getSingleResult();
            return name == null ? ("Region " + regionId) : name.toString();
        } catch (jakarta.persistence.NoResultException e) {
            log.warn("lookupRegionName({}): no such region — falling back to id label", regionId);
            return "Region " + regionId;
        } catch (PersistenceException e) {
            log.warn("lookupRegionName({}): query failed — falling back to id label", regionId, e);
            return "Region " + regionId;
        }
    }

    /** Resolve an auditee (dealer) id to its display name. */
    private String lookupAuditeeName(Long auditeeId) {
        if (auditeeId == null) return "Dealer";
        try {
            Object name = em.createNativeQuery("SELECT name FROM auditees WHERE id = :id")
                    .setParameter("id", auditeeId).getSingleResult();
            return name == null ? ("Dealer " + auditeeId) : name.toString();
        } catch (jakarta.persistence.NoResultException e) {
            log.warn("lookupAuditeeName({}): no such auditee — falling back to id label", auditeeId);
            return "Dealer " + auditeeId;
        } catch (PersistenceException e) {
            log.warn("lookupAuditeeName({}): query failed — falling back to id label", auditeeId, e);
            return "Dealer " + auditeeId;
        }
    }

    /** Resolve a location id to "<auditee name> – <city>". */
    private String lookupLocationLabel(Long locationId) {
        if (locationId == null) return "Location";
        try {
            Object[] row = (Object[]) em.createNativeQuery("""
                    SELECT a.name, c.name FROM auditee_locations al
                      JOIN auditees a ON a.id = al.auditee_id
                 LEFT JOIN cities c ON c.id = al.city_id
                     WHERE al.id = :id
                    """).setParameter("id", locationId).getSingleResult();
            String dealer = row[0] == null ? "Dealer" : row[0].toString();
            String city = row[1] == null ? "" : (" – " + row[1]);
            return dealer + city;
        } catch (jakarta.persistence.NoResultException e) {
            log.warn("lookupLocationLabel({}): no such location — falling back to id label", locationId);
            return "Location " + locationId;
        } catch (PersistenceException e) {
            log.warn("lookupLocationLabel({}): query failed — falling back to id label", locationId, e);
            return "Location " + locationId;
        }
    }

    /** Build the WHERE clause for the "total locations in scope" subquery
     *  in the recency block. */
    private String recencyScopeForAllLocations(String level, Long contextId) {
        return switch (level) {
            case "region" -> "ss.region_id = :regionId";
            case "dealer" -> "al.auditee_id = :auditeeId";
            case "location" -> "al.id = :locationId";
            default -> "TRUE";
        };
    }

    /** Build the trailing AND-clause for the "audited locations in scope"
     *  subquery — the assignments-side filter mirrors the scope from buildStats. */
    private String recencyScopeForAuditAssignments(String level) {
        return switch (level) {
            case "region" -> "AND s2.region_id = :regionId";
            case "dealer" -> "AND aloc2.auditee_id = :auditeeId";
            case "location" -> "AND aa.auditee_location_id = :locationId";
            default -> "";
        };
    }

    /** Lightweight DTO with no stats. Used in list views once we move the
     *  per-audit aggregate to a single GROUP BY (see Round 3 issue: N+1). */
    private AuditDTO toBriefDTO(Audit a) {
        return AuditDTO.builder()
                .id(a.getId())
                .name(a.getName())
                .checksheetId(a.getChecksheet() == null ? null : a.getChecksheet().getId())
                .checksheetName(a.getChecksheet() == null ? null : a.getChecksheet().getName())
                .checksheetCode(a.getChecksheet() == null ? null : a.getChecksheet().getModelNo())
                .status(a.getStatus())
                .startDate(a.getStartDate())
                .endDate(a.getEndDate())
                .createdAt(a.getCreatedAt())
                .updatedAt(a.getUpdatedAt())
                .build();
    }

    /** Brief DTO + the per-audit assignment-count aggregate. One extra round
     *  trip per audit — fine for /list at demo scale, batch later. */
    private AuditDTO toDetailDTO(Audit a) {
        AuditDTO dto = toBriefDTO(a);
        String sql = String.format("""
            SELECT
              (SELECT COUNT(*)        FROM inspections ins
                WHERE ins.audit_id = :id AND ins.kind='AUDIT' AND ins.deleted_at IS NULL) AS total_assignments,
              (SELECT COUNT(*)        FROM inspections ins
                WHERE ins.audit_id = :id AND ins.kind='AUDIT' AND ins.deleted_at IS NULL
                  AND ins.status = '%s') AS done,
              (SELECT COUNT(*)        FROM inspections ins
                WHERE ins.audit_id = :id AND ins.kind='AUDIT' AND ins.deleted_at IS NULL
                  AND ins.status IN ('%s','%s','%s')) AS in_progress
            """, UC_APPROVED, UC_IN_PROGRESS, UC_SUBMITTED, UC_VALIDATED);
        Object[] row = (Object[]) em.createNativeQuery(sql).setParameter("id", a.getId()).getSingleResult();
        Long total = toLong(row[0]);
        Long done = toLong(row[1]);
        Long inProgress = toLong(row[2]);
        Long notStarted = (total == null ? 0 : total) - (done == null ? 0 : done) - (inProgress == null ? 0 : inProgress);
        dto.setTotalLocations(total);
        dto.setDone(done);
        dto.setInProgress(inProgress);
        dto.setNotStarted(notStarted < 0 ? 0 : notStarted);
        return dto;
    }

    private static Long toLong(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.longValue();
        return Long.valueOf(o.toString());
    }
}
