package com.checkSheet.audit;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T1.0 — Full audit-to-BI lifecycle (canonical chain).
 *
 * <p>Per docs/test-plan-intervention.md, this is the single test that
 * proves the whole intervention pipeline works end-to-end. Owns its
 * fixtures (users / roles / audit / assignments / UCs / interventions /
 * re-inspection waves) — zero dependence on demo-seed accounts. Three
 * timing variants share the chain:
 * <ul>
 *   <li>A: Intervention activated BEFORE any UC approval (listener path)</li>
 *   <li>B: Intervention activated AFTER some, BEFORE others (mixed)</li>
 *   <li>C: Intervention activated AFTER all approvals (back-fill only)</li>
 * </ul>
 * All three must produce identical post-state — proves back-fill on
 * activate is correct + idempotent.
 *
 * <p>Bootstrap: one SUPER_ADMIN user inserted via JdbcTemplate (mirrors the
 * V100.001 seed pattern). Every other user — including operators, the
 * dealer principal, validators, approvers — is created through the
 * public {@code /api/user/createOrEditOperator} endpoint. This exercises
 * the user-create flow as a side effect, per the test plan.
 *
 * <p>Tear-down: afterAll soft-deletes every row the test created, in FK
 * dependency order.
 */
@Tag("tier1")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
// No @DirtiesContext — the test plan (docs/test-plan-intervention.md:115)
// targets a "60s parallel suite via single Spring boot context per group +
// @DirtiesContext.NEVER". teardown() below thoroughly wipes everything
// this class created so the next test class inherits an unchanged context.
class InterventionLifecycleE2ETest {

    private static final long CHECKSHEET_ID = 15L;       // seeded, APPROVED, all 4 question types
    private static final long ROLE_DEPT_ADMIN = 2L;
    private static final long ROLE_OPERATOR   = 9L;
    // DEALER_PRINCIPAL role id is looked up at runtime — V1.27 added it.

    @LocalServerPort int port;
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;

    private long bootstrapUserId;
    private String runId;       // unique suffix for every fixture name in this test class
    private long t_test_start;  // wall-clock window lower bound
    private long t_test_end;
    private Long[] originalValidatorIds;  // restored at teardown
    private Long[] originalApproverIds;

    private long deptSalesId;
    private long deptSpaId;
    private long roleDealerPrincipalId;

    // Tear-down ledger — filled as we create things, drained in reverse.
    private final List<Long> createdUserIds = new ArrayList<>();
    private final List<Long> createdAuditIds = new ArrayList<>();
    private final List<Long> createdInterventionIds = new ArrayList<>();

    @BeforeAll
    void bootstrapTestRealm() {
        // Uppercase: createOrEditOperator stores username uppercased, so use
        // uppercase end-to-end to keep our SQL lookups + login flows lined up.
        runId = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        t_test_start = System.currentTimeMillis();

        // Defensive: clean any orphan rows left behind by a prior failed run
        // BEFORE inserting our own — otherwise our cleanup pattern
        // (T_%) would wipe the bootstrap admin we're about to create.
        cleanupStaleTestInterventions();

        deptSalesId = jdbc.queryForObject(
            "SELECT id FROM departments WHERE name='Sales' AND department_id IS NULL",
            Long.class);
        deptSpaId = jdbc.queryForObject(
            "SELECT id FROM departments WHERE name='Sales Process Adherence' AND department_id = ?",
            Long.class, deptSalesId);
        roleDealerPrincipalId = jdbc.queryForObject(
            "SELECT id FROM roles WHERE role_code = 'DEALER_PRINCIPAL'",
            Long.class);

        // Bootstrap admin: SQL-inserted to break the chicken-and-egg
        // (createOrEditOperator requires an authenticated caller).
        // Mirrors V100.001 — same bcrypt hash for password "12345678".
        String bootstrapUsername = "T_BOOT_ADMIN_" + runId;
        bootstrapUserId = jdbc.queryForObject("""
            INSERT INTO users (username, email, first_name, last_name, mobile, password, status, fail_login_count, created_at, created_by)
            VALUES (?, ?, ?, ?, ?, '$2a$10$IDcjr6bjC.zpRWSNBrlT0uluLTZyddk8dMG7aMBR2INuDPTVG.GxK', 'A', 0, NOW(), 1)
            RETURNING id
            """, Long.class,
            bootstrapUsername,
            bootstrapUsername.toLowerCase() + "@e2e.test",
            "Boot", "Admin", "9000000000");
        createdUserIds.add(bootstrapUserId);
        // Make the bootstrap user a DEPT_ADMIN @ Sales so the createOrEditOperator
        // permission check passes.
        jdbc.update("""
            INSERT INTO user_role_departments (user_id, role_id, department_id, created_at, created_by)
            VALUES (?, ?, ?, NOW(), 1)
            """, bootstrapUserId, ROLE_DEPT_ADMIN, deptSalesId);

        // ALL bootstrap permissions need to be granted by tenant. The role 2
        // (DEPT_ADMIN) already carries the demo permissions in V100.x; this
        // test runs against the local DB which has them.
    }

