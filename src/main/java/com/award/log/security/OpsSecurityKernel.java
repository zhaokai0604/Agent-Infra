package com.award.log.security;

import com.award.log.mcp.dispatch.McpToolParamReader;
import com.award.log.security.effect.PlanEffectGate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 安全内核门面：HTTP MCP、编排器、@Tool AOP 统一口径的裁决入口。
 */
@Component
@RequiredArgsConstructor
public class OpsSecurityKernel {

    private final McpInvocationSecurityGate mcpInvocationSecurityGate;
    private final OpsTrustPolicy opsTrustPolicy;
    private final PlanEffectGate planEffectGate;

    public McpInvocationSecurityGate.GateDecision evaluateTool(
            String toolName,
            Map<String, Object> parameters,
            McpInvocationSecurityGate.McpSecurityProfile profile) {
        return mcpInvocationSecurityGate.evaluate(toolName, parameters, profile);
    }

    public McpInvocationSecurityGate.GateDecision evaluateChatTool(
            String toolBeanName,
            String instruction) {
        return mcpInvocationSecurityGate.evaluateChatToolInvocation(
                toolBeanName, Map.of(), instruction, null);
    }

    public PlanEffectGate.PlanDecision evaluatePlan(List<PlanEffectGate.PlannedCall> calls) {
        return planEffectGate.evaluate(calls == null ? List.of() : calls);
    }

    public PlanEffectGate.PlanDecision evaluatePlanMaps(List<Map<String, Object>> steps) {
        List<PlanEffectGate.PlannedCall> calls = new ArrayList<>();
        if (steps != null) {
            for (Map<String, Object> step : steps) {
                if (step == null) {
                    continue;
                }
                Object tool = step.getOrDefault("toolName", step.get("tool"));
                if (tool == null) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> params = step.get("parameters") instanceof Map<?, ?> m
                        ? (Map<String, Object>) m
                        : Map.of();
                calls.add(new PlanEffectGate.PlannedCall(String.valueOf(tool), params));
            }
        }
        return planEffectGate.evaluate(calls);
    }

    public OpsTrustTier trustTierForTool(String toolName, Map<String, Object> parameters, String instruction) {
        return opsTrustPolicy.tierForTool(toolName, parameters, instruction);
    }

    public String buildInstruction(String toolName, Map<String, Object> parameters) {
        return mcpInvocationSecurityGate.buildInstruction(toolName, parameters);
    }

    public void applyConfirmedWriteParams(String toolName, Map<String, Object> parameters, String userMessage) {
        McpToolParamReader.applyConfirmedWriteToolParams(toolName, parameters, userMessage);
    }
}
