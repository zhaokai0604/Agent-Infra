package com.award.log.service.impl;

import com.award.log.model.PerformanceData;
import com.award.log.service.StatisticsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PerformanceAnalysisServiceImplTest {

    @Mock
    private StatisticsService statisticsService;

    private PerformanceAnalysisServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new PerformanceAnalysisServiceImpl();
        ReflectionTestUtils.setField(service, "statisticsService", statisticsService);
    }

    @Test
    void recordPerformanceDataShouldAssignIdAndTimestamp() {
        PerformanceData data = new PerformanceData();
        data.setDataType("CPU");
        data.setValue(42.0);
        assertTrue(service.recordPerformanceData(data));
        assertNotNull(data.getId());
        assertNotNull(data.getCollectTime());
    }

    @Test
    void batchRecordPerformanceDataShouldCountSuccesses() {
        PerformanceData first = new PerformanceData();
        first.setDataType("CPU");
        first.setValue(10.0);
        PerformanceData second = new PerformanceData();
        second.setDataType("MEMORY");
        second.setValue(20.0);
        assertEquals(2, service.batchRecordPerformanceData(List.of(first, second)));
    }

    @Test
    void getPerformanceTrendShouldAggregateByInterval() {
        LocalDateTime now = LocalDateTime.now();
        PerformanceData point = new PerformanceData();
        point.setDataType("CPU");
        point.setValue(50.0);
        point.setCollectTime(now.minusMinutes(5));
        service.recordPerformanceData(point);

        List<Map<String, Object>> trend = service.getPerformanceTrend(
                "CPU", now.minusMinutes(10), now.plusMinutes(1), 5);
        assertFalse(trend.isEmpty());
        assertTrue(trend.get(0).containsKey("value"));
    }

    @Test
    void getPerformanceStatisticsShouldReturnAggregates() {
        LocalDateTime now = LocalDateTime.now();
        PerformanceData point = new PerformanceData();
        point.setDataType("DISK");
        point.setValue(70.0);
        point.setCollectTime(now);
        service.recordPerformanceData(point);

        Map<String, Object> stats = service.getPerformanceStatistics("DISK", now.minusHours(1), now.plusMinutes(1));
        assertEquals("DISK", stats.get("dataType"));
        assertNotNull(stats.get("count"));
        assertTrue(((Number) stats.get("count")).longValue() >= 1);
    }
}
