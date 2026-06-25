package com.auditpro.roadtosale;

import com.auditpro.roadtosale.seed.SeedService;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PhotoTest extends AbstractIntegrationTest {

    private String createSession(String token) throws Exception {
        String body = objectMapper.writeValueAsString(
                Map.of("type", "LIVE", "checksheetCode", "RTS_HONDA_V1"));
        MvcResult res = mockMvc.perform(post("/sessions")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
        return dataOf(res).path("id").asText();
    }

    @Test
    void uploadAndListPhotoWithRetrievableUrl() throws Exception {
        String token = login("rep1", SeedService.DEFAULT_PASSWORD);
        String id = createSession(token);

        MockMultipartFile file = new MockMultipartFile(
                "file", "front.jpg", "image/jpeg", "fake-image-bytes".getBytes());

        MvcResult uploaded = mockMvc.perform(multipart("/sessions/" + id + "/photos")
                        .file(file)
                        .param("slot", "front_left")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.slot").value("front_left"))
                .andExpect(jsonPath("$.data.fileUrl").isNotEmpty())
                .andReturn();

        JsonNode photo = dataOf(uploaded);
        String fileUrl = photo.path("fileUrl").asText();
        assertThat(fileUrl).startsWith("/files/");

        // The fileUrl must actually serve the bytes back (public route).
        mockMvc.perform(get(fileUrl))
                .andExpect(status().isOk());

        mockMvc.perform(get("/sessions/" + id + "/photos").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].slot").value("front_left"));
    }

    @Test
    void uploadToOtherTenantSessionReturns404() throws Exception {
        String rep2 = login("rep2", SeedService.DEFAULT_PASSWORD);
        String rep2Session = createSession(rep2);

        String rep1 = login("rep1", SeedService.DEFAULT_PASSWORD);
        MockMultipartFile file = new MockMultipartFile(
                "file", "x.jpg", "image/jpeg", "bytes".getBytes());
        mockMvc.perform(multipart("/sessions/" + rep2Session + "/photos")
                        .file(file)
                        .param("slot", "interior")
                        .header("Authorization", bearer(rep1)))
                .andExpect(status().isNotFound());
    }
}
