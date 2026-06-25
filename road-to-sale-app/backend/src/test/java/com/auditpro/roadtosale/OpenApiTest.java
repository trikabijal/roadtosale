package com.auditpro.roadtosale;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** TW5: the OpenAPI document is present and lists the documented /api/v1 paths. */
class OpenApiTest extends AbstractIntegrationTest {

    @Test
    void apiDocsArePresentAndIncludeDocumentedPaths() throws Exception {
        MvcResult res = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode doc = objectMapper.readTree(res.getResponse().getContentAsString());
        JsonNode paths = doc.path("paths");

        assertThat(paths.has("/api/v1/auth/login")).isTrue();
        assertThat(paths.has("/api/v1/sessions")).isTrue();
        assertThat(paths.has("/api/v1/checksheets/{code}")).isTrue();
        assertThat(paths.has("/api/v1/sessions/{id}/photos")).isTrue();
        assertThat(paths.has("/api/v1/sessions/{id}/photos/{photoId}/content")).isTrue();
    }
}
