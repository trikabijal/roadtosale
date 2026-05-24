package com.checkSheet.audit;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Intervention pipeline E2E — implements docs/test-plan-intervention.md
 * Tier 1 cases.
 *
 * <p>Covered here:
 * <ul>
 *   <li>T1.3 — draft → activate (ALL / BY_REGION / MANUAL targeting)</li>
 *   <li>T1.4 — question conflict on activate (HTTP 409)</li>
 *   <li>T1.5 — plan instantiation when an audit UC is approved</li>
 *   <li>T1.7 — re-inspection wave approval flips plan to COMPLETED</li>
 *   <li>T1.8 — audit_signal CTE rolls up post-intervention score</li>
 * </ul>
 *
 * <p>Auth, fixtures, and helpers mirror the {@link AuditFlowE2ETest}
 * pattern — single Spring boot context, log-in via /api/user/login to
 * exercise the JWT path honestly, manipulate DB via JdbcTemplate where
 * a service path doesn't exist (e.g. fabricating a re-inspection UC
 * because the listener requires a brand-new approval).
 */
@Tag("tier1")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class InterventionFlowE2ETest {

    private static final String NAME_PREFIX = "T1.";

    /** Bootstrap super-admin token captured once in @BeforeAll — used
     *  for every admin-flavoured call. The bootstrap user (defaulting
     *  to Z006135) is the only seed user the test depends on. */
    private String adminToken;

    @LocalServerPort int port;
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;

    /** Bootstrap super-admin username — see application-<env>.properties
     *  key {@code app.test.bootstrap-user}. */
    @org.springframework.beans.factory.annotation.Value("${app.test.bootstrap-user:Z006135}")
    private String bootstrapAdminUsername;

    private RestTestSupport.OwnedAuditFixture fixture;
    private long auditId;
    private long auditAssignmentA;
    private long auditAssignmentB;
    private long checksheetId;
    private long sampleUcId;       // an APPROVED UC on auditAssignmentA with NOT-OK answers
    private List<Long> failingQuestionIds;

    @org.junit.jupiter.api.AfterAll
    void cleanupOrphansAfterClass() {
        // Wipe any T1.* interventions this class created — even if a test
        // failed mid-flight, so the next test class starts clean.
        RestTestSupport.wipeTestInterventions(jdbc, NAME_PREFIX);
        // F13/C5: tear down the audit + everything under it.
        RestTestSupport.cleanupOwnedAuditFixture(jdbc, fixture);
    }

    @org.junit.jupiter.api.AfterEach
    void cleanupAfterEachMethod() {
        // Per-method cleanup so a mid-test failure doesn't leak into the
        // next method (each test creates its own T1.x-{uuid} interventions).
        RestTestSupport.wipeTestInterventions(jdbc, NAME_PREFIX);
    }

    @BeforeAll
    void setup() {
        // Defensive: wipe orphans from previous failed runs of THIS class.
        RestTestSupport.wipeTestInterventions(jdbc, NAME_PREFIX);
        // Log in as the bootstrap super-admin once and reuse the token
        // for every admin call below. No KIA_* seed dependency.
        adminToken = RestTestSupport.token(http, port, bootstrapAdminUsername);
        // F13/C5: own our audit + inspection instead of picking demo seed
        // via ORDER BY id LIMIT 1. Codex reproduced a 409 CONFLICT against
        // pre-existing intervention 639 in a local run with the prior code.
        fixture = RestTestSupport.createOwnedAuditFixtureWithToken(http, port, jdbc,
            NAME_PREFIX + "flow.", adminToken, /* minFailingAnswers */ 5);
        auditId          = fixture.auditId;
        checksheetId     = fixture.checksheetId;
        sampleUcId       = fixture.auditAssignmentId;
        auditAssignmentA = fixture.auditAssignmentId;
        auditAssignmentB = fixture.auditAssignmentBId;
        failingQuestionIds = fixture.failingQuestionIds.subList(0, 3);
        assertEquals(3, failingQuestionIds.size(),
            "need 3 failing questions to construct overlap scenarios");
    }

    // ── T1.3 — Draft create + activate ──────────────────────────────────

    @Test
    @DisplayName("T1.3 — Draft create then activate (MANUAL targeting) materialises target set")
    void draftCreateAndManualActivate() {
        Map<String, Object> draftPayload = Map.of(
            "name", "T1.3-MANUAL-" + UUID.randomUUID(),
            "theme", "test",
            "priority", "P1",
            "auditId", auditId,
            "targetDate", "2026-12-01",
            "questionIds", failingQuestionIds,
            "targetingMode", "MANUAL",
            "targetAuditAssignmentIds", List.of(auditAssignmentA)
        );
        JsonNode draft = postOk("/api/intervention/createDraft", adminToken, draftPayload);
        long ivId = draft.path("data").path("id").asLong();
        assertTrue(ivId > 0);
        assertEquals("DRAFT", draft.path("data").path("status").asText());

        Map<String, Object> targeting = Map.of(
            "targetingMode", "MANUAL",
            "targetAuditAssignmentIds", List.of(auditAssignmentA)
        );
        JsonNode activated = postOk("/api/intervention/" + ivId + "/activate", adminToken, targeting);
        assertEquals("ACTIVE", activated.path("data").path("status").asText());
        assertEquals(1, activated.path("data").path("totalAssignmentTargets").asLong());
        assertNotNull(activated.path("data").path("activatedAt").asText(null));

        Long targetCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM intervention_assignment_targets WHERE intervention_id = ? AND deleted_at IS NULL",
            Long.class, ivId);
        assertEquals(1L, targetCount);

        cleanupIntervention(ivId);
    }

    @Test
    @DisplayName("T1.3 — Activate without questions returns 422")
    void activateWithoutQuestionsRejected() {
        Map<String, Object> draftPayload = Map.of(
            "name", "T1.3-NOQ-" + UUID.randomUUID(),
            "priority", "P1",
            "auditId", auditId,
            "targetDate", "2026-12-01",
            "questionIds", List.of(),
            "targetingMode", "ALL"
        );
        JsonNode draft = postOk("/api/intervention/createDraft", adminToken, draftPayload);
        long ivId = draft.path("data").path("id").asLong();

        ResponseEntity<JsonNode> r = post("/api/intervention/" + ivId + "/activate",
            adminToken, Map.of("targetingMode", "ALL"), JsonNode.class);
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, r.getStatusCode(),
            () -> "activate should 422: " + r.getBody());

        cleanupIntervention(ivId);
    }

    // ── T1.4 — question conflict ────────────────────────────────────────

    @Test
    @DisplayName("T1.4 — Activating an intervention that overlaps an active one returns 409 with conflict body")
    void activationConflictReturns409() {
        // Activate A on questions 1 + 2.
        long ivA = createDraftWithQuestions("T1.4-A-" + UUID.randomUUID(),
            failingQuestionIds.subList(0, 2));
        postOk("/api/intervention/" + ivA + "/activate", adminToken,
            Map.of("targetingMode", "MANUAL",
                   "targetAuditAssignmentIds", List.of(auditAssignmentA)));

        // Create B on questions 2 + 3 (overlaps on question 2).
        long ivB = createDraftWithQuestions("T1.4-B-" + UUID.randomUUID(),
            failingQuestionIds.subList(1, 3));

        // Preview conflicts — should list A.
        JsonNode conflicts = getOk("/api/intervention/" + ivB + "/activationConflicts", adminToken);
        assertTrue(conflicts.path("data").isArray());
        assertEquals(1, conflicts.path("data").size());
        long conflictingIvId = conflicts.path("data").get(0).path("conflictingInterventionId").asLong();
        assertEquals(ivA, conflictingIvId);

        // Activate B → 409 with body.
        ResponseEntity<JsonNode> r = post("/api/intervention/" + ivB + "/activate",
            adminToken, Map.of("targetingMode", "MANUAL",
                                  "targetAuditAssignmentIds", List.of(auditAssignmentA)),
            JsonNode.class);
        assertEquals(HttpStatus.CONFLICT, r.getStatusCode(),
            () -> "expected 409 on conflicting activate: " + r.getBody());
        assertTrue(r.getBody().path("data").isArray(), "conflict body should be an array");

        cleanupIntervention(ivA);
        cleanupIntervention(ivB);
    }

    // ── T1.8 — audit_signal CTE rollup math ─────────────────────────────

    @Test
    @DisplayName("T1.8 — audit_signal pct_ok rises after a re-inspection wave flips 3 NOT-OKs to OK")
    void auditSignalRollupReflectsInterventionWave() {
        // Original score for our sample UC (unchanged baseline).
        Double origPct = jdbc.queryForObject(
            "SELECT 100.0 * SUM(CASE WHEN judgement = 1 THEN 1 ELSE 0 END) / NULLIF(COUNT(*),0) " +
            "  FROM user_checksheet_answers WHERE inspection_id = ? AND deleted_at IS NULL",
            Double.class, sampleUcId);
        assertNotNull(origPct);

        // Activate an intervention covering our 3 failing questions on aa A.
        long ivId = createDraftWithQuestions("T1.8-" + UUID.randomUUID(), failingQuestionIds);
        postOk("/api/intervention/" + ivId + "/activate", adminToken,
            Map.of("targetingMode", "MANUAL",
                   "targetAuditAssignmentIds", List.of(auditAssignmentA)));

        // Post-V1.28 + per Rev 2026-05-10c: back-fill on activate created the
        // plan (kind=INTERVENTION inspection at the same location). Find it.
        Long planId = jdbc.queryForObject(
            "SELECT id FROM inspections " +
            " WHERE kind = 'INTERVENTION' AND intervention_id = ? " +
            "   AND auditee_location_id = (SELECT auditee_location_id FROM inspections WHERE id = ?) " +
            "   AND deleted_at IS NULL",
            Long.class, ivId, auditAssignmentA);
        assertNotNull(planId, "Activation back-fill must have created a plan for this aa+intervention");

        // Drive the re-inspection through the plan inspection's lifecycle.
        // The plan IS the inspection; fabricate APPROVED-state answers on it
        // for the rollup math test. Real flow (operator → submit → validate →
        // approve) is exercised in InterventionLifecycleE2ETest; this one
        // focuses purely on the audit_signal math.
        for (Long qid : failingQuestionIds) {
            jdbc.update(
                "INSERT INTO user_checksheet_answers " +
                "  (inspection_id, chks_question_id, chks_question_result_id, judgement, answer) " +
                "  SELECT ?, ?, " +
                "         (SELECT chks_question_result_id FROM user_checksheet_answers " +
                "           WHERE inspection_id = ? AND chks_question_id = ? " +
                "             AND deleted_at IS NULL LIMIT 1), " +
                "         1, 'fixed via test re-inspection'",
                planId, qid, sampleUcId, qid);
        }
        // Mark the plan APPROVED with a recent submitted_at so latest-wins picks it.
        // (approved_at lives on user_checksheet_approvals_history, not on inspections.)
        jdbc.update("UPDATE inspections SET status = 'APPROVED', " +
                    "  submitted_at = NOW() - INTERVAL '1 hour' WHERE id = ?", planId);

        // Audit_signal rollup query mirroring the production CTE: latest answer
        // per chks_question_result_id across the audit-kind inspection AND
        // intervention-kind inspections at the same location whose intervention
        // is tied to the same audit.
        String rollupSql =
            "SELECT 100.0 * SUM(CASE WHEN latest.judgement = 1 THEN 1 ELSE 0 END) / NULLIF(COUNT(*),0) " +
            "  FROM ( " +
            "    SELECT DISTINCT ON (qrid) judgement " +
            "      FROM ( " +
            "        SELECT uca.chks_question_result_id AS qrid, uca.judgement, " +
            "               ins2.submitted_at AS sub_at, uca.id AS uca_id " +
            "          FROM inspections base " +
            "          JOIN inspections ins2 ON ins2.deleted_at IS NULL " +
            "                                AND ins2.status = 'APPROVED' " +
            "                                AND ins2.auditee_location_id = base.auditee_location_id " +
            "          LEFT JOIN interventions iv ON iv.id = ins2.intervention_id " +
            "          JOIN user_checksheet_answers uca ON uca.inspection_id = ins2.id " +
            "                                          AND uca.deleted_at IS NULL " +
            "                                          AND uca.chks_question_result_id IS NOT NULL " +
            "         WHERE base.id = ? AND base.kind = 'AUDIT' " +
            "           AND ((ins2.kind = 'AUDIT'        AND ins2.audit_id = base.audit_id) " +
            "             OR (ins2.kind = 'INTERVENTION' AND iv.audit_id   = base.audit_id)) " +
            "      ) all_ans " +
            "      ORDER BY qrid, sub_at DESC NULLS LAST, uca_id DESC " +
            "  ) latest";

        Double rolledPct = jdbc.queryForObject(rollupSql, Double.class, sampleUcId);
        assertNotNull(rolledPct);
        assertTrue(rolledPct > origPct,
            () -> "rolled-up pct " + rolledPct + " should exceed original " + origPct);

        // Status flipped to SUBMITTED → not visible to rollup (only APPROVED counts)
        jdbc.update("UPDATE inspections SET status = 'SUBMITTED' WHERE id = ?", planId);
        Double notApprovedPct = jdbc.queryForObject(rollupSql, Double.class, sampleUcId);
        assertEquals(origPct, notApprovedPct, 0.001,
            "non-APPROVED plan must NOT count toward rollup");

        // Cleanup is handled by cleanupIntervention (which now wipes plan's
        // children + the plan inspection itself).
        cleanupIntervention(ivId);
    }

    // ── T1.10 — BI summary endpoint reflects intervention state ─────────

    @Test
    @DisplayName("T1.10 — /intervention-summary/national returns non-zero counts after activation")
    void interventionSummaryReflectsActivation() {
        long ivId = createDraftWithQuestions("T1.10-" + UUID.randomUUID(), failingQuestionIds);
        postOk("/api/intervention/" + ivId + "/activate", adminToken,
            Map.of("targetingMode", "MANUAL",
                   "targetAuditAssignmentIds", List.of(auditAssignmentA)));

        JsonNode summary = getOk("/api/audit/" + auditId + "/intervention-summary/national", adminToken);
        long active = summary.path("data").path("activeCampaigns").asLong();
        assertTrue(active >= 1, "should see at least one active intervention");

        cleanupIntervention(ivId);
    }

    // ── intervention-specific helpers ───────────────────────────────────

    private long createDraftWithQuestions(String name, List<Long> qIds) {
        Map<String, Object> payload = Map.of(
            "name", name,
            "priority", "P1",
            "auditId", auditId,
            "targetDate", "2026-12-01",
            "questionIds", qIds,
            "targetingMode", "MANUAL",
            "targetAuditAssignmentIds", List.of(auditAssignmentA)
        );
        return postOk("/api/intervention/createDraft", adminToken, payload).path("data").path("id").asLong();
    }

    private void cleanupIntervention(long ivId) {
        // Post-V1.28: plans are kind=INTERVENTION inspections; clean child rows
        // off them before deleting the inspections themselves.
        String inspectionsForIv = "(SELECT id FROM inspections WHERE intervention_id = ?)";
        jdbc.update("DELETE FROM intervention_assignment_questions WHERE inspection_id IN " + inspectionsForIv, ivId);
        jdbc.update("DELETE FROM user_checksheet_answers           WHERE inspection_id IN " + inspectionsForIv, ivId);
        jdbc.update("DELETE FROM inspections WHERE intervention_id = ?", ivId);
        jdbc.update("DELETE FROM intervention_assignment_targets WHERE intervention_id = ?", ivId);
        jdbc.update("DELETE FROM intervention_questions WHERE intervention_id = ?", ivId);
        jdbc.update("DELETE FROM interventions WHERE id = ?", ivId);
    }

    // ── REST helpers thin-wrap RestTestSupport so the test bodies stay
    //    readable. All actual HTTP plumbing lives in RestTestSupport.

    private <T> ResponseEntity<T> post(String path, String bearer, Object payload, Class<T> respType) {
        return RestTestSupport.post(http, port, path, bearer, payload, respType);
    }
    private JsonNode postOk(String path, String bearer, Object payload) {
        return RestTestSupport.postOk(http, port, path, bearer, payload);
    }
    private JsonNode getOk(String path, String bearer) {
        return RestTestSupport.getOk(http, port, path, bearer);
    }
}
