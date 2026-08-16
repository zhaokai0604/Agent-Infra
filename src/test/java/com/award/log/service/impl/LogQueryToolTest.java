package com.award.log.service.impl;

import com.award.log.model.LogAnalysisDetail;
import com.award.log.service.AiTool;
import com.award.log.service.LogQueryFallbackService;
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
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LogQueryToolTest {

    @Mock
    private LogQueryFallbackService logQueryFallbackService;

    private LogQueryTool tool;

    @BeforeEach
    void setUp() {
        tool = new LogQueryTool();
        ReflectionTestUtils.setField(tool, "logQueryFallbackService", logQueryFallbackService);
        ReflectionTestUtils.setField(tool, "elasticsearchOperations", null);
    }

    @Test
    void metadataShouldExposeQuerySchema() {
        assertEquals("query_logs", tool.getName());
        assertTrue(tool.getFunctionSchema().containsKey("parameters"));
    }

    @Test
    void executeShouldUseMysqlFallbackWhenEsUnavailable() {
        LogAnalysisDetail row = new LogAnalysisDetail();
        row.setDesensitizedLog("NullPointerException");
        row.setSeverity("ERROR");
        when(logQueryFallbackService.queryRecent(anyInt(), any(), any(), isNull(), anyInt()))
                .thenReturn(List.of(row));

        AiTool.ToolResult result = tool.execute(Map.of(
                "timeRange", "最近1小时",
                "severity", "ERROR",
                "keywords", "NullPointer"));
        assertTrue(result.isSuccess());
        assertTrue(result.getContent().contains("查询结果"));
    }

    @Test
    void executeShouldReturnEmptyMessageWhenNoRowsFound() {
        when(logQueryFallbackService.queryRecent(anyInt(), any(), any(), isNull(), anyInt()))
                .thenReturn(List.of());

        AiTool.ToolResult result = tool.execute(Map.of("timeRange", "今天"));
        assertTrue(result.isSuccess());
        assertTrue(result.getContent().contains("没有找到日志记录"));
    }
}
