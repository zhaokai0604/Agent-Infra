package com.award.log.security.signal;

import com.award.log.util.TestTimeSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecuritySignalServiceTest {

    @Test
    void ingestAndSummarizeThreatSignals() {
        TestTimeSource timeSource = new TestTimeSource(10_000L);
        SecuritySignalService service = new SecuritySignalService(
                new SecuritySignalNormalizer(new ObjectMapper()),
                timeSource);
        ReflectionTestUtils.setField(service, "recentMax", 32);
        ReflectionTestUtils.setField(service, "activeWindowMs", 3_600_000L);

        service.ingest("suricata", """
                {"timestamp":"2026-06-17T10:00:00+08:00","src_ip":"1.1.1.1","dest_ip":"2.2.2.2",
                 "alert":{"signature":"critical match","severity":1,"action":"blocked"}}
                """);
        service.ingest("wazuh", Map.of(
                "agent", Map.of("name", "host-b"),
                "rule", Map.of("id", "200001", "level", 10, "description", "Suspicious persistence")));

        Map<String, Object> summary = service.summary();

        assertTrue(Boolean.TRUE.equals(summary.get("hasThreat")));
        assertEquals(2, ((Number) summary.get("highOrAboveCount")).intValue());
        assertEquals(1, ((Number) summary.get("blockedCount")).intValue());
        assertFalse(service.recentHighPriority(10, 60_000L).isEmpty());
        assertTrue(service.buildThreatSummaryText(summary).contains("高危"));
    }

    @Test
    void recentRespectsLimitAndWindow() {
        TestTimeSource timeSource = new TestTimeSource(100_000L);
        SecuritySignalService service = new SecuritySignalService(
                new SecuritySignalNormalizer(new ObjectMapper()),
                timeSource);
        ReflectionTestUtils.setField(service, "recentMax", 32);
        ReflectionTestUtils.setField(service, "activeWindowMs", 60_000L);

        service.ingest("ids", Map.of("title", "first", "severity", "low"));
        timeSource.setNow(200_000L);
        service.ingest("ids", Map.of("title", "second", "severity", "high"));

        List<SecuritySignal> recent = service.recent(1);
        Map<String, Object> summary = service.summary(30_000L);

        assertEquals(1, recent.size());
        assertEquals("second", recent.get(0).title());
        assertEquals(1, ((Number) summary.get("totalCount")).intValue());
    }
}
