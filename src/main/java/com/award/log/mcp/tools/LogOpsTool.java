package com.award.log.mcp.tools;

import com.award.log.mcp.ToolResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 日志运维合一：读（采样分析）+ 写（陈旧日志清理，默认 dry-run）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LogOpsTool {

    private final ObjectMapper objectMapper;
    private final LogAnalysisTool logAnalysisTool;
    private final LogCleanupTool logCleanupTool;

    @Tool(name = "logOpsGateway",
            description = "日志运维合一：operation=analyze|cleanup；cleanup 为写操作默认预览")
    public String logOpsGateway(
            @ToolParam(description = "analyze|cleanup", required = true) String operation,
            @ToolParam(description = "日志路径", required = false) String logPath,
            @ToolParam(description = "分析行数", required = false) Integer lines,
            @ToolParam(description = "最近小时", required = false) Integer sinceHours,
            @ToolParam(description = "关键词", required = false) String keywordContains,
            @ToolParam(description = "cleanup 根路径", required = false) String path,
            @ToolParam(description = "cleanup 天数", required = false) Integer days,
            @ToolParam(description = "dryRun", required = false) Boolean dryRun,
            @ToolParam(description = "confirmDelete", required = false) Boolean confirmDelete
    ) throws JsonProcessingException {
        return executeGateway(operation, logPath, lines, sinceHours, keywordContains, path, days, dryRun, confirmDelete);
    }

    public String executeGateway(
            String operation,
            String logPath,
            Integer lines,
            Integer sinceHours,
            String keywordContains,
            String path,
            Integer days,
            Boolean dryRun,
            Boolean confirmDelete
    ) throws JsonProcessingException {
        String op = operation == null || operation.isBlank() ? "analyze" : operation.trim().toLowerCase();
        return switch (op) {
            case "analyze", "read", "scan" -> {
                if ((sinceHours != null && sinceHours > 0)
                        || (keywordContains != null && !keywordContains.isBlank())) {
                    yield logAnalysisTool.analyzeLogs(logPath, lines, sinceHours, keywordContains);
                }
                yield logAnalysisTool.analyzeLogs(logPath, lines);
            }
            case "cleanup", "clean", "prune" -> logCleanupTool.cleanupOldLogs(path, days, dryRun, confirmDelete);
            default -> unknownOperation(op);
        };
    }

    private String unknownOperation(String op) throws JsonProcessingException {
        return objectMapper.writeValueAsString(
                ToolResult.error("未知 operation: " + op + "；可选 analyze|cleanup", 0L));
    }
}
