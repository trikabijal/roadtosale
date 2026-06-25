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

    /** Minimal JPEG: SOI marker + a few bytes so the magic-byte sniff (FF D8 FF) passes. */
    private static byte[] jpegBytes() {
        return new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00, 0x10, 'J', 'F', 'I', 'F'};
    }

    /** Minimal PNG signature (89 50 4E 47 0D 0A 1A 0A) + filler. */
    private static byte[] pngBytes() {
        return new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00};
    }

    private String createSession(String token) throws Exception {
        String body = objectMapper.writeValueAsString(
                Map.of("type", "LIVE", "checksheetCode", "RTS_HONDA_V1"));
        MvcResult res = mockMvc.perform(post("/api/v1/sessions")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
        return dataOf(res).path("id").asText();
    }

    @Test
    void uploadSetsBffRelativeFileUrlAndContentEndpointServesBytes() throws Exception {
        String token = login("rep1", SeedService.DEFAULT_PASSWORD);
        String id = createSession(token);

        MockMultipartFile file = new MockMultipartFile(
                "file", "front.jpg", "image/jpeg", jpegBytes());

        MvcResult uploaded = mockMvc.perform(multipart("/api/v1/sessions/" + id + "/photos")
                        .file(file)
                        .param("slot", "front_left")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.slot").value("front_left"))
                .andExpect(jsonPath("$.data.fileUrl").isNotEmpty())
                .andReturn();

        JsonNode photo = dataOf(uploaded);
        String photoId = photo.path("id").asText();
        String fileUrl = photo.path("fileUrl").asText();

        // fileUrl is the BFF-relative logical path (no /files, no /api/v1).
        assertThat(fileUrl).isEqualTo("/sessions/" + id + "/photos/" + photoId + "/content");

        // The content endpoint is authed + tenant-scoped and serves the bytes with the
        // sniffed content-type (image/jpeg). It is NOT public.
        mockMvc.perform(get("/api/v1/sessions/" + id + "/photos/" + photoId + "/content"))
                .andExpect(status().isUnauthorized());

        MvcResult content = mockMvc.perform(get("/api/v1/sessions/" + id + "/photos/" + photoId + "/content")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(content.getResponse().getContentType()).startsWith(MediaType.IMAGE_JPEG_VALUE);
        assertThat(content.getResponse().getContentAsByteArray()).isEqualTo(jpegBytes());

        mockMvc.perform(get("/api/v1/sessions/" + id + "/photos").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].slot").value("front_left"));
    }

    @Test
    void pngUploadIsAccepted() throws Exception {
        String token = login("rep1", SeedService.DEFAULT_PASSWORD);
        String id = createSession(token);
        MockMultipartFile file = new MockMultipartFile("file", "x.png", "image/png", pngBytes());
        mockMvc.perform(multipart("/api/v1/sessions/" + id + "/photos")
                        .file(file).param("slot", "interior")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk());
    }

    @Test
    void contentEndpointForOtherTenantReturns404() throws Exception {
        // rep1 uploads a photo.
        String rep1 = login("rep1", SeedService.DEFAULT_PASSWORD);
        String rep1Session = createSession(rep1);
        MockMultipartFile file = new MockMultipartFile("file", "f.jpg", "image/jpeg", jpegBytes());
        MvcResult uploaded = mockMvc.perform(multipart("/api/v1/sessions/" + rep1Session + "/photos")
                        .file(file).param("slot", "front_left")
                        .header("Authorization", bearer(rep1)))
                .andExpect(status().isOk()).andReturn();
        String photoId = dataOf(uploaded).path("id").asText();

        // rep2 (different dealership) must not be able to read those bytes -> 404.
        String rep2 = login("rep2", SeedService.DEFAULT_PASSWORD);
        mockMvc.perform(get("/api/v1/sessions/" + rep1Session + "/photos/" + photoId + "/content")
                        .header("Authorization", bearer(rep2)))
                .andExpect(status().isNotFound());
    }

    @Test
    void uploadToOtherTenantSessionReturns404() throws Exception {
        String rep2 = login("rep2", SeedService.DEFAULT_PASSWORD);
        String rep2Session = createSession(rep2);

        String rep1 = login("rep1", SeedService.DEFAULT_PASSWORD);
        MockMultipartFile file = new MockMultipartFile("file", "x.jpg", "image/jpeg", jpegBytes());
        mockMvc.perform(multipart("/api/v1/sessions/" + rep2Session + "/photos")
                        .file(file)
                        .param("slot", "interior")
                        .header("Authorization", bearer(rep1)))
                .andExpect(status().isNotFound());
    }

    @Test
    void nonImageWithSpoofedImageHeaderIsRejected400() throws Exception {
        String token = login("rep1", SeedService.DEFAULT_PASSWORD);
        String id = createSession(token);
        // Text bytes but a lying image/png content-type header. Magic-byte sniff must reject.
        MockMultipartFile file = new MockMultipartFile(
                "file", "evil.png", "image/png", "this is not an image".getBytes());
        mockMvc.perform(multipart("/api/v1/sessions/" + id + "/photos")
                        .file(file).param("slot", "vin")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void oversizeUploadReturns413() throws Exception {
        // The servlet container raises MaxUploadSizeExceededException when the upload
        // exceeds spring.servlet.multipart.max-file-size (10MB, B2). MockMvc does not
        // run Tomcat's multipart size parser, so we assert the GlobalExceptionHandler
        // maps that exception to 413 in the ApiResponse envelope — which is the code
        // change under test. A standalone MockMvc wires only the advice + a stub that
        // throws exactly what the container would.
        org.springframework.test.web.servlet.MockMvc standalone =
                org.springframework.test.web.servlet.setup.MockMvcBuilders
                        .standaloneSetup(new ThrowingUploadController())
                        .setControllerAdvice(new com.auditpro.roadtosale.web.GlobalExceptionHandler())
                        .build();
        standalone.perform(post("/throw-too-large"))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.status").value(413));
    }

    /** Stub controller that throws what Tomcat throws when an upload is too large. */
    @org.springframework.web.bind.annotation.RestController
    static class ThrowingUploadController {
        @org.springframework.web.bind.annotation.PostMapping("/throw-too-large")
        public void tooLarge() {
            throw new org.springframework.web.multipart.MaxUploadSizeExceededException(10L * 1024 * 1024);
        }
    }
}
