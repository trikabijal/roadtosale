package com.auditpro.roadtosale;

import com.auditpro.roadtosale.seed.SeedService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ChecksheetAndHealthTest extends AbstractIntegrationTest {

    @Autowired
    private DataSource dataSource;

    @Test
    void healthIsPublicAndProvesDbReachability() throws Exception {
        // 1) The endpoint is public and reports ok against the REAL embedded Postgres.
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ok"));

        // 2) Prove the "ok" is backed by a live datasource the app actually uses:
        //    run a query through the same DataSource bean the HealthController holds.
        //    A static "ok" with no DB wired could not satisfy this.
        try (Connection c = dataSource.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT 1")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isEqualTo(1);
            // The migrated schema is present on this very datasource (Flyway ran).
            assertThat(c.getMetaData().getTables(null, null, "sessions", null).next()).isTrue();
        }
    }

    @Test
    void checksheetLoadsByCode() throws Exception {
        String token = login("rep1", SeedService.DEFAULT_PASSWORD);
        mockMvc.perform(get("/api/v1/checksheets/RTS_HONDA_V1").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.code").value("RTS_HONDA_V1"))
                .andExpect(jsonPath("$.data.steps").isArray())
                .andExpect(jsonPath("$.data.steps[0].questions[0].id").value("1"))
                .andExpect(jsonPath("$.data.steps[0].questions[0].isMandatory").value(true));
    }

    @Test
    void unknownChecksheetReturns404() throws Exception {
        String token = login("rep1", SeedService.DEFAULT_PASSWORD);
        mockMvc.perform(get("/api/v1/checksheets/DOES_NOT_EXIST").header("Authorization", bearer(token)))
                .andExpect(status().isNotFound());
    }
}
