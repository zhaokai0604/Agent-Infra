package com.award.log.service;

import com.award.log.agent.DrainTemplateNoveltyTracker;
import com.award.log.config.AgentOpsProperties;
import com.award.log.security.OpsPathPolicy;
import com.award.log.security.signal.SecuritySignalService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OpsPatrolServiceTest {

    @Test
    void runPatrolCycleAddsSecuritySignalFindingAndCorrelationSummary() throws Exception {
        StatisticsService statisticsService = mock(StatisticsService.class);
        AiLogAlarmService aiLogAlarmService = mock(AiLogAlarmService.class);
        DrainTemplateNoveltyTracker noveltyTracker = mock(DrainTemplateNoveltyTracker.class);
        SecuritySignalService securitySignalService = mock(SecuritySignalService.class);

        AgentOpsProperties props = new AgentOpsProperties();
        OpsPathPolicy opsPathPolicy = new OpsPathPolicy(props);
        ReflectionTestUtils.setField(opsPathPolicy, "logCollectorFilePath", "");
        ReflectionTestUtils.invokeMethod(opsPathPolicy, "init");

        OpsPatrolService service = new OpsPatrolService(
                statisticsService,
                aiLogAlarmService,
                noveltyTracker,
                opsPathPolicy,
                securitySignalService);

        Path root = Files.createTempDirectory("ops-patrol-test");
        Path logsDir = Files.createDirectories(root.resolve("logs"));
        Files.writeString(logsDir.resolve("app.log"), "hello patrol");

        ReflectionTestUtils.setField(service, "inspectRootsRaw", root.toString());
        ReflectionTestUtils.setField(service, "diskWarnPercent", 95.0d);
        ReflectionTestUtils.setField(service, "cpuWarnPercent", 95.0d);
        ReflectionTestUtils.setField(service, "anomalySpikeFactor", 3.0d);
        ReflectionTestUtils.setField(service, "anomalyMinDelta", 100);
        ReflectionTestUtils.setField(service, "errorAlarmMin", 99);
        ReflectionTestUtils.setField(service, "diskHotspotTopN", 5);
        ReflectionTestUtils.setField(service, "diskHotspotMaxDepth", 2);
        ReflectionTestUtils.setField(service, "diskHotspotVisitBudget", 200);
        ReflectionTestUtils.setField(service, "dedupWindowMs", 1L);

        when(statisticsService.getSystemPerformance(null)).thenReturn(Map.of(
                "diskUsage", 30.0,
                "cpuUsage", 20.0,
                "memoryUsage", 40.0));
        when(statisticsService.getAnomalyLogStatistics(1)).thenReturn(Map.of("totalAnomalyLogs", 3));
        when(aiLogAlarmService.getAlarmStatistics(1, null, null)).thenReturn(Map.of(
                "totalAlarms", 1,
                "levelDistribution", List.of(Map.of("level", "INFO", "count", 1))));
        when(noveltyTracker.countFirstSeenInWindow(3_600_000L)).thenReturn(0L);
        when(securitySignalService.summary()).thenReturn(Map.of(
                "hasThreat", true,
                "criticalCount", 1,
                "highOrAboveCount", 1,
                "blockedCount", 0,
                "topTitles", List.of("blocked reverse shell")));
        when(securitySignalService.buildThreatSummaryText(org.mockito.ArgumentMatchers.anyMap()))
                .thenReturn("threat summary");

        service.runPatrolCycle();

        List<Map<String, Object>> findings = service.getLastFindingsSnapshot();
        Map<String, Object> correlation = service.getLastCorrelationSnapshot();

        assertTrue(findings.stream().anyMatch(f -> "security_signal".equals(f.get("code"))));
        assertEquals("threat summary", findings.stream()
                .filter(f -> "security_signal".equals(f.get("code")))
                .findFirst()
                .orElseThrow()
                .get("detail"));
        assertEquals(true, ((Map<?, ?>) correlation.get("securitySignals")).get("hasThreat"));
        assertFalse(((List<?>) correlation.get("diskHotspotsTop")).isEmpty());
    }
}
