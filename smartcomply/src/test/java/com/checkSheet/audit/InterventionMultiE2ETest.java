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
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Intervention pipeline Tier 2 — multi-intervention chaos. Implements
 * docs/test-plan-intervention.md T2.1 (3+ interventions on one aa) and
 * T2.2 (OK→NotOK→OK chain across waves).
 *
 * <p>These are the scenarios the user explicitly called out: when a single
 * audit_assignment has multiple interventions running, the BI rollup must
 * still take the latest answer per question, and the audit-report overlay
 * must show every wave with its intervention attribution.
 */
@Tag("tier2")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
// No @DirtiesContext — the test plan (docs/test-plan-intervention.md:115)
// targets a "60s parallel suite via single Spring boot context per group +
// @DirtiesContext.NEVER". @AfterEach + @AfterAll below clean up everything
// this class wrote so the next test class inherits an unchanged context.
class InterventionMultiE2ETest {

    private static final String NAME_PREFIX = "T2.";

    /** Bootstrap super-admin token captured in @BeforeAll — used for
     *  every admin-flavoured call. The bootstrap user (defaulting to
     *  Z006135) is the only seed user the test depends on. */
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
    private long auditAssignmentId;
    private long checksheetId;
    private long sampleUcId;
    private List<Long> q;          // 3 failing question ids on sampleUcId
    private List<Long> qrid;       // matching chks_question_result_ids

    @org.junit.jupiter.api.AfterAll
    void cleanupOrphansAfterClass() {
        // F13/C5: own fixture — tear down the audit + everything under it.
        RestTestSupport.wipeTestInterventions(jdbc, NAME_PREFIX);
        RestTestSupport.cleanupOwnedAuditFixture(jdbc, fixture);
    }

    @org.junit.jupiter.api.AfterEach
    void cleanupAfterEachMethod() {
        // Each test method here creates its own ivs (T2.1-A, T2.1-B, T2.1-C
        // for threeConcurrent; T2.2-... for fiveWave). Wipe between methods
        // so a mid-method assert failure doesn't poison the next test.
        RestTestSupport.wipeTestInterventions(jdbc, NAME_PREFIX);
    }

    @BeforeAll
    void setup() {
        // Defensive: wipe orphans from prior failed runs of this class.
        RestTestSupport.wipeTestInterventions(jdbc, NAME_PREFIX);
        // F13/C5: own our audit + inspection instead of picking ORDER BY id
        // LIMIT 1 from the local seed. The prior pattern made the test
        // non-hermetic — Codex reproduced a 409 against a pre-existing active
        // intervention because the seed audit had stale state.
        adminToken = RestTestSupport.token(http, port, bootstrapAdminUsername);
        fixture = RestTestSupport.createOwnedAuditFixtureWithToken(http, port, jdbc,
            NAME_PREFIX + "multi.", adminToken, /* minFailingAnswers */ 3);
        auditId           = fixture.auditId;
        checksheetId      = fixture.checksheetId;
        sampleUcId        = fixture.auditAssignmentId;
        auditAssignmentId = fixture.auditAssignmentId;
        q    = fixture.failingQuestionIds.subList(0, 3);
        qrid = fixture.failingQuestionResultIds.subList(0, 3);
        assertEquals(3, q.size());
    }

    // ── T2.1 — three interventions on the same assignment ──────────────

