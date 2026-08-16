package com.award.log.service.impl;

import com.award.log.mapper.LogAnalysisDetailMapper;
import com.award.log.mapper.LogAnalysisTaskMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StatisticsServiceImplTest {

    @Mock
    private LogAnalysisTaskMapper taskMapper;
    @Mock
    private LogAnalysisDetailMapper detailMapper;

    private StatisticsServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new StatisticsServiceImpl();
        org.springframework.test.util.ReflectionTestUtils.setField(service, "taskMapper", taskMapper);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "detailMapper", detailMapper);
    }

    @Test
    void getRecentLogSummaryShouldAggregateFromDb() {
        when(taskMapper.countSinceDays(7)).thenReturn(10L);
        when(detailMapper.countSinceDays(7)).thenReturn(100L);
        when(taskMapper.getTaskTrend(7)).thenReturn(List.of(Map.of("day", "2024-01-01", "count", 2)));
        when(detailMapper.selectAnomalySeverityDistribution(7))
                .thenReturn(List.of(Map.of("severity", "ERROR", "count", 5)));

        Map<String, Object> summary = service.getRecentLogSummary(7);
        assertEquals(10L, summary.get("totalTasks"));
        assertEquals(100L, summary.get("totalLogs"));
        assertTrue(summary.containsKey("anomalyRate"));
    }

    @Test
    void getSystemPerformanceShouldReturnMetrics() {
        Map<String, Object> perf = service.getSystemPerformance(null);
        assertNotNull(perf);
        assertTrue(perf.containsKey("cpuUsage"));
        assertTrue(perf.containsKey("memoryUsage"));
        assertTrue(perf.containsKey("diskUsage"));
        assertTrue(perf.containsKey("metricsSource"));

        Map<String, Object> cached = service.getSystemPerformance(null);
        assertNotNull(cached.get("cpuUsage"));
    }

    @Test
    void getTaskStatusStatisticsShouldMapStatusCounts() {
        when(taskMapper.getTaskStatusCount()).thenReturn(List.of(
                Map.of("status", "DONE", "count", 3),
                Map.of("STATUS", "RUNNING", "COUNT", 1)));
        when(taskMapper.getTaskCompletionStatistics()).thenReturn(List.of());
        when(taskMapper.getAverageProcessingTime()).thenReturn(1.5);

        Map<String, Object> stats = service.getTaskStatusStatistics();
        @SuppressWarnings("unchecked")
        Map<String, Integer> statusCount = (Map<String, Integer>) stats.get("statusCount");
        assertEquals(3, statusCount.get("DONE"));
        assertEquals(1, statusCount.get("RUNNING"));
    }

    @Test
    void getAnomalyLogStatisticsShouldUseLocalCache() {
        when(detailMapper.selectAnomalySeverityDistribution(3))
                .thenReturn(List.of(Map.of("count", 4)));

        Map<String, Object> first = service.getAnomalyLogStatistics(3);
        Map<String, Object> second = service.getAnomalyLogStatistics(3);
        assertEquals(first.get("totalAnomalyLogs"), second.get("totalAnomalyLogs"));
        verify(detailMapper, times(1)).selectAnomalySeverityDistribution(3);
    }

    @Test
    void getRecentLogSummaryUsesRedisWhenAvailable() {
        @SuppressWarnings("unchecked")
        org.springframework.data.redis.core.RedisTemplate<String, Object> redis =
                mock(org.springframework.data.redis.core.RedisTemplate.class);
        @SuppressWarnings("unchecked")
        org.springframework.data.redis.core.ValueOperations<String, Object> ops =
                mock(org.springframework.data.redis.core.ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get("statistics:log_summary:v3:5"))
                .thenReturn(Map.of("totalTasks", 99L, "totalLogs", 500L));
        ReflectionTestUtils.setField(service, "redisTemplate", redis);

        Map<String, Object> summary = service.getRecentLogSummary(5);
        assertEquals(99L, summary.get("totalTasks"));
        verify(taskMapper, never()).countSinceDays(anyInt());
    }

    @Test
    void getRecentLogSummaryStoresToRedisAfterDbRead() {
        @SuppressWarnings("unchecked")
        org.springframework.data.redis.core.RedisTemplate<String, Object> redis =
                mock(org.springframework.data.redis.core.RedisTemplate.class);
        @SuppressWarnings("unchecked")
        org.springframework.data.redis.core.ValueOperations<String, Object> ops =
                mock(org.springframework.data.redis.core.ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get(anyString())).thenReturn(null);
        ReflectionTestUtils.setField(service, "redisTemplate", redis);

        when(taskMapper.countSinceDays(4)).thenReturn(2L);
        when(detailMapper.countSinceDays(4)).thenReturn(20L);
        when(taskMapper.getTaskTrend(4)).thenReturn(List.of());
        when(detailMapper.selectAnomalySeverityDistribution(4)).thenReturn(List.of());

        Map<String, Object> summary = service.getRecentLogSummary(4);
        assertEquals(2L, summary.get("totalTasks"));
        verify(ops).set(startsWith("statistics:log_summary:v3:4"), eq(summary), eq(10L),
                eq(java.util.concurrent.TimeUnit.MINUTES));
    }
}
