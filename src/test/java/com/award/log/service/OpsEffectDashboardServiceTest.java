package com.award.log.service;

import com.award.log.agent.RemediationEffectEvaluator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Date;
import java.sql.ResultSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OpsEffectDashboardServiceTest {

    @Mock JdbcTemplate jdbcTemplate;
    @Mock OpsAuditTraceService opsAuditTraceService;
    @Mock PatrolHistoryService patrolHistoryService;
    @Mock StatisticsService statisticsService;
    @Mock RemediationEffectEvaluator remediationEffectEvaluator;

    private OpsEffectDashboardService service;

    @BeforeEach
    void setUp() {
        service = new OpsEffectDashboardService(
                jdbcTemplate,
                opsAuditTraceService,
                patrolHistoryService,
                statisticsService,
                remediationEffectEvaluator
        );
    }

    @Test
    void buildDashboardWithEmptyDataStillReturnsStructure() {
        when(jdbcTemplate.queryForMap(anyString(), any(Object.class)))
                .thenThrow(new DataAccessResourceFailureException("no table"));
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object.class)))
                .thenReturn(List.of());
        when(jdbcTemplate.queryForList(anyString(), eq(String.class), any(Object.class)))
                .thenReturn(List.of());
        when(patrolHistoryService.countByDay(anyInt())).thenReturn(List.of());
        when(remediationEffectEvaluator.captureMetrics()).thenReturn(Map.of("diskUsagePct", 72.0));
        when(statisticsService.getSystemPerformance(isNull())).thenReturn(Map.of(
                "diskUsage", 72.0,
                "cpuUsage", 15.0,
                "memoryUsage", 40.0
        ));
        when(opsAuditTraceService.listRecent(anyInt())).thenReturn(List.of());

        Map<String, Object> dashboard = service.buildDashboard(7);

        assertEquals(7, dashboard.get("periodDays"));
        assertNotNull(dashboard.get("overallScore"));
        assertNotNull(dashboard.get("overallGrade"));
        assertNotNull(dashboard.get("dimensions"));
        assertNotNull(dashboard.get("kpis"));
        assertNotNull(dashboard.get("valueStatement"));
    }

    @Test
    void buildDashboardClampsPeriodDays() {
        when(jdbcTemplate.queryForMap(anyString(), any(Object.class)))
                .thenThrow(new DataAccessResourceFailureException("no table"));
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object.class)))
                .thenReturn(List.of());
        when(jdbcTemplate.queryForList(anyString(), eq(String.class), any(Object.class)))
                .thenReturn(List.of());
        when(patrolHistoryService.countByDay(anyInt())).thenReturn(List.of());
        when(remediationEffectEvaluator.captureMetrics()).thenReturn(Map.of());
        when(statisticsService.getSystemPerformance(isNull())).thenReturn(Map.of());
        when(opsAuditTraceService.listRecent(anyInt())).thenReturn(List.of());

        Map<String, Object> dashboard = service.buildDashboard(90);
        assertEquals(30, dashboard.get("periodDays"));
    }

    @Test
    void buildDashboardUsesStructuredReleasedBytesAndDedupedHealingScores() throws Exception {
        Map<String, Object> auditRow = new LinkedHashMap<>();
        auditRow.put("total", 20);
        auditRow.put("success_exec", 15);
        auditRow.put("blocked", 3);
        auditRow.put("need_confirm", 2);
        auditRow.put("pass_cnt", 12);
        auditRow.put("mcp_cnt", 10);
        auditRow.put("assistant_cnt", 10);
        auditRow.put("avg_duration", 2500L);

        when(jdbcTemplate.queryForMap(anyString(), any(Object.class))).thenReturn(auditRow);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object.class)))
                .thenAnswer(invocation -> {
                    String sql = invocation.getArgument(0);
                    RowMapper<?> mapper = invocation.getArgument(1);
                    if (sql.contains("GROUP BY DATE(created_at)")) {
                        ResultSet rs = mock(ResultSet.class);
                        when(rs.getDate("day")).thenReturn(Date.valueOf("2024-06-01"));
                        when(rs.getInt("total")).thenReturn(5);
                        when(rs.getInt("success_exec")).thenReturn(4);
                        when(rs.getInt("blocked")).thenReturn(1);
                        return List.of(mapper.mapRow(rs, 0));
                    }
                    if (sql.contains("healingScore")) {
                        ResultSet rs = mock(ResultSet.class);
                        when(rs.getString("steps_json")).thenReturn(
                                "[{\"phase\":\"effect\",\"detail\":{\"healingScore\":88,\"diskUsageDeltaPct\":2.5}}]");
                        when(rs.getString("result_summary")).thenReturn("自愈评分 88/100");
                        when(rs.getTimestamp("created_at")).thenReturn(null);
                        when(rs.getString("trace_id")).thenReturn("trace-heal-1");
                        return List.of(mapper.mapRow(rs, 0));
                    }
                    return List.of();
                });
        when(jdbcTemplate.queryForList(anyString(), eq(String.class), any(Object.class)))
                .thenReturn(List.of(
                        "[{\"phase\":\"execute\",\"toolName\":\"CleanTempTool\",\"mode\":\"EXECUTE\",\"success\":true,"
                                + "\"detail\":\"{\\\"success\\\":true,\\\"data\\\":{\\\"filesDeleted\\\":120,\\\"bytesFreed\\\":1610612736}}\"}]"
                ));
        when(patrolHistoryService.countByDay(anyInt())).thenReturn(List.of(
                Map.of("day", "2024-06-01", "runCount", 4L, "alertCount", 10L),
                Map.of("day", "2024-06-02", "runCount", 3L, "alertCount", 2L),
                Map.of("day", "2024-06-03", "runCount", 2L, "alertCount", 1L),
                Map.of("day", "2024-06-04", "runCount", 2L, "alertCount", 1L)
        ));
        when(remediationEffectEvaluator.captureMetrics()).thenReturn(Map.of("diskUsagePct", 68.0));
        when(statisticsService.getSystemPerformance(isNull())).thenReturn(Map.of(
                "diskUsage", 68.0,
                "cpuUsage", 82.0,
                "memoryUsage", 55.0
        ));
        when(opsAuditTraceService.listRecent(anyInt())).thenReturn(List.of(
                Map.of(
                        "traceId", "trace-heal-1",
                        "resultSummary", "自愈评分 91/100",
                        "createdAt", "2024-06-01T00:00:00Z",
                        "channel", "MCP"
                )
        ));

        Map<String, Object> dashboard = service.buildDashboard(14);

        assertEquals(14, dashboard.get("periodDays"));
        assertTrue((Integer) dashboard.get("overallScore") > 0);

        @SuppressWarnings("unchecked")
        Map<String, Object> dimensions = (Map<String, Object>) dashboard.get("dimensions");
        @SuppressWarnings("unchecked")
        Map<String, Object> healing = (Map<String, Object>) dimensions.get("healing");
        @SuppressWarnings("unchecked")
        Map<String, Object> resource = (Map<String, Object>) dimensions.get("resource");
        @SuppressWarnings("unchecked")
        Map<String, Object> kpis = (Map<String, Object>) dashboard.get("kpis");
        @SuppressWarnings("unchecked")
        Map<String, Object> valueStatement = (Map<String, Object>) dashboard.get("valueStatement");

        assertEquals(Boolean.TRUE, healing.get("measured"));
        assertEquals(88L, valueStatement.get("avgHealingScore"));
        assertEquals(88.0, kpis.get("avgHealingScore"));
        assertEquals(1.5, kpis.get("releasedSpaceGb"));
        assertTrue(String.valueOf(resource.get("detail")).contains("1.50"));
    }

    @Test
    void buildDashboardMarksHealingAsUnmeasuredWithoutRealSamples() {
        Map<String, Object> auditRow = new LinkedHashMap<>();
        auditRow.put("total", 5);
        auditRow.put("success_exec", 5);
        auditRow.put("blocked", 0);
        auditRow.put("need_confirm", 0);
        auditRow.put("pass_cnt", 5);
        auditRow.put("mcp_cnt", 5);
        auditRow.put("assistant_cnt", 0);
        auditRow.put("avg_duration", 1000L);

        when(jdbcTemplate.queryForMap(anyString(), any(Object.class))).thenReturn(auditRow);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object.class)))
                .thenAnswer(invocation -> {
                    String sql = invocation.getArgument(0);
                    RowMapper<?> mapper = invocation.getArgument(1);
                    if (sql.contains("GROUP BY DATE(created_at)")) {
                        ResultSet rs = mock(ResultSet.class);
                        when(rs.getDate("day")).thenReturn(Date.valueOf("2024-06-01"));
                        when(rs.getInt("total")).thenReturn(5);
                        when(rs.getInt("success_exec")).thenReturn(5);
                        when(rs.getInt("blocked")).thenReturn(0);
                        return List.of(mapper.mapRow(rs, 0));
                    }
                    return List.of();
                });
        when(jdbcTemplate.queryForList(anyString(), eq(String.class), any(Object.class)))
                .thenReturn(List.of(
                        "[{\"phase\":\"execute\",\"toolName\":\"LogCleanupTool\",\"mode\":\"EXECUTE\",\"success\":true,"
                                + "\"detail\":\"{\\\"success\\\":true,\\\"data\\\":{\\\"filesDeleted\\\":7}}\"}]"
                ));
        when(patrolHistoryService.countByDay(anyInt())).thenReturn(List.of());
        when(remediationEffectEvaluator.captureMetrics()).thenReturn(Map.of("diskUsagePct", 40.0));
        when(statisticsService.getSystemPerformance(isNull())).thenReturn(Map.of(
                "diskUsage", 40.0,
                "cpuUsage", 10.0,
                "memoryUsage", 30.0
        ));
        when(opsAuditTraceService.listRecent(anyInt())).thenReturn(List.of());

        Map<String, Object> dashboard = service.buildDashboard(7);

        @SuppressWarnings("unchecked")
        Map<String, Object> dimensions = (Map<String, Object>) dashboard.get("dimensions");
        @SuppressWarnings("unchecked")
        Map<String, Object> healing = (Map<String, Object>) dimensions.get("healing");
        @SuppressWarnings("unchecked")
        Map<String, Object> kpis = (Map<String, Object>) dashboard.get("kpis");

        assertNull(healing.get("score"));
        assertEquals(Boolean.FALSE, healing.get("measured"));
        assertNull(kpis.get("avgHealingScore"));
        assertEquals(0.0, kpis.get("releasedSpaceGb"));
        assertFalse(((List<?>) dashboard.get("recentEffectRuns")).size() > 0);
    }
}
