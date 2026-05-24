package com.checkSheet.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import jakarta.persistence.EntityManager;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for AuditServiceImpl.scopeCte — verifies that the date filter SQL
 * is injected into the CTE when startDate/endDate params are supplied.
 */
@ExtendWith(MockitoExtension.class)
class AuditBiDateFilterTest {

    @Mock
    private EntityManager em;

    @InjectMocks
    private AuditServiceImpl auditService;

    private String invokeScopeCte(String level, String dateFilter) {
        return (String) ReflectionTestUtils.invokeMethod(auditService, "scopeCte", level, dateFilter);
    }

    @Test
    void scopeCte_noDateFilter_doesNotContainDateClause() {
        String cte = invokeScopeCte("national", "");
        assertFalse(cte.contains("BETWEEN :startDate AND :endDate"),
                "Empty dateFilter should not produce a date BETWEEN clause");
    }

    @Test
    void scopeCte_withDateFilter_containsDateClause() {
        String dateFilter = "AND DATE(ins.submitted_at) BETWEEN :startDate AND :endDate";
        String cte = invokeScopeCte("national", dateFilter);
        assertTrue(cte.contains("BETWEEN :startDate AND :endDate"),
                "Non-empty dateFilter should produce the date BETWEEN clause");
        assertTrue(cte.contains(":startDate") && cte.contains(":endDate"),
                "CTE should contain the named date parameters");
    }

    @Test
    void scopeCte_regional_includesRegionFilter() {
        String cte = invokeScopeCte("region", "");
        assertTrue(cte.contains("region_id = :regionId"),
                "Region-scoped CTE must include the region_id scope filter");
    }

    @Test
    void scopeCte_dealer_includesDealerFilter() {
        String cte = invokeScopeCte("dealer", "");
        assertTrue(cte.contains("auditee_id = :auditeeId"),
                "Dealer-scoped CTE must include the auditee_id scope filter");
    }

    @Test
    void scopeCte_location_includesLocationFilter() {
        String cte = invokeScopeCte("location", "");
        assertTrue(cte.contains("aloc.id = :locationId"),
                "Location-scoped CTE must include the location id scope filter");
    }

    @Test
    void scopeCte_noArgOverload_delegatesToTwoArgVersion() {
        // scopeCte(level) should produce the same SQL as scopeCte(level, "")
        String fromOneArg = (String) ReflectionTestUtils.invokeMethod(auditService, "scopeCte", "national");
        String fromTwoArg = invokeScopeCte("national", "");
        assertEquals(fromOneArg, fromTwoArg,
                "scopeCte(level) must delegate to scopeCte(level, \"\")");
    }
}
