package com.checkSheet.audit;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shared HTTP helpers for the audit E2E test classes. Removes the ~50
 * lines of token/post/postOk/getOk copy-paste from
 * {@link InterventionFlowE2ETest} and {@link InterventionMultiE2ETest}
 * (and any future test class). Static methods because tests aren't
 * subclasses — composition over inheritance.
 *
 * <p>Convention: every method takes the {@link TestRestTemplate} +
 * {@code port} explicitly so the helper has no Spring context dependency
 * of its own.
 */
final class RestTestSupport {
    static final String DEFAULT_PWD = "12345678";

    /** Test classes inject the bootstrap super-admin via Spring's
     *  {@code @Value("${app.test.bootstrap-user:Z006135}")} so the value
     *  is driven by the active profile's application-&lt;env&gt;.properties
     *  file (key: {@code app.test.bootstrap-user}) — same file Spring is
     *  already loading for DB credentials etc.
     *
     *  This is the single seed user the test suite depends on — everyone
     *  else (operators / validators / approvers) is created in
     *  {@link #createTestUser} during @BeforeAll and deleted in @AfterAll. */

    /** Test user record returned by {@link #createTestUser}. */
    static final class TestUser {
        final long id;
        final String username;
        final String password;
        final String roleCode;
        final long roleId;
        final long departmentId;
        final String accessToken;
        TestUser(long id, String username, String password, String roleCode,
                 long roleId, long departmentId, String accessToken) {
            this.id = id;
            this.username = username;
            this.password = password;
            this.roleCode = roleCode;
            this.roleId = roleId;
            this.departmentId = departmentId;
            this.accessToken = accessToken;
        }
    }

    /** Create a user via POST /api/user/createOrEditOperator, then log them in.
     *  Username is suffixed with a uniqueness nonce so concurrent runs and
     *  re-runs never collide. The endpoint encodes password = username
     *  uppercased (UserServiceImpl), so callers don't pick the password.
     *
     *  @param roleCode one of SUPER_ADMIN / DEPT_ADMIN / SUBDEPT_ADMIN / OPERATOR
     *  @param prefix   human-readable tag for the username, e.g. "VAL", "OP-A"
     */
    static TestUser createTestUser(TestRestTemplate http, int port, JdbcTemplate jdbc,
                                   String adminToken, String roleCode, String prefix) {
        Long roleId = jdbc.queryForObject(
            "SELECT id FROM roles WHERE role_code = ?", Long.class, roleCode);
        assertNotNull(roleId, "role_code not found: " + roleCode);
        // Pick the Sales master department if it exists; otherwise the first
        // department row. Backend uses department_id for permission scoping
        // but our tests don't care which specific department.
        Long deptId = jdbc.query(
            "SELECT id FROM departments WHERE name = 'Sales' AND department_id IS NULL " +
            "  UNION ALL SELECT id FROM departments ORDER BY 1 LIMIT 1",
            (rs, i) -> rs.getLong(1)).stream().findFirst().orElse(null);
        assertNotNull(deptId, "no department row exists — DB unseeded?");

        String nonce = UUID.randomUUID().toString().substring(0, 8);
        String username = (prefix + "_" + nonce).toUpperCase();
        String password = username; // backend hardcodes to uppercased username
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("username", username);
        body.put("firstName", prefix);
        body.put("lastName", "Test");
        body.put("email", username.toLowerCase() + "@example.com");
        body.put("mobile", "9000000000");
        body.put("roleIds", List.of(roleId));
        body.put("departmentIds", List.of(deptId));
        JsonNode created = postOk(http, port, "/api/user/createOrEditOperator", adminToken, body);
        long userId = created.path("data").path("id").asLong();
        if (userId <= 0) {
            // Some envelopes return the user object directly.
            userId = created.path("data").path("userId").asLong();
        }
        // Operator-roled users log in via the mobile path (deviceType=APP).
        String deviceType = "OPERATOR".equals(roleCode) ? "APP" : "WEB";
        Map<String, String> loginBody = Map.of(
            "username", username, "password", password, "deviceType", deviceType);
        ResponseEntity<JsonNode> loginResp = post(http, port, "/api/user/login", null, loginBody, JsonNode.class);
        assertEquals(HttpStatus.OK, loginResp.getStatusCode(),
            () -> "test-user login failed for " + username + ": " + loginResp.getBody());
        String token = loginResp.getBody().path("data").path("accessToken").asText();
        return new TestUser(userId, username, password, roleCode, roleId, deptId, token);
    }

