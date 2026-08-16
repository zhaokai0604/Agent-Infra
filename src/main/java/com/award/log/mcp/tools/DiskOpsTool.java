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
 * 磁盘运维合一：读（df / 热点 / 综合诊断）+ 写（临时目录清理，默认 dry-run）。
 * <p>HTTP toolName: {@code DiskOpsTool}，通过 {@code operation} 选择能力；兼容独立 {@code DiskTool} 等旧名。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DiskOpsTool {

    private final ObjectMapper objectMapper;
    private final DiskTool diskTool;
    private final DiskAnalyzeTool diskAnalyzeTool;
    private final CleanTempTool cleanTempTool;

    @Tool(name = "diskOpsGateway",
            description = "磁盘运维合一：operation=df|hotspots|analyze|clean-temp；clean-temp 写操作默认预览")
    public String diskOpsGateway(
            @ToolParam(description = "df|hotspots|analyze|clean-temp", required = true) String operation,
            @ToolParam(description = "clean-temp 路径", required = false) String path,
            @ToolParam(description = "clean-temp 天数", required = false) Integer days,
            @ToolParam(description = "clean-temp dryRun", required = false) Boolean dryRun,
            @ToolParam(description = "clean-temp confirmDelete", required = false) Boolean confirmDelete,
            @ToolParam(description = "hotspots/analyze 根路径", required = false) String rootPath,
            @ToolParam(description = "hotspots 深度", required = false) Integer maxDepth,
            @ToolParam(description = "hotspots/analyze 条数", required = false) Integer topN,
            @ToolParam(description = "analyze 是否含热点", required = false) Boolean includeHotspots
    ) throws JsonProcessingException {
        return executeGateway(operation, path, days, dryRun, confirmDelete, rootPath, maxDepth, topN, includeHotspots);
    }

    public String executeGateway(
            String operation,
            String path,
            Integer days,
            Boolean dryRun,
            Boolean confirmDelete,
            String rootPath,
            Integer maxDepth,
            Integer topN,
            Boolean includeHotspots
    ) throws JsonProcessingException {
        String op = operation == null || operation.isBlank() ? "df" : operation.trim().toLowerCase();
        return switch (op) {
            case "df", "usage", "disk" -> diskTool.checkDiskUsage();
            case "hotspots", "insight", "du" -> diskTool.rankDiskUsageUnderPath(rootPath, maxDepth, topN);
            case "analyze", "diagnose" -> diskAnalyzeTool.analyzeDiskPressure(
                    rootPath, includeHotspots != null ? includeHotspots : Boolean.TRUE, topN);
            case "clean-temp", "clean", "cleanup" -> cleanTempTool.cleanTempFiles(path, days, dryRun, confirmDelete);
            default -> unknownOperation(op);
        };
    }

    private String unknownOperation(String op) throws JsonProcessingException {
        return objectMapper.writeValueAsString(
                ToolResult.error("未知 operation: " + op + "；可选 df|hotspots|analyze|clean-temp", 0L));
    }
}
