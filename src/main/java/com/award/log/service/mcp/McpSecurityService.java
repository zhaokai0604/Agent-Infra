package com.award.log.service.mcp;

import com.award.log.security.McpInvocationSecurityGate;
import com.award.log.security.McpInvocationSecurityGate.GateDecision;
import com.award.log.security.McpInvocationSecurityGate.McpSecurityProfile;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * MCP 安全门控与阻断/待确认响应构建。
 */
@Service
@RequiredArgsConstructor
public class McpSecurityService {

    private final McpInvocationSecurityGate securityGate;

    public GateDecision evaluateInitial(String toolName, Map<String, Object> parameters, String userMessage) {
        return securityGate.evaluate(toolName, parameters, userMessage, McpSecurityProfile.INITIAL_REQUEST);
    }

    public GateDecision evaluatePostConfirm(String toolName, Map<String, Object> parameters, String userMessage) {
        return securityGate.evaluate(toolName, parameters, userMessage, McpSecurityProfile.POST_CONFIRMATION);
    }

    public GateDecision evaluateDeferred(String toolName, Map<String, Object> parameters) {
        return securityGate.evaluate(toolName, parameters, McpSecurityProfile.DEFERRED_SCHEDULED);
    }

    public String buildInstruction(String toolName, Map<String, Object> parameters) {
        return securityGate.buildInstruction(toolName, parameters);
    }

    public String buildAuditInstruction(String userMessage, String toolName, Map<String, Object> parameters) {
        String built = buildInstruction(toolName, parameters);
        if (userMessage == null || userMessage.isBlank()) {
            return built;
        }
        return "用户: " + userMessage.trim() + " | " + built;
    }

    public Map<String, Object> buildBlockResponse(String traceId, long startTime, GateDecision decision) {
        Map<String, Object> response = new HashMap<>();
        response.put("success", false);
        response.put("error", decision.getMessage());
        response.put("securityCode", decision.getCode());
        response.put("traceId", traceId);
        response.put("duration", System.currentTimeMillis() - startTime);
        attachAgenticRisk(response, decision);
        return response;
    }

    public Map<String, Object> buildNeedConfirmResponse(
            String traceId, long startTime, GateDecision decision, String toolName, Map<String, Object> parameters) {
        Map<String, Object> response = new HashMap<>();
        response.put("success", false);
        response.put("needConfirm", true);
        response.put("message", decision.getMessage() != null ? decision.getMessage()
                : "该操作存在风险，请在界面点击确认（等同口令「确认执行」）");
        response.put("riskLevel", decision.getRiskLevel().name());
        attachAgenticRisk(response, decision);
        response.put("toolName", toolName);
        response.put("parameters", parameters);
        response.put("traceId", traceId);
        response.put("duration", System.currentTimeMillis() - startTime);
        return response;
    }

    public static void attachAgenticRisk(Map<String, Object> response, GateDecision decision) {
        if (decision.getAgenticRiskScore() != null) {
            response.put("riskScore", decision.getAgenticRiskScore());
        }
        if (decision.getAgenticRiskDimensions() != null) {
            response.put("riskDimensions", decision.getAgenticRiskDimensions());
        }
        if (decision.getAgenticRiskExplanation() != null) {
            response.put("riskExplanation", decision.getAgenticRiskExplanation());
        }
    }

    public static String mapGateCodeToOutcome(String code) {
        if (code == null) {
            return "SECURITY_REJECT";
        }
        return switch (code) {
            case "INJECTION" -> "REJECT_INJECTION";
            case "HIGH_INTENT" -> "REJECT_HIGH_INTENT";
            case "INTENT_TOOL_MISMATCH" -> "REJECT_INTENT_MISMATCH";
            case "HIGH_RISK_COMMAND" -> "REJECT_HIGH_RISK_COMMAND";
            case "RISK_SCORE_HIGH" -> "REJECT_RISK_SCORE";
            default -> "SECURITY_REJECT";
        };
    }

    public static String formatRiskScoreLine(GateDecision decision) {
        if (decision.getAgenticRiskScore() != null) {
            return String.format(Locale.ROOT, "风险评分 %.1f/10 已通过门控", decision.getAgenticRiskScore());
        }
        return "门控已放行";
    }
}