    /** Hard-delete the user_role_departments row for this user via
     *  POST /api/user/deleteUser, then drop the users row itself to free
     *  the (unique) username + email so the next run can recreate. The
     *  endpoint is idempotent — call freely from @AfterAll. */
    static void deleteTestUser(TestRestTemplate http, int port, JdbcTemplate jdbc,
                               String adminToken, TestUser user) {
        if (user == null) return;
        try {
            Map<String, Object> body = Map.of(
                "id", user.id,
                "roleId", user.roleId,
                "departmentId", user.departmentId
            );
            post(http, port, "/api/user/deleteUser", adminToken, body, JsonNode.class);
        } catch (Exception ignored) {
            // Best-effort.
        }
        // Defensive: if the API endpoint kept the users row around (it
        // typically only drops the user_role_departments link), hard-delete
        // so re-runs don't collide on the unique username.
        try {
            jdbc.update("DELETE FROM user_role_departments WHERE user_id = ?", user.id);
            jdbc.update("DELETE FROM refresh_token WHERE user_id = ?", user.id);
            jdbc.update("DELETE FROM users WHERE id = ?", user.id);
        } catch (Exception ignored) {
            // Best-effort.
        }
    }

    /** Snapshot + reduce: replace the checksheet's per-stage actor array
     *  with EXACTLY {@code [userId]}, returning the original array so the
     *  caller can restore it in @AfterAll via {@link #restoreChecksheetActors}.
     *
     *  Why replace rather than append: validate/approve transitions only
     *  flip the inspection status to VALIDATED/APPROVED when the count of
     *  recorded validations equals the count of declared validators on
     *  the checksheet (see UserChecksheetValidationServiceImpl L134). If
     *  we just appended our test validator, we'd need every seed
     *  validator to also act before the transition fires. Replacing makes
     *  the test validator the sole declared actor for the duration of
     *  the test.
     *
     *  @param stage one of "VALIDATOR", "APPROVER", "DATA_VALIDATOR",
     *               "DATA_APPROVER", "OPERATOR"
     *  @return the original array contents — pass back to {@link #restoreChecksheetActors}.
     */
    static long[] replaceChecksheetActor(JdbcTemplate jdbc, long checksheetId, long userId, String stage) {
        String col = checksheetActorColumn(stage);
        Long[] prior = jdbc.queryForObject(
            "SELECT COALESCE(" + col + ", '{}'::bigint[]) FROM checksheets WHERE id = ?",
            (rs, i) -> {
                java.sql.Array arr = rs.getArray(1);
                if (arr == null) return new Long[0];
                Object o = arr.getArray();
                if (o instanceof Long[] la) return la;
                if (o instanceof Object[] oa) {
                    Long[] out = new Long[oa.length];
                    for (int k = 0; k < oa.length; k++) out[k] = ((Number) oa[k]).longValue();
                    return out;
                }
                return new Long[0];
            }, checksheetId);
        jdbc.update(
            "UPDATE checksheets SET " + col + " = ARRAY[?::bigint] WHERE id = ?",
            userId, checksheetId);
        long[] result = new long[prior.length];
        for (int i = 0; i < prior.length; i++) result[i] = prior[i];
        return result;
    }

    /** Restore the checksheet's per-stage actor array to {@code original}. */
    static void restoreChecksheetActors(JdbcTemplate jdbc, long checksheetId, long[] original, String stage) {
        try {
            String col = checksheetActorColumn(stage);
            if (original == null || original.length == 0) {
                jdbc.update("UPDATE checksheets SET " + col + " = '{}'::bigint[] WHERE id = ?", checksheetId);
                return;
            }
            // Build "ARRAY[a,b,c]::bigint[]" param-bound — Postgres needs
            // explicit array param for bigint[].
            Long[] boxed = new Long[original.length];
            for (int i = 0; i < original.length; i++) boxed[i] = original[i];
            jdbc.update(
                con -> {
                    java.sql.Array arr = con.createArrayOf("bigint", boxed);
                    var ps = con.prepareStatement(
                        "UPDATE checksheets SET " + col + " = ? WHERE id = ?");
                    ps.setArray(1, arr);
                    ps.setLong(2, checksheetId);
                    return ps;
                });
        } catch (Exception ignored) {
            // Best-effort.
        }
    }