    private void cleanupStaleTestInterventions() {
        // Match every test-class's naming pattern — InterventionFlowE2ETest's
        // T1.3 / T1.4 / T1.8 / T1.10, InterventionMultiE2ETest's T2.1 / T2.2,
        // and our own T1.0-*. Also match the legacy Intervention-A/B-{uuid}
        // names this test uses for its activated rows.
        List<Long> staleIvIds = jdbc.queryForList("""
            SELECT id FROM interventions
             WHERE name LIKE 'Intervention-A-%' OR name LIKE 'Intervention-B-%'
                OR name LIKE 'T1.%' OR name LIKE 'T2.%'
                OR name LIKE '__SMOKE_INTERVENTION_%'
            """, Long.class);
        for (Long ivId : staleIvIds) {
            jdbc.update("DELETE FROM intervention_assignment_questions WHERE inspection_id IN (SELECT id FROM inspections WHERE intervention_id = ?)", ivId);
            jdbc.update("DELETE FROM user_checksheet_answers WHERE inspection_id IN (SELECT id FROM inspections WHERE intervention_id = ?)", ivId);
            jdbc.update("DELETE FROM user_checksheet_validations_history WHERE inspection_id IN (SELECT id FROM inspections WHERE intervention_id = ?)", ivId);
            jdbc.update("DELETE FROM user_checksheet_validations WHERE inspection_id IN (SELECT id FROM inspections WHERE intervention_id = ?)", ivId);
            jdbc.update("DELETE FROM user_checksheet_approvals_history WHERE inspection_id IN (SELECT id FROM inspections WHERE intervention_id = ?)", ivId);
            jdbc.update("DELETE FROM user_checksheet_approvals WHERE inspection_id IN (SELECT id FROM inspections WHERE intervention_id = ?)", ivId);
            // F16: was duplicated — one DELETE is enough.
            jdbc.update("DELETE FROM inspections WHERE intervention_id = ?", ivId);
            jdbc.update("DELETE FROM intervention_assignment_targets WHERE intervention_id = ?", ivId);
            jdbc.update("DELETE FROM intervention_questions WHERE intervention_id = ?", ivId);
            jdbc.update("DELETE FROM interventions WHERE id = ?", ivId);
        }
        // Also clean stale audits + their UCs from prior failed runs.
        List<Long> staleAuditIds = jdbc.queryForList(
            "SELECT id FROM audits WHERE name LIKE 'T1.0-%'", Long.class);
        for (Long aid : staleAuditIds) {
            cleanupAuditCascade(aid);
        }
        // Also clean stale users from prior failed runs.
        jdbc.update("DELETE FROM refresh_token WHERE user_id IN (SELECT id FROM users WHERE username LIKE 'T_%')");
        jdbc.update("DELETE FROM user_role_departments WHERE user_id IN (SELECT id FROM users WHERE username LIKE 'T_%')");
        jdbc.update("UPDATE inspections SET dealer_principal_user_id = NULL WHERE dealer_principal_user_id IN (SELECT id FROM users WHERE username LIKE 'T_%')");
        jdbc.update("UPDATE users SET created_by = 1, updated_by = NULL, deleted_by = NULL WHERE created_by IN (SELECT id FROM users WHERE username LIKE 'T_%')");
        jdbc.update("DELETE FROM users WHERE username LIKE 'T_%'");
    }

    private void cleanupAuditCascade(long auditId) {
        jdbc.update("DELETE FROM user_checksheet_answers WHERE inspection_id IN (SELECT id FROM inspections WHERE audit_id = ?)", auditId);
        jdbc.update("DELETE FROM user_checksheet_validations_history WHERE inspection_id IN (SELECT id FROM inspections WHERE audit_id = ?)", auditId);
        jdbc.update("DELETE FROM user_checksheet_validations WHERE inspection_id IN (SELECT id FROM inspections WHERE audit_id = ?)", auditId);
        jdbc.update("DELETE FROM user_checksheet_approvals_history WHERE inspection_id IN (SELECT id FROM inspections WHERE audit_id = ?)", auditId);
        jdbc.update("DELETE FROM user_checksheet_approvals WHERE inspection_id IN (SELECT id FROM inspections WHERE audit_id = ?)", auditId);
        // F16: was duplicated — one DELETE is enough.
        jdbc.update("DELETE FROM inspections WHERE audit_id = ?", auditId);
        jdbc.update("DELETE FROM audits WHERE id = ?", auditId);
    }

    /** API-created users have password set to their uppercase username
     *  (per UserServiceImpl.createOrEditOperator). The bootstrap user is
     *  inserted via SQL with the standard "12345678" hash. */
    private String passwordFor(String username) {
        if (username.startsWith("T_BOOT_ADMIN_")) return "12345678";
        return username.toUpperCase();
    }

    private String tokenFor(String username) {
        return RestTestSupport.token(http, port, username, passwordFor(username));
    }

    @AfterAll
    void teardown() {
        // Reverse dependency order. Deleted_at sentinel; no hard deletes
        // for FK-shared rows that the demo data references.
        for (Long ivId : createdInterventionIds) {
            jdbc.update("DELETE FROM intervention_assignment_questions WHERE inspection_id IN (SELECT id FROM inspections WHERE intervention_id = ?)", ivId);
            jdbc.update("DELETE FROM user_checksheet_answers WHERE inspection_id IN (SELECT id FROM inspections WHERE intervention_id = ?)", ivId);
            jdbc.update("DELETE FROM user_checksheet_validations_history WHERE inspection_id IN (SELECT id FROM inspections WHERE intervention_id = ?)", ivId);
            jdbc.update("DELETE FROM user_checksheet_validations WHERE inspection_id IN (SELECT id FROM inspections WHERE intervention_id = ?)", ivId);
            jdbc.update("DELETE FROM user_checksheet_approvals_history WHERE inspection_id IN (SELECT id FROM inspections WHERE intervention_id = ?)", ivId);
            jdbc.update("DELETE FROM user_checksheet_approvals WHERE inspection_id IN (SELECT id FROM inspections WHERE intervention_id = ?)", ivId);
            // F16: was duplicated — one DELETE is enough.
            jdbc.update("DELETE FROM inspections WHERE intervention_id = ?", ivId);
            jdbc.update("DELETE FROM intervention_assignment_targets WHERE intervention_id = ?", ivId);
            jdbc.update("DELETE FROM intervention_questions WHERE intervention_id = ?", ivId);
            jdbc.update("DELETE FROM interventions WHERE id = ?", ivId);
        }
        for (Long auditId : createdAuditIds) {
            jdbc.update("""
                DELETE FROM user_checksheet_answers WHERE inspection_id IN
                  (SELECT id FROM inspections WHERE audit_id = ?)""", auditId);
            jdbc.update("""
                DELETE FROM user_checksheet_validations_history WHERE inspection_id IN
                  (SELECT id FROM inspections WHERE audit_id = ?)""", auditId);
            jdbc.update("""
                DELETE FROM user_checksheet_validations WHERE inspection_id IN
                  (SELECT id FROM inspections WHERE audit_id = ?)""", auditId);
            jdbc.update("""
                DELETE FROM user_checksheet_approvals_history WHERE inspection_id IN
                  (SELECT id FROM inspections WHERE audit_id = ?)""", auditId);
            jdbc.update("""
                DELETE FROM user_checksheet_approvals WHERE inspection_id IN
                  (SELECT id FROM inspections WHERE audit_id = ?)""", auditId);
            jdbc.update("DELETE FROM inspections WHERE audit_id = ?", auditId);
            jdbc.update("DELETE FROM audits WHERE id = ?", auditId);
        }
        // Restore the original checksheet validator / approver arrays.
        if (originalValidatorIds != null) {
            jdbc.update("UPDATE checksheets SET data_validator_user_ids = ?::bigint[] WHERE id = ?",
                "{" + String.join(",", java.util.Arrays.stream(originalValidatorIds)
                    .map(String::valueOf).toArray(String[]::new)) + "}",
                CHECKSHEET_ID);
        }
        if (originalApproverIds != null) {
            jdbc.update("UPDATE checksheets SET data_approver_user_ids = ?::bigint[] WHERE id = ?",
                "{" + String.join(",", java.util.Arrays.stream(originalApproverIds)
                    .map(String::valueOf).toArray(String[]::new)) + "}",
                CHECKSHEET_ID);
        }

        // Reverse so the bootstrap admin (index 0, used as created_by for
        // every other user) is deleted last, after its dependents.
        java.util.List<Long> reversed = new java.util.ArrayList<>(createdUserIds);
        java.util.Collections.reverse(reversed);
        for (Long uid : reversed) {
            jdbc.update("DELETE FROM refresh_token WHERE user_id = ?", uid);
            jdbc.update("DELETE FROM user_role_departments WHERE user_id = ?", uid);
            // Also clear any created_by/updated_by/deleted_by FK self-refs from
            // other rows we don't own (defensive — shouldn't happen in practice).
            jdbc.update("UPDATE inspections SET dealer_principal_user_id = NULL WHERE dealer_principal_user_id = ?", uid);
            jdbc.update("DELETE FROM users WHERE id = ?", uid);
        }
        t_test_end = System.currentTimeMillis();
    }

