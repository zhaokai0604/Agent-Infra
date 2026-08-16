package com.award.log.agent;

import com.award.log.model.LogAlarm;
import com.award.log.security.signal.SecuritySignalService;
import com.award.log.service.AiLogAlarmService;
import com.award.log.service.KnowledgeBaseService;
import com.award.log.service.OpsPatrolService;
import com.award.log.service.StatisticsService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MultiAgentContextBuilderTest {

    @Test
    void buildIncludesSecuritySignalsAcrossPerceptionDiagnosisAndCorrelationDigest() {
        StatisticsService statisticsService = mock(StatisticsService.class);
        AiLogAlarmService aiLogAlarmService = mock(AiLogAlarmService.class);
        DrainTemplateNoveltyTracker noveltyTracker = mock(DrainTemplateNoveltyTracker.class);
        SecuritySignalService securitySignalService = mock(SecuritySignalService.class);
        OpsPatrolService patrolService = mock(OpsPatrolService.class);

        when(statisticsService.getSystemPerformance(null)).thenReturn(Map.of(
                "cpuUsage", 10,
                "memoryUsage", 30,
                "diskUsage", 20,
                "networkUsage", 5));
        when(statisticsService.getTaskStatusStatistics()).thenReturn(Map.of("running", 1));
        when(statisticsService.getAnomalyLogStatistics(1)).thenReturn(Map.of("totalAnomalyLogs", 0));
        when(aiLogAlarmService.getAlarmStatistics(1, null, null)).thenReturn(Map.of("totalAlarms", 0, "successRate", 1.0));
        when(aiLogAlarmService.getAlarmHistory(1, 8, null, null)).thenReturn(Map.of("list", List.of()));
        when(noveltyTracker.snapshotForContext()).thenReturn(Map.of("count", 0));
        when(securitySignalService.summary()).thenReturn(Map.of("hasThreat", true, "criticalCount", 1));
        when(securitySignalService.recentHighPriorityAsMaps(5, 3_600_000L))
                .thenReturn(List.of(Map.of("title", "critical alert")));
        when(patrolService.getLastFindingsSnapshot()).thenReturn(List.of(Map.of("code", "security_signal")));

        MultiAgentContextBuilder builder = new MultiAgentContextBuilder(
                statisticsService,
                aiLogAlarmService,
                noveltyTracker,
                securitySignalService);
        ReflectionTestUtils.setField(builder, "opsPatrolService", patrolService);

        Map<String, Object> root = builder.buildForUser("check security");

        @SuppressWarnings("unchecked")
        Map<String, Object> perception = (Map<String, Object>) root.get("perceptionAgent");
        @SuppressWarnings("unchecked")
        Map<String, Object> diagnosis = (Map<String, Object>) root.get("diagnosisAgent");
        @SuppressWarnings("unchecked")
        Map<String, Object> correlation = (Map<String, Object>) root.get("correlationDigest");

        assertEquals(true, ((Map<?, ?>) perception.get("securitySignals")).get("hasThreat"));
        assertEquals(1, ((List<?>) diagnosis.get("recentHighPrioritySecuritySignals")).size());
        assertEquals(true, ((Map<?, ?>) correlation.get("securitySignals")).get("hasThreat"));
        assertEquals(1, ((List<?>) correlation.get("recentHighPrioritySecuritySignals")).size());
    }

    @Test
    void buildUsesPrefetchedCorrelationFieldsWithoutRequeryingStats() {
        StatisticsService statisticsService = mock(StatisticsService.class);
        AiLogAlarmService aiLogAlarmService = mock(AiLogAlarmService.class);
        DrainTemplateNoveltyTracker noveltyTracker = mock(DrainTemplateNoveltyTracker.class);
        SecuritySignalService securitySignalService = mock(SecuritySignalService.class);
        OpsPatrolService patrolService = mock(OpsPatrolService.class);

        when(statisticsService.getTaskStatusStatistics()).thenReturn(Map.of("running", 2));
        when(aiLogAlarmService.getAlarmHistory(1, 8, null, null)).thenReturn(Map.of("list", List.of()));
        when(noveltyTracker.snapshotForContext()).thenReturn(Map.of("novel", 2));
        when(securitySignalService.summary()).thenReturn(Map.of("hasThreat", false, "criticalCount", 0));
        when(securitySignalService.recentHighPriorityAsMaps(5, 3_600_000L)).thenReturn(List.of());
        when(patrolService.getLastFindingsSnapshot()).thenReturn(List.of(Map.of("code", "disk")));

        MultiAgentContextBuilder builder = new MultiAgentContextBuilder(
                statisticsService,
                aiLogAlarmService,
                noveltyTracker,
                securitySignalService);
        ReflectionTestUtils.setField(builder, "opsPatrolService", patrolService);

        Map<String, Object> prefetched = Map.of(
                "performance", Map.of("cpuUsage", 61, "memoryUsage", 52, "diskUsage", 43, "networkUsage", 34),
                "alarmStats", Map.of("totalAlarms", 9, "successRate", 0.88),
                "anomalyLogDay1", Map.of("totalAnomalyLogs", 7),
                "recentAlarms", Map.of("list", List.of(Map.of("title", "from-prefetch"))),
                "knowledgeRagHits", List.of(Map.of("title", "cached runbook")));

        Map<String, Object> root = builder.buildForUser("cached", prefetched);

        @SuppressWarnings("unchecked")
        Map<String, Object> correlation = (Map<String, Object>) root.get("correlationDigest");
        @SuppressWarnings("unchecked")
        Map<String, Object> diagnosis = (Map<String, Object>) root.get("diagnosisAgent");

        assertEquals(61, correlation.get("cpuUsagePct"));
        assertEquals(9, correlation.get("alarmTotal24h"));
        assertEquals(0.88, correlation.get("alarmSuccessRate"));
        assertEquals(7, ((Map<?, ?>) correlation.get("anomalyLogDay1")).get("totalAnomalyLogs"));
        assertEquals(List.of("from-prefetch"), diagnosis.get("recentAlarmTitles"));
        assertEquals(List.of(Map.of("title", "cached runbook")), diagnosis.get("similarHistoricalCases"));
    }

    @Test
    void buildDiagnosisFallsBackToAlarmRootCauseAndKnowledgeSearch() {
        StatisticsService statisticsService = mock(StatisticsService.class);
        AiLogAlarmService aiLogAlarmService = mock(AiLogAlarmService.class);
        DrainTemplateNoveltyTracker noveltyTracker = mock(DrainTemplateNoveltyTracker.class);
        SecuritySignalService securitySignalService = mock(SecuritySignalService.class);
        KnowledgeBaseService knowledgeBaseService = mock(KnowledgeBaseService.class);

        LogAlarm alarm = new LogAlarm();
        alarm.setRootCause("database lock contention");
        alarm.setLogContent("ignored because root cause wins");

        when(statisticsService.getSystemPerformance(null)).thenReturn(Map.of("cpuUsage", 81, "diskUsage", 72));
        when(statisticsService.getTaskStatusStatistics()).thenReturn(Map.of());
        when(statisticsService.getAnomalyLogStatistics(1)).thenReturn(Map.of("totalAnomalyLogs", 2));
        when(aiLogAlarmService.getAlarmStatistics(1, null, null)).thenReturn(Map.of("totalAlarms", 1));
        when(aiLogAlarmService.getAlarmHistory(1, 8, null, null)).thenReturn(Map.of("list", List.of(alarm)));
        when(noveltyTracker.snapshotForContext()).thenReturn(Map.of());
        when(securitySignalService.summary()).thenReturn(Map.of("hasThreat", false));
        when(securitySignalService.recentHighPriorityAsMaps(5, 3_600_000L)).thenReturn(List.of());
        when(knowledgeBaseService.search(anyString(), eq(5))).thenReturn(List.of(Map.of("title", "runbook-match")));

        MultiAgentContextBuilder builder = new MultiAgentContextBuilder(
                statisticsService,
                aiLogAlarmService,
                noveltyTracker,
                securitySignalService);
        ReflectionTestUtils.setField(builder, "knowledgeBaseService", knowledgeBaseService);

        Map<String, Object> root = builder.buildForUser("database cpu alert");

        @SuppressWarnings("unchecked")
        Map<String, Object> diagnosis = (Map<String, Object>) root.get("diagnosisAgent");

        assertEquals(List.of("database lock contention"), diagnosis.get("recentAlarmTitles"));
        assertEquals(List.of(Map.of("title", "runbook-match")), diagnosis.get("similarHistoricalCases"));
        verify(knowledgeBaseService).search(anyString(), eq(5));
        assertTrue(((List<?>) diagnosis.get("recentAlarmTitles")).contains("database lock contention"));
    }
}
