package com.checkSheet.audit;

import com.checkSheet.DTO.AddAuditAssignmentsRequestDTO;
import com.checkSheet.DTO.AuditAssignmentCreateDTO;
import com.checkSheet.DTO.AuditDTO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * E2E coverage for issue #9 — file-upload validation on
 * POST /api/userChecksheet/createUserChksAnsFile.
 *
 * Three black-box scenarios driven through the public mobile contract:
 *  1. 11 MB body              → rejected at the multipart parser layer (413)
 *  2. text/plain content-type → rejected at the service layer (415)
 *  3. valid 1 KB image/jpeg   → accepted (200)
 *
 * Mirrors AuditFlowE2ETest setup so the test runs in the same suite, against
 * the same seeded DB, with the same auth pattern.
 */
@Tag("tier1")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class FileUploadValidationE2ETest {

    /** ID of the source APPROVED checksheet — resolved in @BeforeAll, not
     *  hardcoded. CI seeds id=1; local typically has id=15; UAT has its own. */
    private long checksheetId;

    @LocalServerPort int port;
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;

    private final ObjectMapper json = new ObjectMapper();
    private long auditId;
    private long aaId;
    private long auditeeLocId;
    private long ucId;
    private long answerId;

    // Test users created in @BeforeAll, deleted in @AfterAll. The
    // bootstrap super-admin is the only seed user we still depend on.
    private String adminToken;
    private RestTestSupport.TestUser PREPARER;
    private RestTestSupport.TestUser OPERATOR_A;

    /** Bootstrap super-admin username — see application-<env>.properties
     *  key {@code app.test.bootstrap-user}. */
    @org.springframework.beans.factory.annotation.Value("${app.test.bootstrap-user:Z006135}")
    private String bootstrapAdminUsername;

    @BeforeAll
    void setup() {
        // Pick the source checksheet dynamically (any APPROVED template).
        // Bootstrap admin + freshly-created test users — no KIA_* seed
        // dependency. Operator A is patched into the source checksheet's
        // operator list so addAuditAssignments accepts them as a valid
        // assignee.
        Long picked = jdbc.query(
            "SELECT id FROM checksheets WHERE status = 'APPROVED' AND deleted_at IS NULL " +
            " ORDER BY id LIMIT 1",
            (rs, i) -> rs.getLong(1)).stream().findFirst().orElse(null);
        assertNotNull(picked, "Need at least one APPROVED checksheet in the DB.");
        checksheetId = picked;

        adminToken = RestTestSupport.token(http, port, bootstrapAdminUsername);
        PREPARER   = RestTestSupport.createTestUser(http, port, jdbc, adminToken, "SUBDEPT_ADMIN", "FU_PREP");
        OPERATOR_A = RestTestSupport.createTestUser(http, port, jdbc, adminToken, "OPERATOR",      "FU_OPA");
        RestTestSupport.addChecksheetActor(jdbc, checksheetId, OPERATOR_A.id, "OPERATOR");

        // Pick one auditee_location seeded in the DB.
        List<Long> locs = jdbc.queryForList(
                "SELECT id FROM auditee_locations WHERE deleted_at IS NULL ORDER BY id LIMIT 1",
                Long.class);
        assertEquals(1, locs.size(), "need at least 1 auditee_location seeded");
        auditeeLocId = locs.get(0);

        // Create a fresh audit campaign so tests are isolated and re-runnable.
        AuditDTO toCreate = AuditDTO.builder()
                .name("E2E upload-validation " + UUID.randomUUID())
                .checksheetId(checksheetId)
                .status("ACTIVE")
                .build();
        ResponseEntity<JsonNode> r = post("/api/audit/createAudit", PREPARER.accessToken, toCreate, JsonNode.class);
        assertEquals(HttpStatus.OK, r.getStatusCode(), () -> "createAudit failed: " + r.getBody());
        auditId = r.getBody().path("data").path("id").asLong();

        // Assign operator A to that location.
        AddAuditAssignmentsRequestDTO req = new AddAuditAssignmentsRequestDTO();
        req.setAuditId(auditId);
        req.setAssignments(List.of(new AuditAssignmentCreateDTO(auditeeLocId, OPERATOR_A.id, null)));
        ResponseEntity<JsonNode> r2 = post("/api/audit/addAuditAssignments", PREPARER.accessToken, req, JsonNode.class);
        assertEquals(HttpStatus.OK, r2.getStatusCode());

        aaId = jdbc.queryForObject(
                "SELECT id FROM inspections WHERE audit_id=? AND auditee_location_id=? AND kind='AUDIT' AND deleted_at IS NULL",
                Long.class, auditId, auditeeLocId);

        // Operator A creates a UC + one answer that we'll attach files to.
        String aTok = OPERATOR_A.accessToken;
        Map<String, Object> ucReq = Map.of(
                "auditAssignmentId", aaId,
                "status", "IN_PROGRESS",
                "shift", "First",
                "startedAt", "2026-05-07 10:00:00.000",
                "submissionVersion", 0,
                "frequencyOfFreqOfChkCnt", 1
        );
        JsonNode ucBody = postOk("/api/userChecksheet/createOrUpdate", aTok, List.of(ucReq));
        ucId = ucBody.path("data").get(0).path("id").asLong();

        Long qrId = jdbc.queryForObject("""
                SELECT qr.id FROM chks_question_results qr
                  JOIN chks_questions q ON q.id = qr.chks_question_id
                 WHERE q.checksheet_id = ?
                 ORDER BY qr.id LIMIT 1
                """, Long.class, checksheetId);
        Map<String, Object> ans = Map.of(
                "userChecksheetId", ucId,
                "chksQuestionResultId", qrId,
                "isNotApplicable", false,
                "answeredAt", "2026-05-07 10:35:00.000",
                "judgement", 1,
                "answer", "0"
        );
        JsonNode ansBody = postOk("/api/userChecksheet/createOrUpdateUserChksAns", aTok, List.of(ans));
        answerId = ansBody.path("data").get(0).path("id").asLong();
    }

    @AfterAll
    void cleanup() {
        // V1.28: audit_assignments + user_checksheets collapsed into inspections (kind='AUDIT').
        // Child FK renamed user_checksheet_id → inspection_id.
        jdbc.update("""
            DELETE FROM user_checksheet_answer_files WHERE user_checksheet_answer_id IN
                (SELECT uca.id FROM user_checksheet_answers uca
                   JOIN inspections i ON i.id = uca.inspection_id
                  WHERE i.audit_id = ? AND i.kind = 'AUDIT')
            """, auditId);
        jdbc.update("""
            DELETE FROM user_checksheet_answers WHERE inspection_id IN
                (SELECT id FROM inspections WHERE audit_id = ? AND kind = 'AUDIT')
            """, auditId);
        jdbc.update("DELETE FROM inspections WHERE audit_id = ? AND kind = 'AUDIT'", auditId);
        jdbc.update("DELETE FROM audits WHERE id = ?", auditId);

        // Revert checksheet patch + delete the test users.
        RestTestSupport.removeChecksheetActor(jdbc, checksheetId, OPERATOR_A.id, "OPERATOR");
        RestTestSupport.deleteTestUser(http, port, jdbc, adminToken, OPERATOR_A);
        RestTestSupport.deleteTestUser(http, port, jdbc, adminToken, PREPARER);
    }

    // ─── Validation scenarios ───────────────────────────────────────────────

    @Test
    @DisplayName("Upload validation: 11 MB body is rejected with 413 (parser layer)")
    void uploadOversizedFile_returns413() {
        // 11 MB > 10 MB app limit AND > 11 MB parser limit → parser rejects
        // first (the parser cap is set to 11 MB exactly so anything strictly
        // larger trips it). We send 11 MB + 1 byte to be sure.
        int sizeBytes = 11 * 1024 * 1024 + 1;
        byte[] big = new byte[sizeBytes];
        ResponseEntity<JsonNode> r = uploadPhoto(OPERATOR_A.accessToken, answerId, "huge.jpg", "image/jpeg", big);

        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, r.getStatusCode(),
                () -> "expected 413, got " + r.getStatusCode() + " body=" + r.getBody());
        assertFalse(r.getBody().path("status").asBoolean(true),
                () -> "status should be false: " + r.getBody());
    }

    @Test
    @DisplayName("Upload validation: text/plain content-type is rejected with 415 (service layer)")
    void uploadWrongContentType_returns415() {
        byte[] tiny = "not an image".getBytes();
        ResponseEntity<JsonNode> r = uploadPhoto(OPERATOR_A.accessToken, answerId, "fake.txt", "text/plain", tiny);

        assertEquals(HttpStatus.UNSUPPORTED_MEDIA_TYPE, r.getStatusCode(),
                () -> "expected 415, got " + r.getStatusCode() + " body=" + r.getBody());
        assertFalse(r.getBody().path("status").asBoolean(true));
        assertTrue(r.getBody().path("message").asText().toLowerCase().contains("content type")
                || r.getBody().path("message").asText().toLowerCase().contains("unsupported"),
                () -> "expected content-type rejection message, got: " + r.getBody().path("message").asText());
    }

    @Test
    @DisplayName("Upload validation: 1 KB image/jpeg under 10 MB is accepted")
    void uploadValidImage_returns200() {
        byte[] tiny = new byte[1024]; // 1 KB
        ResponseEntity<JsonNode> r = uploadPhoto(OPERATOR_A.accessToken, answerId, "small.jpg", "image/jpeg", tiny);

        assertEquals(HttpStatus.OK, r.getStatusCode(),
                () -> "expected 200, got " + r.getStatusCode() + " body=" + r.getBody());
        assertTrue(r.getBody().path("status").asBoolean(false),
                () -> "status should be true: " + r.getBody());
    }

    // ─── Helpers (mirror AuditFlowE2ETest) ──────────────────────────────────

    private <T> ResponseEntity<T> post(String path, String bearer, Object payload, Class<T> respType) {
        HttpEntity<Object> entity = new HttpEntity<>(payload, jsonHeaders(bearer));
        return http.exchange(url(path), HttpMethod.POST, entity, respType);
    }

    private JsonNode postOk(String path, String bearer, Object payload) {
        ResponseEntity<JsonNode> r = post(path, bearer, payload, JsonNode.class);
        assertEquals(HttpStatus.OK, r.getStatusCode(), () -> path + " failed: " + r.getBody());
        assertTrue(r.getBody().path("status").asBoolean(false),
                () -> path + " status=false: " + r.getBody());
        return r.getBody();
    }

    private ResponseEntity<JsonNode> uploadPhoto(String bearer, long answerId, String name,
                                                 String contentType, byte[] bytes) {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        ByteArrayResource fileResource = new ByteArrayResource(bytes) {
            @Override public String getFilename() { return name; }
        };
        // Wrap in HttpEntity to control the per-part Content-Type header.
        HttpHeaders partHeaders = new HttpHeaders();
        partHeaders.setContentType(MediaType.parseMediaType(contentType));
        HttpEntity<ByteArrayResource> filePart = new HttpEntity<>(fileResource, partHeaders);
        form.add("file", filePart);
        form.add("userChecksheetAnswerId", answerId);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        if (bearer != null) headers.setBearerAuth(bearer);

        return http.exchange(url("/api/userChecksheet/createUserChksAnsFile"),
                HttpMethod.POST,
                new HttpEntity<>(form, headers),
                JsonNode.class);
    }

    private HttpHeaders jsonHeaders(String bearer) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (bearer != null) h.setBearerAuth(bearer);
        return h;
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