    // ── Tests ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("T1.0-A — intervention activated BEFORE any UC approval (listener-only path)")
    void lifecycle_A_interventionBeforeApprovals() throws Exception {
        runFullLifecycle(ActivationTiming.BEFORE_ANY_APPROVAL);
    }

    @Test
    @DisplayName("T1.0-B — intervention activated mid-flight (mixed listener + back-fill)")
    void lifecycle_B_interventionMidFlight() throws Exception {
        runFullLifecycle(ActivationTiming.AFTER_FIRST_APPROVAL_ONLY);
    }

    @Test
    @DisplayName("T1.0-C — intervention activated AFTER all UCs approved (back-fill only)")
    void lifecycle_C_interventionAfterAllApprovals() throws Exception {
        runFullLifecycle(ActivationTiming.AFTER_ALL_APPROVALS);
    }

    private enum ActivationTiming { BEFORE_ANY_APPROVAL, AFTER_FIRST_APPROVAL_ONLY, AFTER_ALL_APPROVALS }

    // ── The chain ───────────────────────────────────────────────────────────

    /** Single chain that all three variants share. The {@code timing}
     *  parameter governs only when interventions are activated relative
     *  to UC approvals. Everything else is identical. */
    private void runFullLifecycle(ActivationTiming timing) throws Exception {
        Realm realm = createTestRealm();

        // 1. Create a fresh audit anchored to checksheet 15.
        long auditId = createAudit(realm, "T1.0-" + timing + "-" + runId);

        // 2. Pick 5 distinct auditee_locations and attach assignments.
        List<Long> assignmentIds = attachAssignments(realm, auditId, 5);

        // Pre-compute the disjoint A / B question subsets ONCE so both the
        // failing-answer picker (which marks A's + B's questions NOT-OK on
        // the audit UCs) and the intervention-create flow (which scopes A
        // and B to their respective question sets) agree.
        QuestionSubsets qs = disjointQuestionSubsets();

        // 3-5. Create 3 UCs, fill with deliberate NOT-OK across all 4 question
        // types, walk to APPROVED — the timing parameter splits step 6
        // (intervention activate) before/during/after this step.
        long ucCount = 3;
        List<UcFixture> ucs = new ArrayList<>();
        long activatedIvA = -1, activatedIvB = -1;

        if (timing == ActivationTiming.BEFORE_ANY_APPROVAL) {
            // Activate before any approval → listener fires on each approval.
            activatedIvA = createAndActivateIntervention(realm, auditId, assignmentIds, "A-" + runId, qs.a);
            activatedIvB = createAndActivateIntervention(realm, auditId, assignmentIds, "B-" + runId, qs.b);
        }

        for (int i = 0; i < ucCount; i++) {
            UcFixture uc = fillAndApproveUC(realm, assignmentIds.get(i),
                                            realm.operators.get(i % realm.operators.size()),
                                            qs);
            ucs.add(uc);
            if (timing == ActivationTiming.AFTER_FIRST_APPROVAL_ONLY && i == 0) {
                // Activate after the first UC approved → first UC gets a plan
                // via back-fill, remaining UCs via listener.
                activatedIvA = createAndActivateIntervention(realm, auditId, assignmentIds, "A-" + runId, qs.a);
                activatedIvB = createAndActivateIntervention(realm, auditId, assignmentIds, "B-" + runId, qs.b);
            }
        }

        if (timing == ActivationTiming.AFTER_ALL_APPROVALS) {
            // All UCs approved before activation → all plans created via back-fill.
            activatedIvA = createAndActivateIntervention(realm, auditId, assignmentIds, "A-" + runId, qs.a);
            activatedIvB = createAndActivateIntervention(realm, auditId, assignmentIds, "B-" + runId, qs.b);
        }

        assertTrue(activatedIvA > 0 && activatedIvB > 0, "interventions activated");

        // 7. Verify plans appeared for each (UC, intervention) where overlap exists.
        assertPlansExistForAllUCs(ucs, List.of(activatedIvA, activatedIvB));

        // 7a. INVARIANT — no plans for untouched audit assignments. Two of the
        // five attached assignments never had a UC created (= never APPROVED).
        // Activating the intervention with MANUAL targeting on ALL five aaIds
        // must NOT instantiate plans for the unapproved two — the back-fill
        // sweep is supposed to skip aaIds with no APPROVED audit-kind UC, and
        // the listener never fires for never-approved aas. Failure here would
        // mean the back-fill is over-eager (creates plans for never-failed
        // sites) or pulls in a non-APPROVED UC (lineage leak).
        Set<Long> touchedAaIds = ucs.stream().map(u -> u.auditAssignmentId).collect(Collectors.toSet());
        List<Long> untouchedAaIds = assignmentIds.stream()
            .filter(id -> !touchedAaIds.contains(id))
            .collect(Collectors.toList());
        assertNoPlansForUntouchedAssignments(untouchedAaIds, List.of(activatedIvA, activatedIvB));

        // 7b. INVARIANT — tracked-question exactness. Each plan's
        // intervention_assignment_questions rows must equal the intersection
        // of (failing questions on the audit UC) ∩ (intervention's question
        // scope). Since the test deliberately makes every question in
        // qs.a ∪ qs.b fail on each audit UC, the expected IAQ set for plan-A
        // is qs.a and for plan-B is qs.b. Failure here = the listener /
        // back-fill stamped a wrong / incomplete tracked-question subset on
        // the plan, breaking the audit-report overlay's question filter.
        assertTrackedQuestionsExactness(ucs, activatedIvA, qs.a);
        assertTrackedQuestionsExactness(ucs, activatedIvB, qs.b);

        // 8. Acknowledge plans as the dealer principal (whose user is stamped on the aa).
        acknowledgeAllPlans(realm, ucs);

        // 9-10. Run re-inspection waves — flip every tracked question to OK.
        runReinspectionWaves(realm, ucs);

        // 11-14. Asserts on BI, overlay, and timeline consistency.
        assertBILift(auditId, ucs);
        assertOverlayChain(realm, ucs, activatedIvA, activatedIvB);
        assertTimelineConsistency(auditId);
    }

