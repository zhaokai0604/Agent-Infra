package com.award.log.controller;

import com.award.log.service.PerformanceAnalysisService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class PerformanceAnalysisControllerSmokeTest {

    @Mock private PerformanceAnalysisService performanceAnalysisService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        PerformanceAnalysisController controller = new PerformanceAnalysisController();
        ReflectionTestUtils.setField(controller, "performanceAnalysisService", performanceAnalysisService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void trendReturnsPerformanceData() throws Exception {
        when(performanceAnalysisService.getPerformanceTrend(eq("cpu"), any(LocalDateTime.class), any(LocalDateTime.class), eq(5)))
                .thenReturn(List.of(Map.of("points", 10)));

        mockMvc.perform(get("/api/performance/trend")
                        .param("dataType", "cpu")
                        .param("startTime", "2026-06-01T00:00:00")
                        .param("endTime", "2026-06-02T00:00:00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0].points").value(10));
    }

    @Test
    void statisticsReturnsSummary() throws Exception {
        when(performanceAnalysisService.getPerformanceStatistics(eq("cpu"), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(Map.of("avg", 42.0));

        mockMvc.perform(get("/api/performance/statistics")
                        .param("dataType", "cpu")
                        .param("startTime", "2026-06-01T00:00:00")
                        .param("endTime", "2026-06-02T00:00:00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.avg").value(42.0));
    }
}
