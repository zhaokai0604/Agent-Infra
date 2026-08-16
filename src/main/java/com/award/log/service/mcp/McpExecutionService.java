package com.award.log.service.mcp;

import com.award.log.mcp.McpToolPayloadParser;
import com.award.log.mcp.dispatch.McpToolDispatchResult;
import com.award.log.mcp.dispatch.McpToolDispatcher;
import com.award.log.security.ChatToolExecutionTracker;
import com.award.log.security.McpToolSurface;
import com.award.log.security.OpsSecurityContext;
import com.award.log.security.WriteExecutionCoordinator;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * MCP 工具实际分发执行（含 OpsSecurityContext 生命周期）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class McpExecutionService {

    private final McpToolDispatcher mcpToolDispatcher;
    private final ObjectMapper objectMapper;

    public Map<String, Object> execute(String toolName, Map<String, Object> parameters, long startTime,
                                       String traceId, String userInstruction) {
        return execute(toolName, parameters, startTime, traceId, userInstruction, false);
    }

    public Map<String, Object> execute(String toolName, Map<String, Object> parameters, long startTime,
                                       String traceId, String userInstruction, boolean userConfirmedWrite) {
        OpsSecurityContext.open(traceId, userInstruction, true, McpToolSurface.FULL, false, userConfirmedWrite);
        ChatToolExecutionTracker.clear();
        try {
            Map<String, Object> response = new HashMap<>();
            McpToolDispatchResult dispatched = mcpToolDispatcher.dispatch(toolName, parameters);
            long duration = System.currentTimeMillis() - startTime;
            String resultJson = dispatched.success() && dispatched.data() != null
                    ? String.valueOf(dispatched.data())
                    : null;

            boolean toolOk = dispatched.success()
                    && resultJson != null
                    && McpToolPayloadParser.isSuccessful(objectMapper, resultJson);
            String status = resultJson != null
                    ? McpToolPayloadParser.statusOf(objectMapper, resultJson)
                    : "ERROR";
            if (toolOk) {
                response.put("success", true);
                response.put("status", status);
                response.put("data", dispatched.data());
                response.put("duration", duration);
                if ("WARN".equalsIgnoreCase(status)) {
                    log.warn("工具 {} 执行完成但为 WARN，耗时: {}ms", toolName, duration);
                } else {
                    log.info("工具 {} 执行成功，耗时: {}ms", toolName, duration);
                }
            } else {
                response.put("success", false);
                response.put("status", "ERROR");
                String err = dispatched.success()
                        ? McpToolPayloadParser.errorMessage(objectMapper, resultJson)
                        : dispatched.errorMessage();
                response.put("error", err != null ? err : "工具执行失败");
                if (dispatched.success() && dispatched.data() != null) {
                    response.put("data", dispatched.data());
                }
                response.put("duration", duration);
                log.info("工具 {} 执行失败: {}", toolName, response.get("error"));
            }
            WriteExecutionCoordinator.attachWriteMismatchIfNeeded(toolName, parameters, resultJson, response);
            if (Boolean.TRUE.equals(response.get("writeMismatch"))) {
                response.put("success", false);
                if (!response.containsKey("error") || String.valueOf(response.get("error")).isBlank()) {
                    response.put("error", String.valueOf(response.get("writeMismatchMessage")));
                }
            }
            if (Boolean.TRUE.equals(response.get("evidenceIncomplete"))) {
                response.put("success", false);
                if (!response.containsKey("error") || String.valueOf(response.get("error")).isBlank()) {
                    response.put("error", String.valueOf(response.get("evidenceMessage")));
                }
            }
            return response;
        } finally {
            ChatToolExecutionTracker.clear();
            OpsSecurityContext.clear();
        }
    }
}