    // ── Helper: create the test realm (users + role assignments) ───────────

    private static class Realm {
        String adminUsername;
        String validatorUsername;
        String approverUsername;
        String dealerPrincipalUsername;
        long dealerPrincipalUserId;
        List<String> operators;     // usernames
        List<Long> operatorIds;
    }

    private Realm createTestRealm() throws Exception {
        Realm r = new Realm();
        String bootToken = tokenFor("T_BOOT_ADMIN_" + runId);

        r.adminUsername     = "T_ADMIN_" + runId;
        r.validatorUsername = "T_VALD_"  + runId;
        r.approverUsername  = "T_APPR_"  + runId;
        r.dealerPrincipalUsername = "T_DP_" + runId;
        r.operators  = new ArrayList<>();
        r.operatorIds = new ArrayList<>();

        long adminId = createUserViaApi(bootToken, r.adminUsername, "Test", "Admin",
            ROLE_DEPT_ADMIN, deptSalesId);
        long valdId  = createUserViaApi(bootToken, r.validatorUsername, "Test", "Validator",
            ROLE_DEPT_ADMIN, deptSalesId);
        long apprId  = createUserViaApi(bootToken, r.approverUsername, "Test", "Approver",
            ROLE_DEPT_ADMIN, deptSalesId);
        long dpId    = createUserViaApi(bootToken, r.dealerPrincipalUsername, "Test", "DealerPrincipal",
            roleDealerPrincipalId, deptSalesId);
        r.dealerPrincipalUserId = dpId;
        for (int i = 0; i < 3; i++) {
            String un = "T_OP_" + i + "_" + runId;
            long oid = createUserViaApi(bootToken, un, "Test", "Operator" + i,
                ROLE_OPERATOR, deptSpaId);
            r.operators.add(un);
            r.operatorIds.add(oid);
        }
        // Replace (don't append to) the checksheet's validator/approver
        // arrays with JUST our test users. The approval-flow logic only
        // flips the UC to VALIDATED/APPROVED when ALL listed validators/
        // approvers have acted; with the seeded multi-validator list we'd
        // never advance because only our one test user would validate.
        // Saved + restored in teardown so we don't pollute the DB.
        if (originalValidatorIds == null) {
            originalValidatorIds = jdbc.queryForObject(
                "SELECT data_validator_user_ids FROM checksheets WHERE id = ?",
                (rs, n) -> { java.sql.Array a = rs.getArray(1); return a == null ? new Long[0] : (Long[]) a.getArray(); },
                CHECKSHEET_ID);
            originalApproverIds = jdbc.queryForObject(
                "SELECT data_approver_user_ids FROM checksheets WHERE id = ?",
                (rs, n) -> { java.sql.Array a = rs.getArray(1); return a == null ? new Long[0] : (Long[]) a.getArray(); },
                CHECKSHEET_ID);
        }
        jdbc.update("UPDATE checksheets SET data_validator_user_ids = ARRAY[?]::bigint[] WHERE id = ?",
            valdId, CHECKSHEET_ID);
        jdbc.update("UPDATE checksheets SET data_approver_user_ids  = ARRAY[?]::bigint[] WHERE id = ?",
            apprId, CHECKSHEET_ID);
        return r;
    }

