package com.auditpro.roadtosale;

import com.auditpro.roadtosale.seed.SeedService;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthTest extends AbstractIntegrationTest {

    @Test
    void loginReturnsTokensAndUser() throws Exception {
        String body = objectMapper.writeValueAsString(
                Map.of("username", "rep1", "password", SeedService.DEFAULT_PASSWORD, "deviceType", "ios"));
        MvcResult res = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.data.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.data.user.username").value("rep1"))
                .andExpect(jsonPath("$.data.user.roles[0]").value("SALESPERSON"))
                .andReturn();
        assertThat(dataOf(res).path("user").path("dealershipId").asText()).isNotBlank();
    }

    @Test
    void loginWithWrongPasswordReturns401() throws Exception {
        String body = objectMapper.writeValueAsString(
                Map.of("username", "rep1", "password", "wrong", "deviceType", "ios"));
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void loginMissingFieldReturns400() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("username", "rep1"));
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void refreshIssuesNewPair() throws Exception {
        String loginBody = objectMapper.writeValueAsString(
                Map.of("username", "rep1", "password", SeedService.DEFAULT_PASSWORD, "deviceType", "ios"));
        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON).content(loginBody)).andReturn();
        String refreshToken = dataOf(login).path("refreshToken").asText();

        String refreshBody = objectMapper.writeValueAsString(Map.of("refreshToken", refreshToken));
        MvcResult res = mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON).content(refreshBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.data.refreshToken").isNotEmpty())
                .andReturn();
        JsonNode data = dataOf(res);
        // The new access token must actually work on a protected route.
        mockMvc.perform(get("/api/v1/sessions").header("Authorization", bearer(data.path("accessToken").asText())))
                .andExpect(status().isOk());
    }

    @Test
    void refreshWithGarbageReturns401() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("refreshToken", "not-a-jwt"));
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void accessTokenCannotBeUsedAsRefreshToken() throws Exception {
        String accessToken = login("rep1", SeedService.DEFAULT_PASSWORD);
        String body = objectMapper.writeValueAsString(Map.of("refreshToken", accessToken));
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void protectedRouteWithoutTokenReturns401() throws Exception {
        mockMvc.perform(get("/api/v1/sessions"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void protectedRouteWithBadTokenReturns401() throws Exception {
        mockMvc.perform(get("/api/v1/sessions").header("Authorization", "Bearer abc.def.ghi"))
                .andExpect(status().isUnauthorized());
    }
}
