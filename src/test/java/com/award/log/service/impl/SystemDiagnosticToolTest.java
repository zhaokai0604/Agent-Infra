package com.award.log.service.impl;

import com.award.log.model.LogAnalysisDetail;
import com.award.log.service.AiTool;
import com.award.log.service.LogQueryFallbackService;
import com.award.log.service.StatisticsService;
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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SystemDiagnosticToolTest {

    @Mock
    private LogQueryFallbackService logQueryFallbackService;
    @Mock
    private StatisticsService statisticsService;

    private SystemDiagnosticTool tool;

    @BeforeEach
    void setUp() {
        tool = new SystemDiagnosticTool();
        ReflectionTestUtils.setField(tool, "logQueryFallbackService", logQueryFallbackService);
        ReflectionTestUtils.setField(tool, "statisticsService", statisticsService);
        ReflectionTestUtils.setField(tool, "elasticsearchOperations", null);
    }

    @Test
    void metadataShouldExposeDiagnosticSchema() {
        assertEquals("diagnose_system", tool.getName());
        assertTrue(tool.getFunctionSchema().containsKey("parameters"));
    }

    @Test
    void executeShouldIncludePerformanceSection() {
        when(statisticsService.getSystemPerformance(null)).thenReturn(Map.of(
                "cpuUsage", 20.0,
                "memoryUsage", 30.0,
                "diskUsage", 40.0));

        AiTool.ToolResult result = tool.execute(Map.of("focusArea", "performance"));
        assertTrue(result.isSuccess());
        assertTrue(result.getContent().contains("系统诊断报告"));
        assertTrue(result.getContent().contains("实时性能"));
    }

    @Test
    void executeShouldSummarizeErrorsFromFallbackData() {
        lenient().when(statisticsService.getSystemPerformance(null)).thenReturn(Map.of(
                "cpuUsage", 10.0, "memoryUsage", 10.0, "diskUsage", 10.0));
        LogAnalysisDetail row = new LogAnalysisDetail();
        row.setDesensitizedLog("NullPointerException in handler");
        row.setSeverity("ERROR");
        when(logQueryFallbackService.queryRecent(anyInt(), isNull(), isNull(), eq(false), anyInt()))
                .thenReturn(List.of(row));
        lenient().when(logQueryFallbackService.queryRecent(anyInt(), isNull(), isNull(), eq(true), anyInt()))
                .thenReturn(List.of());

        AiTool.ToolResult result = tool.execute(Map.of("focusArea", "errors"));
        assertTrue(result.isSuccess());
        assertTrue(result.getContent().contains("错误日志分析"));
    }
}
