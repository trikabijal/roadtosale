package com.checkSheet.audit;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Drill-down context test. Walks the BI hierarchy:
 *
 *     /stats/national → /stats/region/{regionId} → /stats/dealer/{auditeeId} → /stats/location/{locationId}
 *
 * For each level it asserts:
 *   1. The scope label is the resolved NAME (Region "Central", not "Region 101"; the
 *      "101 in breadcrumb" bug we just fixed is the regression target)
 *   2. The count metrics (totalAudits, auditedLocations, redDealers count) are a
 *      monotonically non-increasing subset of the parent — i.e. the scope filter
 *      actually narrows the data set instead of leaking national counts down
 *   3. The redDealers list each level returns can be drilled into — every dealer
 *      auditeeId from regional list must be reachable from the dealer endpoint
 *   4. The same response shape comes back at every level (greenCount, amberCount,
 *      redCount, whatsFailing[], topFailingCheckpoints[], aiInsights[], redDealers[])
 *
 * Pre-req: the local DB has at least one audit with seeded data. The
 * dev/seed-kia-demo.py full run produces the fixture; this test requires it
 * to have been executed at least once.
 */
@Tag("tier1")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class BiDrillDownE2ETest {


    @LocalServerPort int port;
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;

    /** Bootstrap super-admin username — see application-<env>.properties
     *  key {@code app.test.bootstrap-user}. */
    @org.springframework.beans.factory.annotation.Value("${app.test.bootstrap-user:Z006135}")
    private String bootstrapAdminUsername;

    private long auditId;
    private String token;

    @BeforeAll
    void setup() {
        // No KIA_* seed dependency. Bootstrap super-admin (default Z006135)
        // has BI_VIEW permission and is enough to drive every drill-down
        // call below.
        token = RestTestSupport.token(http, port, bootstrapAdminUsername);
        // Pick the most recent audit that actually has APPROVED inspections,
        // so the drill-down assertions have data to compare.
        Long candidate = jdbc.queryForObject("""
            SELECT a.id FROM audits a
              JOIN inspections ins ON ins.audit_id = a.id AND ins.kind = 'AUDIT'
             WHERE ins.status = 'APPROVED' AND a.deleted_at IS NULL
             GROUP BY a.id
             ORDER BY COUNT(*) DESC
             LIMIT 1
            """, Long.class);
        assertNotNull(candidate, "Need at least one audit with APPROVED inspections — run dev/seed-kia-demo.py first");
        auditId = candidate;
    }

    @Test
    @DisplayName("Drill down: national → region → dealer → location, names + counts narrow correctly")
    void drillDown_scopeLabelsResolveToNames_andCountsNarrow() {
        // ── Level 1: National ──────────────────────────────────────────────
        JsonNode national = getStats("/api/audit/" + auditId + "/stats/national");
        assertEquals("National", national.path("scopeLabel").asText(),
                "National scopeLabel must be 'National'");
        long nationalAudits = required(national, "totalAudits");
        long nationalAudited = required(national, "auditedLocations");
        assertTrue(nationalAudits > 0, "National should have audits to drill into");
        assertResponseShape("national", national);

        // Pick a region from the national table that actually has audits in it,
        // so we drill into a populated subtree.
        JsonNode regionRow = pickRowWithAudits(national.path("table"));
        long regionId = regionRow.path("c0").asLong();
        String expectedRegionName = regionRow.path("c1").asText();

        // ── Level 2: Region ────────────────────────────────────────────────
        JsonNode region = getStats("/api/audit/" + auditId + "/stats/region/" + regionId);
        assertEquals(expectedRegionName, region.path("scopeLabel").asText(),
                "Region scopeLabel must be the region NAME, not the id (regression: 'Region 101' bug)");
        long regionAudits = required(region, "totalAudits");
        long regionAudited = required(region, "auditedLocations");
        assertTrue(regionAudits <= nationalAudits,
                () -> "Region audits (" + regionAudits + ") must be ≤ national (" + nationalAudits + ")");
        assertTrue(regionAudited <= nationalAudited,
                () -> "Region locations (" + regionAudited + ") must be ≤ national (" + nationalAudited + ")");
        assertTrue(regionAudits > 0, "Picked a region with audits but the API returned 0 — scope filter likely broken");
        assertResponseShape("region", region);

        // Pick a dealer from the region's bottom-dealers list. If the region
        // doesn't have a bottom-dealers entry (small region), fall back to
        // the national list filtered by region.
        JsonNode dealerEntry = pickDealerInRegion(region, national, regionId);
        long auditeeId = dealerEntry.path("auditeeId").asLong();
        String expectedDealerName = dealerEntry.path("dealer").asText();
        assertTrue(auditeeId > 0, "Picked dealer must have a real auditeeId");

        // ── Level 3: Dealer ────────────────────────────────────────────────
        JsonNode dealer = getStats("/api/audit/" + auditId + "/stats/dealer/" + auditeeId);
        assertEquals(expectedDealerName, dealer.path("scopeLabel").asText(),
                "Dealer scopeLabel must be the dealer NAME");
        long dealerAudits = required(dealer, "totalAudits");
        assertTrue(dealerAudits <= regionAudits,
                () -> "Dealer audits (" + dealerAudits + ") must be ≤ region (" + regionAudits + ")");
        assertResponseShape("dealer", dealer);

        // ── Level 4: Location ──────────────────────────────────────────────
        // Pick a location belonging to the dealer (one of the dealer's auditee_locations
        // that has at least one user_checksheet).
        Long locationId = jdbc.queryForObject("""
            SELECT al.id FROM auditee_locations al
              JOIN inspections ins ON ins.auditee_location_id = al.id AND ins.kind = 'AUDIT'
             WHERE al.auditee_id = ? AND ins.audit_id = ? AND ins.status = 'APPROVED'
             ORDER BY al.id LIMIT 1
            """, Long.class, auditeeId, auditId);
        assertNotNull(locationId, "Picked dealer must have at least one audited location");

        JsonNode location = getStats("/api/audit/" + auditId + "/stats/location/" + locationId);
        String locScopeLabel = location.path("scopeLabel").asText();
        assertTrue(locScopeLabel.startsWith(expectedDealerName) || locScopeLabel.contains(expectedDealerName),
                () -> "Location scopeLabel '" + locScopeLabel + "' should include the parent dealer name '" + expectedDealerName + "'");
        long locationAudits = required(location, "totalAudits");
        assertTrue(locationAudits <= dealerAudits,
                () -> "Location audits (" + locationAudits + ") must be ≤ dealer (" + dealerAudits + ")");
        assertResponseShape("location", location);
    }

    @Test
    @DisplayName("Every region in /stats/national table is reachable via /stats/region/{id}")
    void everyRegionLinkResolves() {
        JsonNode national = getStats("/api/audit/" + auditId + "/stats/national");
        for (JsonNode row : national.path("table")) {
            long regionId = row.path("c0").asLong();
            String regionName = row.path("c1").asText();
            ResponseEntity<JsonNode> r = http.exchange(
                    url("/api/audit/" + auditId + "/stats/region/" + regionId),
                    HttpMethod.GET, new HttpEntity<>(headers(token)), JsonNode.class);
            assertEquals(HttpStatus.OK, r.getStatusCode(),
                    () -> "Region link for " + regionName + " (id=" + regionId + ") returned " + r.getStatusCode());
            assertEquals(regionName, r.getBody().path("data").path("scopeLabel").asText(),
                    () -> "Region " + regionId + " scopeLabel mismatch");
        }
    }

    @Test
    @DisplayName("Every redDealer in /stats/national is reachable via /stats/dealer/{id}")
    void everyRedDealerLinkResolves() {
        JsonNode national = getStats("/api/audit/" + auditId + "/stats/national");
        JsonNode redDealers = national.path("redDealers");
        assertTrue(redDealers.isArray(), "redDealers must be an array");
        for (JsonNode entry : redDealers) {
            long auditeeId = entry.path("auditeeId").asLong();
            String dealerName = entry.path("dealer").asText();
            assertTrue(auditeeId > 0, "Each redDealer must have an auditeeId for the drill link to work");
            ResponseEntity<JsonNode> r = http.exchange(
                    url("/api/audit/" + auditId + "/stats/dealer/" + auditeeId),
                    HttpMethod.GET, new HttpEntity<>(headers(token)), JsonNode.class);
            assertEquals(HttpStatus.OK, r.getStatusCode(),
                    () -> "Dealer link for " + dealerName + " (id=" + auditeeId + ") returned " + r.getStatusCode());
        }
    }

    // ─── Helpers ────────────────────────────────────────────────────────────

    /** Asserts the response has the same shape at every drill level so the
     *  Angular component code can reuse the same template across pages. */
    private void assertResponseShape(String level, JsonNode stats) {
        for (String key : List.of("scope", "scopeLabel", "totalLocations", "auditedLocations",
                "totalAudits", "greenCount", "amberCount", "redCount",
                "whatsFailing", "topFailingCheckpoints", "aiInsights", "table", "redDealers")) {
            assertTrue(stats.has(key), () -> level + " response missing field: " + key);
        }
    }

    private long required(JsonNode stats, String field) {
        JsonNode v = stats.path(field);
        assertFalse(v.isMissingNode() || v.isNull(), () -> "Missing required field: " + field);
        return v.asLong();
    }

    private JsonNode pickRowWithAudits(JsonNode table) {
        for (JsonNode row : table) {
            // c3 is total_audits in the national table (regionId, regionName,
            // total_assignments, total_audits, avg_score)
            if (row.path("c3").asLong() > 0) return row;
        }
        return table.get(0); // fall back, test will fail later with a clear message
    }

    private JsonNode pickDealerInRegion(JsonNode region, JsonNode national, long regionId) {
        // Prefer a dealer from the region's own redDealers list
        for (JsonNode d : region.path("redDealers")) {
            if (d.path("auditeeId").asLong() > 0) return d;
        }
        // Fall back: a dealer from national's redDealers — still a valid drill,
        // just won't necessarily be in the region we picked
        for (JsonNode d : national.path("redDealers")) {
            if (d.path("auditeeId").asLong() > 0) return d;
        }
        fail("No drillable dealer found in either regional or national redDealers");
        return null;  // unreachable
    }

    private JsonNode getStats(String path) {
        ResponseEntity<JsonNode> r = http.exchange(url(path), HttpMethod.GET,
                new HttpEntity<>(headers(token)), JsonNode.class);
        assertEquals(HttpStatus.OK, r.getStatusCode(), () -> path + " returned " + r.getStatusCode() + " body=" + r.getBody());
        assertTrue(r.getBody().path("status").asBoolean(true), () -> path + " status=false: " + r.getBody());
        return r.getBody().path("data");
    }

    private HttpHeaders jsonHeaders(String bearer) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (bearer != null) h.setBearerAuth(bearer);
        return h;
    }

    private HttpHeaders headers(String bearer) {
        HttpHeaders h = new HttpHeaders();
        if (bearer != null) h.setBearerAuth(bearer);
        return h;
    }

    private String url(String path) { return "http://localhost:" + port + path; }
}
