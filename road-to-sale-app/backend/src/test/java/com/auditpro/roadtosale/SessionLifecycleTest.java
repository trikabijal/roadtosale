package com.auditpro.roadtosale;

import com.auditpro.roadtosale.seed.SeedService;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SessionLifecycleTest extends AbstractIntegrationTest {

    private String createSession(String token) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "type", "LIVE",
                "checksheetCode", "RTS_HONDA_V1",
                "context", Map.of("customerName", "Jane Doe", "vehicleOfInterest", "CR-V")));
        MvcResult res = mockMvc.perform(post("/sessions")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.type").value("LIVE"))
                .andExpect(jsonPath("$.data.context.customerName").value("Jane Doe"))
                .andExpect(jsonPath("$.data.progress.total").value(16))
                .andExpect(jsonPath("$.data.progress.answered").value(0))
                .andReturn();
        return dataOf(res).path("id").asText();
    }

    @Test
    void createGetListSession() throws Exception {
        String token = login("rep1", SeedService.DEFAULT_PASSWORD);
        String id = createSession(token);

        mockMvc.perform(get("/sessions/" + id).header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(id))
                .andExpect(jsonPath("$.data.outcomes").isArray());

        mockMvc.perform(get("/sessions").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(id));
    }

    @Test
    void createSessionWithUnknownChecksheetReturns404() throws Exception {
        String token = login("rep1", SeedService.DEFAULT_PASSWORD);
        String body = objectMapper.writeValueAsString(Map.of("type", "LIVE", "checksheetCode", "NOPE"));
        mockMvc.perform(post("/sessions")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound());
    }

    @Test
    void submitCompletesAndSecondSubmitConflicts() throws Exception {
        String token = login("rep1", SeedService.DEFAULT_PASSWORD);
        String id = createSession(token);

        String submitBody = objectMapper.writeValueAsString(Map.of("transcript", "hello world"));
        mockMvc.perform(post("/sessions/" + id + "/submit")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content(submitBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.transcript").value("hello world"))
                .andExpect(jsonPath("$.data.endedAt").isNotEmpty());

        mockMvc.perform(post("/sessions/" + id + "/submit")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content(submitBody))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    void getNonexistentSessionReturns404() throws Exception {
        String token = login("rep1", SeedService.DEFAULT_PASSWORD);
        mockMvc.perform(get("/sessions/" + java.util.UUID.randomUUID())
                        .header("Authorization", bearer(token)))
                .andExpect(status().isNotFound());
    }

    // ── Events idempotency + outcome derivation ──────────────────────────────

    private String eventsBody(String cueId, double confidence) throws Exception {
        return objectMapper.writeValueAsString(Map.of("events", List.of(Map.of(
                "cueId", cueId,
                "questionId", "1",
                "stepNo", 1,
                "detectedAt", "2026-06-25T10:00:00Z",
                "confidence", confidence,
                "transcriptSpan", "hi there",
                "source", "feature"))));
    }

    @Test
    void eventsAreIdempotentByCueId() throws Exception {
        String token = login("rep1", SeedService.DEFAULT_PASSWORD);
        String id = createSession(token);

        // First post: 1 accepted.
        mockMvc.perform(post("/sessions/" + id + "/events")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content(eventsBody("cue-1", 0.9)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accepted").value(1));

        // Re-post same cueId: 0 accepted (idempotent), no duplicate row.
        mockMvc.perform(post("/sessions/" + id + "/events")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content(eventsBody("cue-1", 0.9)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accepted").value(0));
    }

    @Test
    void outcomeUsesHighestConfidenceAndThreshold() throws Exception {
        String token = login("rep1", SeedService.DEFAULT_PASSWORD);
        String id = createSession(token);

        // Two events for question "1": low (0.4) then high (0.8). Highest wins, >= 0.6 -> satisfied.
        mockMvc.perform(post("/sessions/" + id + "/events")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content(eventsBody("cue-low", 0.4)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/sessions/" + id + "/events")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content(eventsBody("cue-high", 0.8)))
                .andExpect(status().isOk());

        MvcResult res = mockMvc.perform(get("/sessions/" + id).header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode data = dataOf(res);
        JsonNode outcome = data.path("outcomes").get(0);
        assertThat(outcome.path("questionId").asText()).isEqualTo("1");
        assertThat(outcome.path("confidence").asDouble()).isEqualTo(0.8);
        assertThat(outcome.path("satisfied").asBoolean()).isTrue();
        assertThat(data.path("progress").path("answered").asInt()).isEqualTo(1);
    }

    @Test
    void outcomeBelowThresholdIsNotSatisfied() throws Exception {
        String token = login("rep1", SeedService.DEFAULT_PASSWORD);
        String id = createSession(token);

        mockMvc.perform(post("/sessions/" + id + "/events")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content(eventsBody("cue-x", 0.5)))
                .andExpect(status().isOk());

        MvcResult res = mockMvc.perform(get("/sessions/" + id).header("Authorization", bearer(token)))
                .andReturn();
        JsonNode data = dataOf(res);
        assertThat(data.path("outcomes").get(0).path("satisfied").asBoolean()).isFalse();
        assertThat(data.path("progress").path("answered").asInt()).isEqualTo(0);
    }

    @Test
    void eventsOnCompletedSessionReturn409() throws Exception {
        String token = login("rep1", SeedService.DEFAULT_PASSWORD);
        String id = createSession(token);
        mockMvc.perform(post("/sessions/" + id + "/submit")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/sessions/" + id + "/events")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content(eventsBody("cue-late", 0.9)))
                .andExpect(status().isConflict());
    }
}
