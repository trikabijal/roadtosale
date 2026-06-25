package com.auditpro.roadtosale;

import com.auditpro.roadtosale.seed.SeedService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Cross-tenant access must be impossible and indistinguishable from "not found":
 * rep1 (Honda of Fremont) must get 404 for rep2's (Honda of Oakland) session.
 */
class TenantIsolationTest extends AbstractIntegrationTest {

    private String createSessionAs(String token) throws Exception {
        String body = objectMapper.writeValueAsString(
                Map.of("type", "LIVE", "checksheetCode", "RTS_HONDA_V1"));
        MvcResult res = mockMvc.perform(post("/api/v1/sessions")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
        return dataOf(res).path("id").asText();
    }

    @Test
    void rep1CannotReadRep2Session() throws Exception {
        String rep2 = login("rep2", SeedService.DEFAULT_PASSWORD);
        String rep2Session = createSessionAs(rep2);

        String rep1 = login("rep1", SeedService.DEFAULT_PASSWORD);
        mockMvc.perform(get("/api/v1/sessions/" + rep2Session).header("Authorization", bearer(rep1)))
                .andExpect(status().isNotFound());
    }

    @Test
    void rep1CannotSubmitRep2Session() throws Exception {
        String rep2 = login("rep2", SeedService.DEFAULT_PASSWORD);
        String rep2Session = createSessionAs(rep2);

        String rep1 = login("rep1", SeedService.DEFAULT_PASSWORD);
        mockMvc.perform(post("/api/v1/sessions/" + rep2Session + "/submit")
                        .header("Authorization", bearer(rep1))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void rep1CannotPostEventsToRep2Session() throws Exception {
        String rep2 = login("rep2", SeedService.DEFAULT_PASSWORD);
        String rep2Session = createSessionAs(rep2);

        String rep1 = login("rep1", SeedService.DEFAULT_PASSWORD);
        String events = objectMapper.writeValueAsString(Map.of("events", List.of(Map.of(
                "cueId", "x", "questionId", "1", "stepNo", 1,
                "detectedAt", "2026-06-25T10:00:00Z", "confidence", 0.9,
                "transcriptSpan", "hi", "source", "feature"))));
        mockMvc.perform(post("/api/v1/sessions/" + rep2Session + "/events")
                        .header("Authorization", bearer(rep1))
                        .contentType(MediaType.APPLICATION_JSON).content(events))
                .andExpect(status().isNotFound());
    }

    @Test
    void listIsScopedToCallerOnly() throws Exception {
        String rep2 = login("rep2", SeedService.DEFAULT_PASSWORD);
        createSessionAs(rep2);

        String rep1 = login("rep1", SeedService.DEFAULT_PASSWORD);
        // rep1 created nothing; rep2's session must not appear.
        mockMvc.perform(get("/api/v1/sessions").header("Authorization", bearer(rep1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(0));
    }
}