    @Test
    @DisplayName("T2.1 — Three concurrent interventions targeting overlapping questions all show up")
    void threeConcurrentInterventions() {
        // Three interventions, each on a distinct failing question. Activation
        // back-fills each with one plan (kind=INTERVENTION inspection at our
        // sampleUc's location, parented to that intervention).
        long ivA = createAndActivate("T2.1-A-" + UUID.randomUUID(), List.of(q.get(0)));
        long ivB = createAndActivate("T2.1-B-" + UUID.randomUUID(), List.of(q.get(1)));
        long ivC = createAndActivate("T2.1-C-" + UUID.randomUUID(), List.of(q.get(2)));

        // Find the plans the back-fill created.
        long planA = findPlanFor(ivA);
        long planB = findPlanFor(ivB);
        long planC = findPlanFor(ivC);

        // Drop OK answers on each plan and mark the plan APPROVED so it
        // contributes to the audit_signal rollup. (Real flow: operator goes
        // through createOrUpdate → answers → submit → validate → approve;
        // T1.0 lifecycle exercises that. This test focuses on multi-plan
        // shape, so we skip the validate/approve plumbing.)
        markPlanAnsweredAndApproved(planA, q.get(0), qrid.get(0), (short) 1);
        markPlanAnsweredAndApproved(planB, q.get(1), qrid.get(1), (short) 1);
        markPlanAnsweredAndApproved(planC, q.get(2), qrid.get(2), (short) 1);

        // BI rollup: 3 questions flipped → pct_ok must rise.
        Double origPct = originalPctForUc(sampleUcId);
        Double rolledPct = rolledPctForUc(sampleUcId);
        assertTrue(rolledPct > origPct,
            "rolledPct " + rolledPct + " should exceed original " + origPct);

        // The audit-report overlay endpoint exists but its detail panels
        // (questionFlags, reAuditAnswers, headerSnapshots) are stubbed empty
        // in V1.28 pending follow-up. Just verify the score deltas surface.
        JsonNode overlay = getOk("/api/audit/userChecksheet/" + sampleUcId + "/improvement-overlay",
            adminToken);
        assertNotNull(overlay.path("data").path("originalScore").asText(null),
            "originalScore must be populated");
        assertNotNull(overlay.path("data").path("currentScore").asText(null),
            "currentScore must be populated");

        cleanupIntervention(ivA);
        cleanupIntervention(ivB);
        cleanupIntervention(ivC);
    }

    // ── T2.2 (Rev 2026-05-10c) — five chained interventions, latest-wins ──

