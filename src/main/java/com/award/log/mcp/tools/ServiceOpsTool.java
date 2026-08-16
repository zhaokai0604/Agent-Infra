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
 * 服务运维合一：读（failed / status）+ 写（白名单内重启，默认 dry-run）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ServiceOpsTool {

    private final ObjectMapper objectMapper;
    private final SystemdTool systemdTool;
    private final ServiceRestartTool serviceRestartTool;

    @Tool(name = "serviceOpsGateway",
            description = "服务运维合一：operation=failed|status|restart；restart 为写操作默认预览")
    public String serviceOpsGateway(
            @ToolParam(description = "failed|status|restart", required = true) String operation,
            @ToolParam(description = "服务名", required = false) String serviceName,
            @ToolParam(description = "restart dryRun", required = false) Boolean dryRun,
            @ToolParam(description = "restart confirmRestart", required = false) Boolean confirmRestart
    ) throws JsonProcessingException {
        return executeGateway(operation, serviceName, dryRun, confirmRestart);
    }

    public String executeGateway(
            String operation,
            String serviceName,
            Boolean dryRun,
            Boolean confirmRestart
    ) throws JsonProcessingException {
        String op = operation == null || operation.isBlank() ? "failed" : operation.trim().toLowerCase();
        return switch (op) {
            case "failed", "list-failed" -> systemdTool.listFailedSystemdUnits();
            case "status", "get" -> systemdTool.getServiceStatus(serviceName);
            case "restart", "reload" -> serviceRestartTool.restartService(serviceName, dryRun, confirmRestart);
            default -> unknownOperation(op);
        };
    }

    private String unknownOperation(String op) throws JsonProcessingException {
        return objectMapper.writeValueAsString(
                ToolResult.error("未知 operation: " + op + "；可选 failed|status|restart", 0L));
    }
}
