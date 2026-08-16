package com.award.log.controller;

import com.award.log.service.StatisticsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class StatisticsControllerSmokeTest {

    @Mock
    private StatisticsService statisticsService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        StatisticsController controller = new StatisticsController();
        ReflectionTestUtils.setField(controller, "statisticsService", statisticsService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void logSummaryReturnsData() throws Exception {
        when(statisticsService.getRecentLogSummary(7)).thenReturn(Map.of("totalLogs", 10));

        mockMvc.perform(get("/admin/statistics/log-summary").param("days", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.totalLogs").value(10));
    }

    @Test
    void performanceAndAnomalyEndpoints() throws Exception {
        when(statisticsService.getSystemPerformance(null)).thenReturn(Map.of("cpuUsage", 12.0));
        when(statisticsService.getAnomalyLogStatistics(14)).thenReturn(Map.of("anomalyLogs", 3));

        mockMvc.perform(get("/admin/statistics/performance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.cpuUsage").value(12.0));

        mockMvc.perform(get("/admin/statistics/anomaly-statistics").param("days", "14"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.anomalyLogs").value(3));
    }

    @Test
    void taskStatusReturnsData() throws Exception {
        when(statisticsService.getTaskStatusStatistics()).thenReturn(Map.of("running", 1));

        mockMvc.perform(get("/admin/statistics/task-status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.running").value(1));
    }
}