    /** Back-compat shim: keep the old append-style helper for callers
     *  that don't need exclusive ownership of the actor slot (e.g. adding
     *  an operator to an audit's operator_user_ids — the operator-check
     *  is a list-membership test, not a count-of-acts test). */
    static void addChecksheetActor(JdbcTemplate jdbc, long checksheetId, long userId, String stage) {
        String col = checksheetActorColumn(stage);
        jdbc.update(
            "UPDATE checksheets SET " + col + " = ARRAY(SELECT DISTINCT unnest(" +
            "  COALESCE(" + col + ", '{}') || ARRAY[?::bigint])) WHERE id = ?",
            userId, checksheetId);
    }

    static void removeChecksheetActor(JdbcTemplate jdbc, long checksheetId, long userId, String stage) {
        try {
            String col = checksheetActorColumn(stage);
            jdbc.update(
                "UPDATE checksheets SET " + col + " = array_remove(" + col + ", ?::bigint) WHERE id = ?",
                userId, checksheetId);
        } catch (Exception ignored) {
            // Best-effort.
        }
    }

    private static String checksheetActorColumn(String stage) {
        return switch (stage) {
            case "VALIDATOR"      -> "validator_user_ids";
            case "APPROVER"       -> "approver_user_ids";
            case "DATA_VALIDATOR" -> "data_validator_user_ids";
            case "DATA_APPROVER"  -> "data_approver_user_ids";
            case "OPERATOR"       -> "operator_user_ids";
            default -> throw new IllegalArgumentException("Unknown stage: " + stage);
        };
    }

    private RestTestSupport() { /* utility */ }

    /** Login via /api/user/login and return the JWT. Asserts 200. */
    static String token(TestRestTemplate http, int port, String username) {
        return token(http, port, username, DEFAULT_PWD);
    }
    static String token(TestRestTemplate http, int port, String username, String password) {
        Map<String, String> body = Map.of("username", username, "password", password, "deviceType", "WEB");
        ResponseEntity<JsonNode> r = post(http, port, "/api/user/login", null, body, JsonNode.class);
        assertEquals(HttpStatus.OK, r.getStatusCode(), () -> "login " + username + ": " + r.getBody());
        return r.getBody().path("data").path("accessToken").asText();
    }

    static <T> ResponseEntity<T> post(TestRestTemplate http, int port,
                                      String path, String bearer, Object payload, Class<T> respType) {
        HttpEntity<Object> entity = new HttpEntity<>(payload, jsonHeaders(bearer));
        return http.exchange(url(port, path), HttpMethod.POST, entity, respType);
    }

    static JsonNode postOk(TestRestTemplate http, int port,
                           String path, String bearer, Object payload) {
        ResponseEntity<JsonNode> r = post(http, port, path, bearer, payload, JsonNode.class);
        assertEquals(HttpStatus.OK, r.getStatusCode(), () -> path + " failed: " + r.getBody());
        assertTrue(r.getBody().path("status").asBoolean(false), () -> path + ": " + r.getBody());
        return r.getBody();
    }

    static JsonNode getOk(TestRestTemplate http, int port, String path, String bearer) {
        HttpHeaders h = new HttpHeaders();
        if (bearer != null) h.setBearerAuth(bearer);
        ResponseEntity<JsonNode> r = http.exchange(url(port, path), HttpMethod.GET,
            new HttpEntity<>(h), JsonNode.class);
        assertEquals(HttpStatus.OK, r.getStatusCode(), () -> path + " failed: " + r.getBody());
        return r.getBody();
    }

