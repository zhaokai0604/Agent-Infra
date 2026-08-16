package com.award.log.service.impl;

import com.award.log.service.AiTool;
import com.award.log.mapper.LogAnalysisDetailMapper;
import com.award.log.mapper.LogAnalysisTaskMapper;
import com.award.log.model.LogAnalysisTask;
import com.award.log.service.StatisticsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GenerateReportToolTest {

    @Mock
    private StatisticsService statisticsService;
    @Mock
    private LogAnalysisTaskMapper taskMapper;
    @Mock
    private LogAnalysisDetailMapper detailMapper;

    private GenerateReportTool tool;

    @BeforeEach
    void setUp() {
        tool = new GenerateReportTool(statisticsService, taskMapper, detailMapper);
    }

    @Test
    void metadataShouldExposeToolIdentity() {
        assertEquals("generate_report", tool.getName());
        assertFalse(tool.getDescription().isBlank());
        assertTrue(tool.getFunctionSchema().containsKey("parameters"));
    }

    @Test
    void executeShouldBuildMarkdownReport() {
        when(statisticsService.getRecentLogSummary(7)).thenReturn(Map.of("totalTasks", 2));
        when(statisticsService.getAnomalyLogStatistics(7)).thenReturn(Map.of("totalAnomalyLogs", 5));
        when(statisticsService.getTaskStatusStatistics()).thenReturn(Map.of("statusCount", Map.of("DONE", 2)));
        when(detailMapper.countSinceDays(7)).thenReturn(10L);
        when(taskMapper.selectPage(0, 5)).thenReturn(List.of(new LogAnalysisTask()));

        AiTool.ToolResult result = tool.execute(Map.of("days", 7));
        assertTrue(result.isSuccess());
        assertTrue(result.getContent().contains("日志分析汇总报告"));
    }

    @Test
    void executeShouldClampDaysParameter() {
        when(statisticsService.getRecentLogSummary(30)).thenReturn(Map.of("totalTasks", 0));
        when(statisticsService.getAnomalyLogStatistics(30)).thenReturn(Map.of("totalAnomalyLogs", 0));
        when(statisticsService.getTaskStatusStatistics()).thenReturn(Map.of());
        when(detailMapper.countSinceDays(30)).thenReturn(0L);
        when(taskMapper.selectPage(0, 5)).thenReturn(List.of());

        AiTool.ToolResult result = tool.execute(Map.of("days", 999));
        assertTrue(result.isSuccess());
        verify(statisticsService).getRecentLogSummary(30);
    }
}
