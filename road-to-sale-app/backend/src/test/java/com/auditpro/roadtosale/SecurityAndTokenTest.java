package com.auditpro.roadtosale;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.auditpro.roadtosale.seed.SeedService;
import com.fasterxml.jackson.databind.JsonNode;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Security-focused tests: secrets never logged (T1), token claims (TW4),
 * expired tokens (TW1), and tenant cannot be spoofed via the request body (T3).
 *
 * <p>The {@code test} profile has no {@code roadtosale.auth.jwt-secret} override,
 * so it inherits the root default — the same key the app signs with, which lets
 * these tests forge tokens that the running app will accept/verify.
 */
class SecurityAndTokenTest extends AbstractIntegrationTest {

    /** Matches the inherited dev/test default secret from application.yml. */
    private static final String TEST_SECRET = "change-me-in-env-dev-only-secret-please";

    private SecretKey key() {
        return Keys.hmacShaKeyFor(TEST_SECRET.getBytes(StandardCharsets.UTF_8));
    }

    // ── T1: no secret leaks to logs ─────────────────────────────────────────

    @Test
    void noTokensOrPasswordsAppearInLogs() throws Exception {
        // Attach a list appender to the root logger to capture all log output.
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        try {
            String loginBody = objectMapper.writeValueAsString(Map.of(
                    "username", "rep1", "password", SeedService.DEFAULT_PASSWORD, "deviceType", "ios"));
            MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON).content(loginBody))
                    .andExpect(status().isOk()).andReturn();
            JsonNode data = dataOf(login);
            String access = data.path("accessToken").asText();
            String refresh = data.path("refreshToken").asText();

            // An authed request as well, so request-logging runs with a principal present.
            mockMvc.perform(get("/api/v1/sessions").header("Authorization", bearer(access)))
                    .andExpect(status().isOk());

            String allLogs = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .reduce("", (a, b) -> a + "\n" + b);

            assertThat(allLogs).doesNotContain(access);
            assertThat(allLogs).doesNotContain(refresh);
            assertThat(allLogs).doesNotContain(SeedService.DEFAULT_PASSWORD);
        } finally {
            root.detachAppender(appender);
        }
    }

    // ── TW4: access token carries userId + dealershipId claims ──────────────

    @Test
    void accessTokenCarriesUserIdAndDealershipIdClaims() throws Exception {
        String loginBody = objectMapper.writeValueAsString(Map.of(
                "username", "rep1", "password", SeedService.DEFAULT_PASSWORD, "deviceType", "ios"));
        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON).content(loginBody))
                .andExpect(status().isOk()).andReturn();
        JsonNode data = dataOf(login);
        String access = data.path("accessToken").asText();
        String expectedDealership = data.path("user").path("dealershipId").asText();
        String expectedUserId = data.path("user").path("id").asText();

        var claims = Jwts.parser().verifyWith(key()).build()
                .parseSignedClaims(access).getPayload();
        assertThat(claims.get("userId", String.class)).isEqualTo(expectedUserId);
        assertThat(claims.get("dealershipId", String.class)).isEqualTo(expectedDealership);
        assertThat(claims.get("type", String.class)).isEqualTo("access");
    }

    // ── TW1: expired tokens are rejected ────────────────────────────────────

    private String forgeToken(String type, Instant issuedAt, Instant expiry) {
        return Jwts.builder()
                .subject(UUID.randomUUID().toString())
                .claim("userId", UUID.randomUUID().toString())
                .claim("dealershipId", UUID.randomUUID().toString())
                .claim("type", type)
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(expiry))
                .signWith(key())
                .compact();
    }

    @Test
    void expiredAccessTokenIsRejectedOnProtectedRoute() throws Exception {
        Instant past = Instant.now().minusSeconds(7200);
        String expiredAccess = forgeToken("access", past, past.plusSeconds(3600)); // expired 1h ago
        mockMvc.perform(get("/api/v1/sessions").header("Authorization", bearer(expiredAccess)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void expiredRefreshTokenIsRejectedOnRefresh() throws Exception {
        Instant past = Instant.now().minusSeconds(7200);
        String expiredRefresh = forgeToken("refresh", past, past.plusSeconds(3600));
        String body = objectMapper.writeValueAsString(Map.of("refreshToken", expiredRefresh));
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
    }

    // ── T3: tenant cannot be spoofed via the request body ───────────────────

    @Test
    void forgedDealershipIdInBodyIsIgnored() throws Exception {
        String token = login("rep1", SeedService.DEFAULT_PASSWORD);
        String rep1Dealership = decodeDealership(token);

        // POST a session with an extra forged dealershipId field.
        String forged = UUID.randomUUID().toString();
        String body = objectMapper.writeValueAsString(Map.of(
                "type", "LIVE",
                "checksheetCode", "RTS_HONDA_V1",
                "dealershipId", forged));
        MvcResult res = mockMvc.perform(post("/api/v1/sessions")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn();
        String sessionId = dataOf(res).path("id").asText();

        // rep2 (a different dealership) must NOT see this session if it were created
        // under the forged tenant; and rep1 (the token's real tenant) must.
        String rep2 = login("rep2", SeedService.DEFAULT_PASSWORD);
        mockMvc.perform(get("/api/v1/sessions/" + sessionId).header("Authorization", bearer(rep2)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/sessions/" + sessionId).header("Authorization", bearer(token)))
                .andExpect(status().isOk());

        // And the forged value never became the session's tenant: rep1's tenant differs from forged.
        assertThat(rep1Dealership).isNotEqualTo(forged);
    }

    private String decodeDealership(String accessToken) {
        var claims = Jwts.parser().verifyWith(key()).build()
                .parseSignedClaims(accessToken).getPayload();
        return claims.get("dealershipId", String.class);
    }
}