    /** Create a user through {@code /api/user/createOrEditOperator}.
     *  The endpoint sets password = uppercase username (per UserServiceImpl).
     *  Returns the created user id. */
    private long createUserViaApi(String bootToken, String username, String firstName,
                                  String lastName, long roleId, long deptId) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("username", username);
        payload.put("firstName", firstName);
        payload.put("lastName", lastName);
        payload.put("mobile", "9000000000");
        payload.put("email", username.toLowerCase() + "@e2e.test");
        payload.put("departmentId", deptId);
        payload.put("roleId", roleId);
        JsonNode resp = RestTestSupport.postOk(http, port, "/api/user/createOrEditOperator", bootToken, payload);
        long uid = jdbc.queryForObject("SELECT id FROM users WHERE username = ?", Long.class, username);
        createdUserIds.add(uid);
        return uid;
    }

    // ── Helpers: audit / assignment / UC creation ──────────────────────────

    private long createAudit(Realm realm, String name) throws Exception {
        String adminToken = tokenFor(realm.adminUsername);
        Map<String, Object> body = Map.of(
            "name", name,
            "checksheetId", CHECKSHEET_ID,
            "status", "ACTIVE"
        );
        JsonNode resp = RestTestSupport.postOk(http, port, "/api/audit/createAudit", adminToken, body);
        long id = resp.path("data").path("id").asLong();
        createdAuditIds.add(id);
        return id;
    }

    private List<Long> attachAssignments(Realm realm, long auditId, int n) throws Exception {
        // Pick n distinct auditee_locations. Stamp the dealer principal on
        // each, set operator round-robin.
        List<Long> locIds = jdbc.queryForList(
            "SELECT id FROM auditee_locations WHERE deleted_at IS NULL ORDER BY id LIMIT ?",
            Long.class, n);
        assertEquals(n, locIds.size(), "need " + n + " seeded auditee_locations");

        List<Map<String, Object>> assignments = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            assignments.add(Map.of(
                "auditeeLocationId", locIds.get(i),
                "operatorUserId", realm.operatorIds.get(i % realm.operatorIds.size())
            ));
        }
        Map<String, Object> body = Map.of("auditId", auditId, "assignments", assignments);
        String adminToken = tokenFor(realm.adminUsername);
        RestTestSupport.postOk(http, port, "/api/audit/addAuditAssignments", adminToken, body);

        // Stamp dealer_principal_user_id on the new audit_assignments so
        // the dealer principal can ack the plans we'll create later.
        List<Long> aaIds = jdbc.queryForList(
            "SELECT id FROM inspections WHERE audit_id = ? ORDER BY id", Long.class, auditId);
        jdbc.update("UPDATE inspections SET dealer_principal_user_id = ? WHERE audit_id = ?",
            realm.dealerPrincipalUserId, auditId);
        return aaIds;
    }

    private static class UcFixture {
        long ucId;
        long auditAssignmentId;
        long operatorUserId;
        Map<Long, Long> qrIdToQuestionId = new LinkedHashMap<>();
        List<Long> notOkQuestionIds = new ArrayList<>();
        Map<String, List<Long>> notOkByType = new LinkedHashMap<>();
        Timestamp originalApprovedAt;
    }

    /** Drive one UC end-to-end: create, answer NOT-OK on every question in
     *  the A and B subsets (so both interventions will overlap with this
     *  UC's failures), submit, validate, approve. Returns enough fixture
     *  state for the later phases (re-inspection wave, overlay asserts). */
    private UcFixture fillAndApproveUC(Realm realm, long aaId, String operatorUsername,
                                        QuestionSubsets qs) throws Exception {
        UcFixture uc = new UcFixture();
        uc.auditAssignmentId = aaId;
        uc.operatorUserId = jdbc.queryForObject(
            "SELECT id FROM users WHERE username = ?", Long.class, operatorUsername);

        String opToken = tokenFor(operatorUsername);

        // 1) createOrUpdate (operator) — endpoint takes a LIST.
        Map<String, Object> create = Map.of(
            "auditAssignmentId", aaId,
            "status", "IN_PROGRESS",
            "shift", "First",
            "startedAt", "2026-05-10 10:00:00.000",
            "submissionVersion", 0,
            "frequencyOfFreqOfChkCnt", 1
        );
        JsonNode created = RestTestSupport.postOk(http, port, "/api/userChecksheet/createOrUpdate", opToken, List.of(create));
        uc.ucId = created.path("data").get(0).path("id").asLong();
        assertTrue(uc.ucId > 0, "createOrUpdate response should carry id; got " + created);

        // 2) Pick the lowest-id chks_question_result for each question in
        // (qs.a ∪ qs.b). This guarantees both A and B will overlap with
        // this UC's failing answers when the listener / back-fill runs.
        List<Long> failingQuestions = new ArrayList<>();
        failingQuestions.addAll(qs.a);
        failingQuestions.addAll(qs.b);
        List<Map<String, Object>> chosenResults = new ArrayList<>();
        for (Long qId : failingQuestions) {
            Map<String, Object> qr = jdbc.queryForMap("""
                SELECT id AS qrid, chks_question_id AS qid FROM chks_question_results
                 WHERE chks_question_id = ? AND deleted_at IS NULL
                 ORDER BY id LIMIT 1
                """, qId);
            chosenResults.add(qr);
            uc.qrIdToQuestionId.put(((Number) qr.get("qrid")).longValue(),
                                    ((Number) qr.get("qid")).longValue());
            uc.notOkQuestionIds.add(qId);
        }

        // 3) Submit answers — all NOT-OK.
        String answeredAt = "2026-05-10 10:30:00.000";
        List<Map<String, Object>> answers = new ArrayList<>();
        for (Map<String, Object> qr : chosenResults) {
            answers.add(Map.of(
                "userChecksheetId", uc.ucId,
                "chksQuestionId",   qr.get("qid"),
                "chksQuestionResultId", qr.get("qrid"),
                "judgement", 2,                         // NOT OK
                "answer", "test NOT OK",
                "answeredAt", answeredAt
            ));
        }
        RestTestSupport.postOk(http, port, "/api/userChecksheet/createOrUpdateUserChksAns", opToken, answers);

        // 4) Submit (endpoint takes a list)
        Map<String, Object> submit = Map.of(
            "id", uc.ucId,
            "auditAssignmentId", aaId,
            "checksheetId", CHECKSHEET_ID,
            "status", "SUBMITTED",
            "frequencyOfFreqOfChkCnt", 1
        );
        RestTestSupport.postOk(http, port, "/api/userChecksheet/createOrUpdate", opToken, List.of(submit));

        // 5) Validate
        String valdToken = tokenFor(realm.validatorUsername);
        Map<String, Object> validate = Map.of(
            "userChecksheetId", uc.ucId,
            "status", "VALIDATED",
            "remarks", "validated by test"
        );
        RestTestSupport.postOk(http, port, "/api/userChecksheetValidation/addUserChecksheetValidation",
            valdToken, validate);

        // 6) Approve
        String apprToken = tokenFor(realm.approverUsername);
        Map<String, Object> approve = Map.of(
            "userChecksheetId", uc.ucId,
            "status", "APPROVED",
            "remarks", "approved by test"
        );
        RestTestSupport.postOk(http, port, "/api/userChecksheetApproval/addUserChecksheetApproval",
            apprToken, approve);

        // Sanity: the UC really did flip to APPROVED.
        String status = jdbc.queryForObject("SELECT status FROM inspections WHERE id = ?",
            String.class, uc.ucId);
        assertEquals("APPROVED", status, "uc " + uc.ucId + " should be APPROVED");
        uc.originalApprovedAt = jdbc.queryForObject(
            "SELECT submitted_at FROM inspections WHERE id = ?", Timestamp.class, uc.ucId);

        // Give the AFTER_COMMIT async listener a chance to run.
        Thread.sleep(200);
        return uc;
    }

    /** Two non-overlapping question subsets — A and B — to scope two
     *  concurrent interventions on the same audit cycle without tripping
     *  the activate-time conflict check (PRD §3.1.5). Implemented by
     *  partitioning the SUBJECTIVE_CONDITION pool: A gets the lower-id
     *  half, B gets the upper. */
    private static class QuestionSubsets { List<Long> a; List<Long> b; }

    private QuestionSubsets disjointQuestionSubsets() {
        List<Long> pool = new ArrayList<>(jdbc.queryForList("""
            SELECT DISTINCT qr.chks_question_id FROM chks_question_results qr
             WHERE qr.answer_type = 'SUBJECTIVE_CONDITION' AND qr.deleted_at IS NULL
             ORDER BY qr.chks_question_id LIMIT 12
            """, Long.class));
        QuestionSubsets out = new QuestionSubsets();
        out.a = new ArrayList<>(pool.subList(0, 6));
        out.b = new ArrayList<>(pool.subList(6, 12));
        return out;
    }

    // ── Helpers: intervention create / activate ────────────────────────────

    private long createAndActivateIntervention(Realm realm, long auditId, List<Long> aaIds,
                                               String suffix, List<Long> questionIds) throws Exception {
        String adminToken = tokenFor(realm.adminUsername);
        Map<String, Object> draftPayload = new LinkedHashMap<>();
        draftPayload.put("name", "Intervention-" + suffix);
        draftPayload.put("priority", "P1");
        draftPayload.put("auditId", auditId);
        draftPayload.put("targetDate", "2026-12-01");
        draftPayload.put("questionIds", questionIds);
        draftPayload.put("targetingMode", "MANUAL");
        draftPayload.put("targetAuditAssignmentIds", aaIds);

        JsonNode draft = RestTestSupport.postOk(http, port, "/api/intervention/createDraft", adminToken, draftPayload);
        long ivId = draft.path("data").path("id").asLong();
        createdInterventionIds.add(ivId);

        Map<String, Object> targeting = Map.of(
            "targetingMode", "MANUAL",
            "targetAuditAssignmentIds", aaIds
        );
        RestTestSupport.postOk(http, port, "/api/intervention/" + ivId + "/activate", adminToken, targeting);
        return ivId;
    }

    private void assertPlansExistForAllUCs(List<UcFixture> ucs, List<Long> ivIds) {
        for (UcFixture uc : ucs) {
            for (Long ivId : ivIds) {
                // Post-V1.28: a "plan" is an INTERVENTION-kind inspection at the
                // same location as the audit-kind aa, parented to the intervention.
                // Exactly ONE — over-instantiation (= 2+ active plans for the
                // same iv at the same location) would mean the listener +
                // back-fill duplicated the work, and the unique partial index
                // uk_inspections_intervention_loc should have caught it. Hard-
                // fail the assertion if we see plan count ≠ 1.
                Long planCount = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM inspections plan
                     WHERE plan.kind = 'INTERVENTION'
                       AND plan.intervention_id = ?
                       AND plan.auditee_location_id = (SELECT auditee_location_id FROM inspections WHERE id = ?)
                       AND plan.deleted_at IS NULL
                    """, Long.class, ivId, uc.auditAssignmentId);
                assertEquals(1L, planCount.longValue(),
                    "expected exactly 1 active plan for iv " + ivId + " on aa " + uc.auditAssignmentId
                    + " — got " + planCount + ". 0=listener didn't fire or back-fill missed; "
                    + ">1=over-instantiation (idempotency guard broken).");
            }
        }
    }

    private void assertNoPlansForUntouchedAssignments(List<Long> untouchedAaIds, List<Long> ivIds) {
        for (Long aaId : untouchedAaIds) {
            for (Long ivId : ivIds) {
                Long planCount = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM inspections plan
                     WHERE plan.kind = 'INTERVENTION'
                       AND plan.intervention_id = ?
                       AND plan.auditee_location_id = (SELECT auditee_location_id FROM inspections WHERE id = ?)
                       AND plan.deleted_at IS NULL
                    """, Long.class, ivId, aaId);
                assertEquals(0L, planCount.longValue(),
                    "expected 0 plans for iv " + ivId + " on UNTOUCHED aa " + aaId
                    + " — got " + planCount + ". Back-fill / listener should only "
                    + "instantiate for APPROVED audit-kind inspections.");
            }
        }
    }

    private void assertTrackedQuestionsExactness(List<UcFixture> ucs, Long ivId, List<Long> expectedScope) {
        for (UcFixture uc : ucs) {
            List<Long> trackedQids = jdbc.queryForList("""
                SELECT iaq.chks_question_id FROM intervention_assignment_questions iaq
                  JOIN inspections plan ON plan.id = iaq.inspection_id
                 WHERE plan.kind = 'INTERVENTION'
                   AND plan.intervention_id = ?
                   AND plan.auditee_location_id = (SELECT auditee_location_id FROM inspections WHERE id = ?)
                   AND plan.deleted_at IS NULL
                   AND iaq.deleted_at IS NULL
                """, Long.class, ivId, uc.auditAssignmentId);
            // Sets — the SQL ordering and the scope list ordering aren't
            // guaranteed to match.
            Set<Long> got = new HashSet<>(trackedQids);
            Set<Long> expected = new HashSet<>(expectedScope);
            assertEquals(expected, got,
                "tracked-question subset on plan for iv " + ivId + " / aa " + uc.auditAssignmentId
                + " must equal (failing questions ∩ iv scope). expected=" + expected + " got=" + got);
        }
    }

    private void acknowledgeAllPlans(Realm realm, List<UcFixture> ucs) throws Exception {
        // V1.30: ack is a soft signal — stamps acknowledged_at / acknowledged_by
        // but doesn't advance status. Caller MUST be the dealer principal
        // stamped on each plan's underlying inspection. Test exercises both
        // the happy path and the row-level FORBIDDEN gate.
        String principalToken = tokenFor(realm.dealerPrincipalUsername);

        // First: walking through every plan and ack as the dealer principal.
        for (UcFixture uc : ucs) {
            List<Long> planIds = jdbc.queryForList("""
                SELECT id FROM inspections
                 WHERE kind = 'INTERVENTION'
                   AND auditee_location_id = (SELECT auditee_location_id FROM inspections WHERE id = ?)
                   AND intervention_id IN (SELECT id FROM interventions WHERE audit_id =
                       (SELECT audit_id FROM inspections WHERE id = ?))
                   AND deleted_at IS NULL
                """, Long.class, uc.auditAssignmentId, uc.auditAssignmentId);
            for (Long planId : planIds) {
                JsonNode resp = RestTestSupport.postOk(http, port,
                    "/api/intervention-assignment/" + planId + "/acknowledge",
                    principalToken, null);
                JsonNode data = resp.path("data");
                assertNotNull(data.get("acknowledgedAt"),
                    "ack response should include acknowledgedAt timestamp; got " + resp);
                assertEquals(realm.dealerPrincipalUserId, data.path("acknowledgedByUserId").asLong(),
                    "acknowledgedByUserId should match the calling principal");
                // Status must NOT have advanced — ack is a soft signal.
                assertEquals("ASSIGNED", data.path("status").asText(),
                    "ack must not advance status; operator's createOrUpdate does that");

                // Verify the DB row too.
                Map<String, Object> row = jdbc.queryForMap(
                    "SELECT acknowledged_at, acknowledged_by FROM inspections WHERE id = ?", planId);
                assertNotNull(row.get("acknowledged_at"));
                assertEquals(realm.dealerPrincipalUserId, ((Number) row.get("acknowledged_by")).longValue());
            }
        }
    }

    /** For each plan, run a re-inspection UC: create, answer (every tracked
     *  question marked OK), submit, validate, approve. */
    private void runReinspectionWaves(Realm realm, List<UcFixture> ucs) throws Exception {
        for (UcFixture uc : ucs) {
            // Plans are INTERVENTION-kind inspections at the same location as
            // the audit-kind aa, parented to one of this lifecycle's interventions.
            List<Long> planIds = jdbc.queryForList("""
                SELECT id FROM inspections
                 WHERE kind = 'INTERVENTION'
                   AND auditee_location_id = (SELECT auditee_location_id FROM inspections WHERE id = ?)
                   AND intervention_id IN (SELECT id FROM interventions WHERE audit_id =
                       (SELECT audit_id FROM inspections WHERE id = ?))
                   AND deleted_at IS NULL
                """, Long.class, uc.auditAssignmentId, uc.auditAssignmentId);
            for (Long planId : planIds) {
                runOneReinspectionWave(realm, uc, planId);
            }
        }
    }

    private void runOneReinspectionWave(Realm realm, UcFixture uc, long planId) throws Exception {
        // Drive the re-inspection wave through the SAME mobile-API endpoints
        // a real auditor uses. SQL inserts here would hide the V1.27 mobile
        // contract gap (interventionAssignmentId acceptance, myAssignments
        // UNION). If those break, this test breaks.
        String operatorUsername = jdbc.queryForObject(
            "SELECT username FROM users WHERE id = ?", String.class, uc.operatorUserId);
        String opToken = tokenFor(operatorUsername);

        // 1) Verify myAssignments shows the intervention plan to the operator.
        JsonNode myAssign = RestTestSupport.getOk(http, port, "/api/audit/myAssignments", opToken);
        boolean planVisible = false;
        for (JsonNode item : myAssign.path("data")) {
            if ("intervention".equals(item.path("assignmentKind").asText())
                && item.path("assignmentId").asLong() == planId) {
                planVisible = true; break;
            }
        }
        assertTrue(planVisible, "operator's myAssignments must include intervention plan " + planId);

        // 2) createOrUpdate with interventionAssignmentId — the V1.27 path.
        Map<String, Object> create = Map.of(
            "interventionAssignmentId", planId,
            "status", "IN_PROGRESS",
            "shift", "First",
            "startedAt", "2026-05-10 11:00:00.000",
            "submissionVersion", 0,
            "frequencyOfFreqOfChkCnt", 1
        );
        JsonNode created = RestTestSupport.postOk(http, port,
            "/api/userChecksheet/createOrUpdate", opToken, List.of(create));
        long reUcId = created.path("data").get(0).path("id").asLong();
        assertTrue(reUcId > 0, "createOrUpdate must return a re-inspection UC id");
        // V1.28 invariant — re-inspection happens IN-PLACE on the plan row.
        // The plan IS the inspection (kind=INTERVENTION), so createOrUpdate
        // must return the same id, not insert a new row. Without this the
        // unique partial index uk_inspections_intervention_loc would still
        // be respected, but the row count drifts: a regression to the pre-
        // V1.28 multi-row model would silently double the inspection count.
        assertEquals(planId, reUcId,
            "Re-inspection must occur in-place on the plan inspection (planId == reUcId), not a new row");

        // 3) Submit answers — every tracked question OK.
        List<Map<String, Object>> tracked = jdbc.queryForList("""
            SELECT chks_question_id, chks_question_result_id
              FROM intervention_assignment_questions
             WHERE inspection_id = ? AND deleted_at IS NULL
            """, planId);
        List<Map<String, Object>> answers = new ArrayList<>();
        for (Map<String, Object> t : tracked) {
            Object qid = t.get("chks_question_id");
            Object qrid = t.get("chks_question_result_id");
            if (qrid == null) continue;
            answers.add(Map.of(
                "userChecksheetId", reUcId,
                "chksQuestionId", qid,
                "chksQuestionResultId", qrid,
                "judgement", 1,                          // OK
                "answer", "fixed in re-inspection",
                "answeredAt", "2026-05-10 11:30:00.000"
            ));
        }
        if (!answers.isEmpty()) {
            RestTestSupport.postOk(http, port,
                "/api/userChecksheet/createOrUpdateUserChksAns", opToken, answers);
        }

        // 4) Submit (operator session — mobile path).
        Map<String, Object> submit = Map.of(
            "id", reUcId,
            "interventionAssignmentId", planId,
            "status", "SUBMITTED",
            "submissionVersion", 0,
            "frequencyOfFreqOfChkCnt", 1
        );
        RestTestSupport.postOk(http, port,
            "/api/userChecksheet/createOrUpdate", opToken, List.of(submit));

        // 5) Validate + approve — admin/back-office paths, NOT mobile.
        String valdToken = tokenFor(realm.validatorUsername);
        RestTestSupport.postOk(http, port,
            "/api/userChecksheetValidation/addUserChecksheetValidation",
            valdToken, Map.of("userChecksheetId", reUcId, "status", "VALIDATED",
                              "remarks", "re-inspection validated"));
        String apprToken = tokenFor(realm.approverUsername);
        RestTestSupport.postOk(http, port,
            "/api/userChecksheetApproval/addUserChecksheetApproval",
            apprToken, Map.of("userChecksheetId", reUcId, "status", "APPROVED",
                              "remarks", "re-inspection approved"));

        Thread.sleep(200);  // AFTER_COMMIT listener (evaluatePlanCompletion)
    }

    // ── Asserts: BI lift + overlay chain + timeline consistency ────────────

    private void assertBILift(long auditId, List<UcFixture> ucs) throws Exception {
        // We computed each UC's pre-intervention pct_ok at approve time.
        // Post re-inspection, the rolled-up pct_ok via audit_signal must
        // be strictly greater for every UC's audit_assignment.
        for (UcFixture uc : ucs) {
            Double original = jdbc.queryForObject("""
                SELECT 100.0 * SUM(CASE WHEN judgement=1 THEN 1 ELSE 0 END) / NULLIF(COUNT(*),0)
                  FROM user_checksheet_answers WHERE inspection_id = ? AND deleted_at IS NULL
                """, Double.class, uc.ucId);
            Double rolled = rolledPctForUc(uc.ucId, uc.auditAssignmentId);
            assertNotNull(original); assertNotNull(rolled);
            assertTrue(rolled > original,
                "post-intervention pct " + rolled + " should exceed original " + original
                + " on uc " + uc.ucId);
        }
    }

    private Double rolledPctForUc(long ucId, long aaId) {
        // Post-V1.28 audit_signal shape: latest answer per chks_question_result_id
        // across the AUDIT-kind inspection AND any INTERVENTION-kind inspections
        // at the same location whose intervention is tied to the same audit.
        return jdbc.queryForObject(
            "SELECT 100.0 * SUM(CASE WHEN latest.judgement = 1 THEN 1 ELSE 0 END) / NULLIF(COUNT(*),0) " +
            "  FROM (SELECT DISTINCT ON (qrid) judgement FROM ( " +
            "    SELECT uca.chks_question_result_id AS qrid, uca.judgement, " +
            "           ins2.submitted_at AS sub_at, uca.id AS uca_id " +
            "      FROM inspections base " +
            "      JOIN inspections ins2 ON ins2.deleted_at IS NULL " +
            "                            AND ins2.status = 'APPROVED' " +
            "                            AND ins2.auditee_location_id = base.auditee_location_id " +
            "      LEFT JOIN interventions iv ON iv.id = ins2.intervention_id " +
            "      JOIN user_checksheet_answers uca ON uca.inspection_id = ins2.id " +
            "                                      AND uca.deleted_at IS NULL " +
            "                                      AND uca.chks_question_result_id IS NOT NULL " +
            "     WHERE base.id = ? AND base.kind = 'AUDIT' " +
            "       AND ((ins2.kind = 'AUDIT'        AND ins2.audit_id = base.audit_id) " +
            "         OR (ins2.kind = 'INTERVENTION' AND iv.audit_id   = base.audit_id)) " +
            "  ) all_ans ORDER BY qrid, sub_at DESC NULLS LAST, uca_id DESC) latest",
            Double.class, ucId);
    }

    private void assertOverlayChain(Realm realm, List<UcFixture> ucs, long ivA, long ivB) throws Exception {
        String adminToken = tokenFor(realm.adminUsername);
        for (UcFixture uc : ucs) {
            JsonNode resp = RestTestSupport.getOk(http, port,
                "/api/audit/userChecksheet/" + uc.ucId + "/improvement-overlay", adminToken);
            JsonNode data = resp.path("data");
            double original = data.path("originalScore").asDouble();
            double current  = data.path("currentScore").asDouble();
            double delta    = data.path("scoreDelta").asDouble();
            assertTrue(current > original,
                "uc " + uc.ucId + " currentScore " + current + " must exceed originalScore " + original);
            assertEquals(current - original, delta, 0.01,
                "uc " + uc.ucId + " scoreDelta math should match");
            // For each tracked question, reAuditAnswers should have an entry whose judgement = 1.
            for (Long qid : uc.notOkQuestionIds) {
                JsonNode chain = data.path("reAuditAnswers").path(qid.toString());
                if (!chain.isArray() || chain.size() == 0) continue;
                Long lastJudgement = chain.get(chain.size() - 1).path("judgement").asLong();
                assertEquals(Long.valueOf(1L), lastJudgement,
                    "uc " + uc.ucId + " q " + qid + " latest re-inspection judgement must be OK");
            }
        }
    }

    /** End-of-test rule sweep — fail with the offending pair printed if any
     *  inequality is violated. The 5-second slack on the wall-clock window
     *  is generous enough for CI clock drift but tight enough to flag bugs. */
    private void assertTimelineConsistency(long auditId) {
        long lo = t_test_start - 5_000;
        long hi = System.currentTimeMillis() + 5_000;

        // Read Timestamp columns directly — going via EXTRACT(EPOCH FROM ...)
        // converts in Postgres's session timezone and confuses the wall-clock
        // window check when DB and JVM disagree on TZ. Java's
        // Timestamp.getTime() returns UTC epoch millis regardless of TZ.
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT uc.id AS uc_id,
                   uc.created_at, uc.submitted_at,
                   (SELECT MAX(approved_at) FROM user_checksheet_approvals_history h
                     WHERE h.inspection_id = uc.id) AS approved_at
              FROM inspections uc
             WHERE uc.kind = 'AUDIT'
               AND uc.audit_id = ?
               AND uc.deleted_at IS NULL
            """, auditId);
        for (Map<String, Object> r : rows) {
            Long c = tsMillis(r.get("created_at"));
            Long s = tsMillis(r.get("submitted_at"));
            Long a = tsMillis(r.get("approved_at"));
            if (c != null) assertWithin(c, lo, hi, r);
            if (s != null) {
                assertWithin(s, lo, hi, r);
                if (c != null) assertTrue(c <= s, "uc " + r.get("uc_id") + " created_at after submitted_at: " + r);
            }
            if (a != null) {
                assertWithin(a, lo, hi, r);
                if (s != null) assertTrue(s <= a, "uc " + r.get("uc_id") + " submitted_at after approved_at: " + r);
            }
        }

        List<Map<String, Object>> plans = jdbc.queryForList("""
            SELECT ia.id, ia.created_at,
                   NULL::timestamp AS acknowledged_at,    -- no longer stored post-V1.28
                   NULL::timestamp AS completed_at        -- no longer stored post-V1.28
              FROM inspections ia
              JOIN interventions iv ON iv.id = ia.intervention_id
             WHERE ia.kind = 'INTERVENTION' AND iv.audit_id = ? AND ia.deleted_at IS NULL
            """, auditId);
        for (Map<String, Object> p : plans) {
            Long c = tsMillis(p.get("created_at"));
            Long a = tsMillis(p.get("acknowledged_at"));
            Long f = tsMillis(p.get("completed_at"));
            if (c != null) assertWithin(c, lo, hi, p);
            if (a != null) {
                assertWithin(a, lo, hi, p);
                if (c != null) assertTrue(c <= a, "plan " + p.get("id") + " created_at after acknowledged_at: " + p);
            }
            if (f != null) {
                assertWithin(f, lo, hi, p);
                if (a != null) assertTrue(a <= f, "plan " + p.get("id") + " acknowledged_at after completed_at: " + p);
            }
        }
    }

    private Long tsMillis(Object v) {
        if (v == null) return null;
        if (v instanceof Timestamp t) return t.getTime();
        return null;
    }

    private void assertWithin(Long t, long lo, long hi, Object ctx) {
        assertTrue(t >= lo && t <= hi,
            "timestamp " + t + " outside [" + lo + "," + hi + "] for " + ctx);
    }
}