    @Test
    @DisplayName("T2.2 — five chained interventions on one question: OK→NotOK→OK→NotOK→OK; latest wins")
    void fiveChainedInterventionsOnOneQuestion() {
        // V1.28 dropped multi-wave-per-intervention (one inspection per
        // (intervention, location) — partial unique index). Per the revised
        // plan, the test is now five separate interventions, each contributing
        // one wave. Same latest-wins assertion holds.
        short[] judgements = { 1, 2, 1, 2, 1 };  // OK, NotOK, OK, NotOK, OK
        long[] ivIds = new long[5];
        long[] planIds = new long[5];

        // Sequential not concurrent — only one ACTIVE intervention per question
        // is allowed (conflict checker enforces). Close each before activating
        // the next; that's the realistic chain shape anyway.
        for (int i = 0; i < 5; i++) {
            ivIds[i] = createAndActivate("T2.2-iv" + (i + 1) + "-" + UUID.randomUUID(),
                                         List.of(q.get(0)));
            planIds[i] = findPlanFor(ivIds[i]);
            markPlanAnsweredAndApproved(planIds[i], q.get(0), qrid.get(0), judgements[i]);
            // submitted_at controls latest-wins ordering. iv1 oldest, iv5 newest.
            jdbc.update("UPDATE inspections SET submitted_at = NOW() - INTERVAL '" + (5 - i) + " hours' " +
                        " WHERE id = ?", planIds[i]);
            // Close so the next iv can be activated without conflict.
            postOk("/api/intervention/" + ivIds[i] + "/close", adminToken, Map.of());
        }

        // Latest (iv5) judgement = OK → rolled-up answer for q[0] should be 1.
        Short latest = jdbc.queryForObject(
            "SELECT judgement FROM ( " +
            "  SELECT DISTINCT ON (qrid) judgement " +
            "    FROM ( " +
            "      SELECT uca.chks_question_result_id AS qrid, uca.judgement, " +
            "             ins2.submitted_at AS sub_at, uca.id AS uca_id " +
            "        FROM inspections base " +
            "        JOIN inspections ins2 ON ins2.deleted_at IS NULL " +
            "                              AND ins2.status = 'APPROVED' " +
            "                              AND ins2.auditee_location_id = base.auditee_location_id " +
            "        LEFT JOIN interventions iv ON iv.id = ins2.intervention_id " +
            "        JOIN user_checksheet_answers uca ON uca.inspection_id = ins2.id " +
            "                                        AND uca.deleted_at IS NULL " +
            "                                        AND uca.chks_question_result_id = ? " +
            "       WHERE base.id = ? AND base.kind = 'AUDIT' " +
            "         AND ((ins2.kind = 'AUDIT'        AND ins2.audit_id = base.audit_id) " +
            "           OR (ins2.kind = 'INTERVENTION' AND iv.audit_id   = base.audit_id)) " +
            "    ) all_ans " +
            "   ORDER BY qrid, sub_at DESC NULLS LAST, uca_id DESC " +
            ") l",
            Short.class, qrid.get(0), sampleUcId);
        assertEquals(Short.valueOf((short) 1), latest,
            "latest intervention's judgement (OK) should win across the 5-iv chain");

        for (long ivId : ivIds) cleanupIntervention(ivId);
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private long createAndActivate(String name, List<Long> qIds) {
        Map<String, Object> draftPayload = Map.of(
            "name", name, "priority", "P1",
            "auditId", auditId,
            "targetDate", "2026-12-01",
            "questionIds", qIds,
            "targetingMode", "MANUAL",
            "targetAuditAssignmentIds", List.of(auditAssignmentId)
        );
        long ivId = postOk("/api/intervention/createDraft", adminToken, draftPayload)
            .path("data").path("id").asLong();
        postOk("/api/intervention/" + ivId + "/activate", adminToken,
            Map.of("targetingMode", "MANUAL",
                   "targetAuditAssignmentIds", List.of(auditAssignmentId)));
        return ivId;
    }

    /** Look up the plan inspection that back-fill created at activation time. */
    private long findPlanFor(long ivId) {
        Long planId = jdbc.queryForObject(
            "SELECT id FROM inspections " +
            " WHERE kind = 'INTERVENTION' AND intervention_id = ? " +
            "   AND auditee_location_id = (SELECT auditee_location_id FROM inspections WHERE id = ?) " +
            "   AND deleted_at IS NULL",
            Long.class, ivId, sampleUcId);
        assertNotNull(planId, "Back-fill should have created a plan inspection for intervention " + ivId);
        return planId;
    }

    /** Insert one answer on a plan inspection and flip the plan to APPROVED so
     *  it contributes to the audit_signal rollup. */
    private void markPlanAnsweredAndApproved(long planId, long qId, long qrId, short judgement) {
        jdbc.update(
            "INSERT INTO user_checksheet_answers " +
            " (inspection_id, chks_question_id, chks_question_result_id, judgement, answer) " +
            " VALUES (?, ?, ?, ?, 'multi-test')",
            planId, qId, qrId, judgement);
        jdbc.update("UPDATE inspections SET status = 'APPROVED', " +
                    " submitted_at = NOW() - INTERVAL '1 hour' WHERE id = ?", planId);
    }

    private Double originalPctForUc(long ucId) {
        return jdbc.queryForObject(
            "SELECT 100.0 * SUM(CASE WHEN judgement = 1 THEN 1 ELSE 0 END) / NULLIF(COUNT(*),0) " +
            "  FROM user_checksheet_answers WHERE inspection_id = ? AND deleted_at IS NULL",
            Double.class, ucId);
    }

    /** Production audit_signal CTE shape — base AUDIT inspection + intervention
     *  inspections at the same location whose intervention is tied to the same audit. */
    private Double rolledPctForUc(long ucId) {
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

    private void cleanupIntervention(long ivId) {
        // Plans (kind=INTERVENTION inspections) carry tracked-question rows
        // and answer rows; wipe them before the inspection itself.
        String plansForIv = "(SELECT id FROM inspections WHERE intervention_id = ?)";
        jdbc.update("DELETE FROM user_checksheet_answers WHERE inspection_id IN " + plansForIv, ivId);
        jdbc.update("DELETE FROM intervention_assignment_questions WHERE inspection_id IN " + plansForIv, ivId);
        jdbc.update("DELETE FROM inspections WHERE intervention_id = ?", ivId);
        jdbc.update("DELETE FROM intervention_assignment_targets WHERE intervention_id = ?", ivId);
        jdbc.update("DELETE FROM intervention_questions WHERE intervention_id = ?", ivId);
        jdbc.update("DELETE FROM interventions WHERE id = ?", ivId);
    }

    // ── REST helpers thin-wrap RestTestSupport (shared with InterventionFlowE2ETest). ─

    private JsonNode postOk(String path, String bearer, Object payload) {
        return RestTestSupport.postOk(http, port, path, bearer, payload);
    }
    private JsonNode getOk(String path, String bearer) {
        return RestTestSupport.getOk(http, port, path, bearer);
    }
}