    static HttpHeaders jsonHeaders(String bearer) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (bearer != null) h.setBearerAuth(bearer);
        return h;
    }

    static String url(int port, String path) {
        return "http://localhost:" + port + path;
    }

    /** Fixture record returned by {@link #createOwnedAuditFixture}. Holds the
     *  ids needed by the intervention tests: a fresh audit, an APPROVED
     *  audit-kind inspection on it with NOT-OK answers, and the question ids
     *  on those failing answers. */
    static final class OwnedAuditFixture {
        final long auditId;
        final long checksheetId;
        final long auditAssignmentId;        // post-V1.28: same as inspection id
        final long auditAssignmentBId;       // a second audit-kind inspection (for BY_REGION / MANUAL fan-out tests)
        final long auditeeLocationId;
        final List<Long> failingQuestionIds;
        final List<Long> failingQuestionResultIds;

        OwnedAuditFixture(long auditId, long checksheetId, long aaId, long aaBId,
                          long locId, List<Long> qIds, List<Long> qrIds) {
            this.auditId = auditId;
            this.checksheetId = checksheetId;
            this.auditAssignmentId = aaId;
            this.auditAssignmentBId = aaBId;
            this.auditeeLocationId = locId;
            this.failingQuestionIds = qIds;
            this.failingQuestionResultIds = qrIds;
        }
    }

    /** Create a fresh audit + assignments through the public API, then stamp
     *  one of the audit-kind inspections to APPROVED with at least {@code
     *  minFailingAnswers} NOT-OK answers copied from a seeded source UC. The
     *  copied answer rows carry chks_question_result_id so the intervention
     *  pipeline can match-by-question downstream.
     *
     *  <p>Test classes call this in @BeforeAll instead of picking demo seed
     *  rows via "ORDER BY id LIMIT 1" — that pattern made the tests
     *  non-hermetic (a 409 against a pre-existing intervention crashed runs
     *  that should have been isolated). The audit name is uniquified per
     *  invocation; teardown is the caller's responsibility (use {@link
     *  #cleanupOwnedAuditFixture}).
     *
     *  @param namePrefix unique-per-class tag, e.g. "T1.flow." — used in the
     *                    audit name and as a leak-cleanup prefix.
     *  @param adminUsername user with AUDIT_VIEW (e.g. the bootstrap
     *      SUPER_ADMIN, default Z006135 via {@link #bootstrapAdminUsername}).
     */
    static OwnedAuditFixture createOwnedAuditFixture(TestRestTemplate http, int port,
                                                     JdbcTemplate jdbc,
                                                     String namePrefix, String adminUsername,
                                                     int minFailingAnswers) {
        return createOwnedAuditFixtureWithToken(http, port, jdbc, namePrefix,
            token(http, port, adminUsername), minFailingAnswers);
    }

    /** Token-based overload — preferred for tests that have already
     *  logged in as the bootstrap super-admin (so we don't make a second
     *  /api/user/login round-trip for the same user). */
    static OwnedAuditFixture createOwnedAuditFixtureWithToken(TestRestTemplate http, int port,
                                                              JdbcTemplate jdbc,
                                                              String namePrefix, String adminToken,
                                                              int minFailingAnswers) {

        // 1. Find a seeded APPROVED audit-kind inspection with enough NOT-OK
        //    answers to copy. We don't depend on its audit_id — we copy the
        //    answer rows onto OUR fresh inspection. Stable order by id so
        //    re-runs pick the same source; resilience against a flaky source
        //    is one of the goals of this helper.
        Long sourceUcId = jdbc.queryForObject(
            "SELECT uc.id FROM inspections uc " +
            "  JOIN user_checksheet_answers uca ON uca.inspection_id = uc.id " +
            " WHERE uc.kind = 'AUDIT' AND uc.status = 'APPROVED' AND uc.deleted_at IS NULL " +
            "   AND uca.judgement <> 1 AND uca.deleted_at IS NULL " +
            "   AND uca.chks_question_id IS NOT NULL AND uca.chks_question_result_id IS NOT NULL " +
            " GROUP BY uc.id " +
            " HAVING COUNT(*) FILTER (WHERE uca.judgement <> 1) >= ? " +
            " ORDER BY uc.id LIMIT 1",
            Long.class, minFailingAnswers);
        assertNotNull(sourceUcId, "local DB must have an APPROVED audit-kind inspection with >= "
            + minFailingAnswers + " NOT-OK answers to copy from");
        Long sourceChecksheetId = jdbc.queryForObject(
            "SELECT checksheet_id FROM inspections WHERE id = ?", Long.class, sourceUcId);
        assertNotNull(sourceChecksheetId, "source inspection must have a checksheet_id");

        // 2. Pick two real auditee_locations not used by any existing
        //    audit-kind inspection of THIS checksheet (avoids collision with
        //    the partial uniqueness on (audit_id, auditee_location_id)).
        List<Long> locIds = jdbc.queryForList(
            "SELECT id FROM auditee_locations WHERE deleted_at IS NULL ORDER BY id LIMIT 2",
            Long.class);
        assertEquals(2, locIds.size(), "need 2 seeded auditee_locations");

        // 3. Create a fresh audit through the public API. Name is class-tagged
        //    + uuid so concurrent runs don't collide.
        String auditName = namePrefix + "owned-" + UUID.randomUUID();
        Map<String, Object> createBody = Map.of(
            "name", auditName,
            "checksheetId", sourceChecksheetId,
            "status", "ACTIVE"
        );
        JsonNode created = postOk(http, port, "/api/audit/createAudit", adminToken, createBody);
        long auditId = created.path("data").path("id").asLong();
        assertTrue(auditId > 0, "createAudit should return id; got: " + created);

        // 4. Attach assignments via API. Each assignment becomes one
        //    kind=AUDIT inspection (V1.28: the assignment IS the inspection).
        Map<String, Object> assignmentsBody = Map.of(
            "auditId", auditId,
            "assignments", List.of(
                Map.of("auditeeLocationId", locIds.get(0)),
                Map.of("auditeeLocationId", locIds.get(1))
            )
        );
        postOk(http, port, "/api/audit/addAuditAssignments", adminToken, assignmentsBody);

        Long aaA = jdbc.queryForObject(
            "SELECT id FROM inspections WHERE audit_id = ? AND auditee_location_id = ?",
            Long.class, auditId, locIds.get(0));
        Long aaB = jdbc.queryForObject(
            "SELECT id FROM inspections WHERE audit_id = ? AND auditee_location_id = ?",
            Long.class, auditId, locIds.get(1));
        assertNotNull(aaA, "primary audit-kind inspection missing");
        assertNotNull(aaB, "secondary audit-kind inspection missing");

        // 5. Stamp our primary aa APPROVED and copy NOT-OK answers from the
        //    seeded source. submitted_at = NOW-2h so it pre-dates anything an
        //    intervention back-fill might create.
        jdbc.update("UPDATE inspections SET status = 'APPROVED', " +
                    "  submitted_at = NOW() - INTERVAL '2 hours', " +
                    "  started_at   = NOW() - INTERVAL '3 hours' " +
                    " WHERE id = ?", aaA);
        jdbc.update(
            "INSERT INTO user_checksheet_answers " +
            "  (inspection_id, chks_question_id, chks_question_result_id, judgement, answer) " +
            "  SELECT ?, src.chks_question_id, src.chks_question_result_id, src.judgement, " +
            "         COALESCE(src.answer, 'owned-fixture') " +
            "    FROM user_checksheet_answers src " +
            "   WHERE src.inspection_id = ? AND src.deleted_at IS NULL " +
            "     AND src.chks_question_id IS NOT NULL AND src.chks_question_result_id IS NOT NULL ",
            aaA, sourceUcId);

        // 6. Look up the (qid, qrid) pairs for downstream test convenience —
        //    same order so tests can match by index.
        List<Map<String, Object>> qPairs = jdbc.queryForList(
            "SELECT chks_question_id AS qid, chks_question_result_id AS qrid " +
            "  FROM user_checksheet_answers " +
            " WHERE inspection_id = ? AND judgement <> 1 AND deleted_at IS NULL " +
            "   AND chks_question_id IS NOT NULL AND chks_question_result_id IS NOT NULL " +
            " ORDER BY id",
            aaA);
        assertTrue(qPairs.size() >= minFailingAnswers,
            "owned fixture should carry >= " + minFailingAnswers + " failing answers; got " + qPairs.size());
        List<Long> qIds  = qPairs.stream().map(r -> ((Number) r.get("qid")).longValue()).toList();
        List<Long> qrIds = qPairs.stream().map(r -> ((Number) r.get("qrid")).longValue()).toList();

        return new OwnedAuditFixture(auditId, sourceChecksheetId, aaA, aaB, locIds.get(0), qIds, qrIds);
    }

    /** Cascade-delete every row that {@link #createOwnedAuditFixture} put
     *  into the DB. Safe to call on a fixture whose tests partially failed —
     *  every step is unconditional + idempotent. */
    static void cleanupOwnedAuditFixture(JdbcTemplate jdbc, OwnedAuditFixture f) {
        if (f == null) return;
        long auditId = f.auditId;
        // Intervention-kind plans first (tests create them under our audit).
        String plansOfAudit =
            "(SELECT i.id FROM inspections i JOIN interventions iv ON iv.id = i.intervention_id " +
            "  WHERE iv.audit_id = ?)";
        jdbc.update("DELETE FROM intervention_assignment_questions WHERE inspection_id IN " + plansOfAudit, auditId);
        jdbc.update("DELETE FROM user_checksheet_answers           WHERE inspection_id IN " + plansOfAudit, auditId);
        jdbc.update("DELETE FROM user_checksheet_validations_history WHERE inspection_id IN " + plansOfAudit, auditId);
        jdbc.update("DELETE FROM user_checksheet_validations         WHERE inspection_id IN " + plansOfAudit, auditId);
        jdbc.update("DELETE FROM user_checksheet_approvals_history   WHERE inspection_id IN " + plansOfAudit, auditId);
        jdbc.update("DELETE FROM user_checksheet_approvals           WHERE inspection_id IN " + plansOfAudit, auditId);
        jdbc.update("DELETE FROM inspections WHERE id IN " + plansOfAudit, auditId);
        // Interventions themselves.
        jdbc.update("DELETE FROM intervention_assignment_targets WHERE intervention_id IN " +
                    "(SELECT id FROM interventions WHERE audit_id = ?)", auditId);
        jdbc.update("DELETE FROM intervention_questions WHERE intervention_id IN " +
                    "(SELECT id FROM interventions WHERE audit_id = ?)", auditId);
        jdbc.update("DELETE FROM interventions WHERE audit_id = ?", auditId);
        // Audit-kind inspections + answers under THIS audit.
        String ucsOfAudit = "(SELECT id FROM inspections WHERE audit_id = ?)";
        jdbc.update("DELETE FROM user_checksheet_answers WHERE inspection_id IN " + ucsOfAudit, auditId);
        jdbc.update("DELETE FROM user_checksheet_validations_history WHERE inspection_id IN " + ucsOfAudit, auditId);
        jdbc.update("DELETE FROM user_checksheet_validations WHERE inspection_id IN " + ucsOfAudit, auditId);
        jdbc.update("DELETE FROM user_checksheet_approvals_history WHERE inspection_id IN " + ucsOfAudit, auditId);
        jdbc.update("DELETE FROM user_checksheet_approvals WHERE inspection_id IN " + ucsOfAudit, auditId);
        jdbc.update("DELETE FROM inspections WHERE audit_id = ?", auditId);
        jdbc.update("DELETE FROM audits WHERE id = ?", auditId);
    }

    /** Cascade-delete every intervention whose name starts with the given
     *  prefix, plus its plans, tracked questions, target rows, and any UCs
     *  pointing at those plans. Used by every test class's @BeforeAll +
     *  @AfterAll hooks so test failures never leak orphans into the next
     *  class's run. Idempotent. */
    static void wipeTestInterventions(org.springframework.jdbc.core.JdbcTemplate jdbc, String namePrefix) {
        java.util.List<Long> ivIds = jdbc.queryForList(
            "SELECT id FROM interventions WHERE name LIKE ?", Long.class, namePrefix + "%");
        for (Long ivId : ivIds) {
            // Delete dependent rows on every INTERVENTION-kind inspection of this intervention.
            String inspectionsOfIv = "(SELECT id FROM inspections WHERE intervention_id = ?)";
            jdbc.update("DELETE FROM intervention_assignment_questions WHERE inspection_id IN " + inspectionsOfIv, ivId);
            jdbc.update("DELETE FROM user_checksheet_answers           WHERE inspection_id IN " + inspectionsOfIv, ivId);
            jdbc.update("DELETE FROM user_checksheet_validations_history WHERE inspection_id IN " + inspectionsOfIv, ivId);
            jdbc.update("DELETE FROM user_checksheet_validations         WHERE inspection_id IN " + inspectionsOfIv, ivId);
            jdbc.update("DELETE FROM user_checksheet_approvals_history   WHERE inspection_id IN " + inspectionsOfIv, ivId);
            jdbc.update("DELETE FROM user_checksheet_approvals           WHERE inspection_id IN " + inspectionsOfIv, ivId);
            jdbc.update("DELETE FROM inspections WHERE intervention_id = ?", ivId);
            jdbc.update("DELETE FROM intervention_assignment_targets WHERE intervention_id = ?", ivId);
            jdbc.update("DELETE FROM intervention_questions WHERE intervention_id = ?", ivId);
            jdbc.update("DELETE FROM interventions WHERE id = ?", ivId);
        }
    }
}
