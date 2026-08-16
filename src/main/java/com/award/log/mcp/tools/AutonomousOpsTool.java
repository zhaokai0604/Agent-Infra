package com.award.log.mcp.tools;

import com.award.log.agent.OpsPatrolAutomationService;
import com.award.log.agent.OpsRunResult;
import com.award.log.mcp.McpToolResponses;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 兼容旧自主运维工具名，现统一转为巡检自动修复单轨入口。
 */
@Component
public class AutonomousOpsTool {

    private final OpsPatrolAutomationService patrolAutomationService;
    private final ObjectMapper objectMapper;

    public AutonomousOpsTool(OpsPatrolAutomationService patrolAutomationService, ObjectMapper objectMapper) {
        this.patrolAutomationService = patrolAutomationService;
        this.objectMapper = objectMapper;
    }

    @Tool(name = "runAutonomousOps",
            description = "触发一次巡检自动修复单轨，返回巡检摘要、自动修复结果与待确认方案。")
    public String runAutonomousOps(
            @ToolParam(description = "用户意图摘要，可选", required = false) String userIntent,
            @ToolParam(description = "兼容旧参数，当前保留但仅触发巡检单轨", required = false) Boolean forceRemediate
    ) throws JsonProcessingException {
        long start = System.currentTimeMillis();
        OpsRunResult result = patrolAutomationService.run();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("traceId", result.traceId());
        payload.put("markdown", result.markdown());
        payload.put("report", result.report());
        payload.put("mode", "PATROL_AUTOMATION");
        if (userIntent != null && !userIntent.isBlank()) {
            payload.put("userIntent", userIntent.trim());
        }
        if (forceRemediate != null) {
            payload.put("forceRemediateAccepted", forceRemediate);
        }

        long duration = System.currentTimeMillis() - start;
        String dataJson = objectMapper.writeValueAsString(payload);
        if (hasFailureSigns(result.report())) {
            return McpToolResponses.warn(objectMapper, dataJson, duration);
        }
        return McpToolResponses.success(objectMapper, dataJson, duration);
    }

    private static boolean hasFailureSigns(Map<String, Object> report) {
        if (report == null || report.isEmpty()) {
            return false;
        }
        Object findings = report.get("findings");
        if (findings instanceof List<?> list && !list.isEmpty()) {
            return true;
        }
        Object remediation = report.get("remediation");
        if (remediation instanceof Map<?, ?> rem) {
            Object statusObj = rem.get("status");
            String status = statusObj != null ? String.valueOf(statusObj) : "";
            if (Set.of("EXECUTION_PARTIAL", "MANUAL_REVIEW", "PENDING_CONFIRMATION", "EXECUTED_WITH_PENDING")
                    .contains(status)) {
                return true;
            }
            Object actions = rem.get("actions");
            if (actions instanceof List<?> acts) {
                for (Object item : acts) {
                    if (item instanceof Map<?, ?> action && Boolean.FALSE.equals(action.get("success"))) {
                        return true;
                    }
                }
            }
        }
        Object pending = report.get("pending");
        return pending instanceof Map<?, ?> p && Boolean.TRUE.equals(p.get("hasPending"));
    }

    public String executeGateway(String userIntent, Boolean forceRemediate) throws JsonProcessingException {
        return runAutonomousOps(userIntent, forceRemediate);
    }
}
