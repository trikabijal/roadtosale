package com.auditpro.roadtosale;

import com.auditpro.roadtosale.seed.SeedService;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ChecksheetAndHealthTest extends AbstractIntegrationTest {

    @Test
    void healthIsPublicAndReportsDbUp() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ok"));
    }

    @Test
    void checksheetLoadsByCode() throws Exception {
        String token = login("rep1", SeedService.DEFAULT_PASSWORD);
        mockMvc.perform(get("/checksheet/RTS_HONDA_V1").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.code").value("RTS_HONDA_V1"))
                .andExpect(jsonPath("$.data.steps").isArray())
                .andExpect(jsonPath("$.data.steps[0].questions[0].id").value("1"))
                .andExpect(jsonPath("$.data.steps[0].questions[0].isMandatory").value(true));
    }

    @Test
    void unknownChecksheetReturns404() throws Exception {
        String token = login("rep1", SeedService.DEFAULT_PASSWORD);
        mockMvc.perform(get("/checksheet/DOES_NOT_EXIST").header("Authorization", bearer(token)))
                .andExpect(status().isNotFound());
    }
}
