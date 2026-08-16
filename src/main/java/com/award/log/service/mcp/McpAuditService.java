package com.award.log.service.mcp;

import com.award.log.security.HttpAuditSubject;
import com.award.log.security.McpInvocationSecurityGate.GateDecision;
import com.award.log.security.OpsPathPolicy;
import com.award.log.security.RiskLevel;
import com.award.log.service.OpsAuditTraceService;
import com.award.log.trace.TraceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Persistence adapter for MCP audit traces.
 */
@Service
@RequiredArgsConstructor
public class McpAuditService {

    private final OpsAuditTraceService opsAuditTraceService;
    private final HttpAuditSubject httpAuditSubject;
    private final OpsPathPolicy opsPathPolicy;

    public void persistGateReject(String traceId, String userInstruction, String toolName,
                                  GateDecision decision, long startTime, Map<String, Object> parameters) {
        String outcome = McpSecurityService.mapGateCodeToOutcome(decision.getCode());
        String step4 = decision.getAgenticRiskScore() != null
                ? String.format(Locale.ROOT, "风险评分 %.1f/10。安全门代码 %s，拒绝原因 %s",
                decision.getAgenticRiskScore(), decision.getCode(), decision.getMessage())
                : String.format(Locale.ROOT, "安全门代码 %s，拒绝 %s", decision.getCode(), decision.getMessage());
        List<Map<String, Object>> steps = buildCotTrace(userInstruction, toolName, decision, step4);
        opsAuditTraceService.save(traceId, "MCP", userInstruction, RiskLevel.HIGH.name(), outcome, toolName, false,
                decision.getMessage(), steps, System.currentTimeMillis() - startTime,
                httpAuditSubject.currentOperatorId(), opsPathPolicy.getPolicyVersion(),
                Map.of(),
                Map.of(
                        "auditKind", "block",
                        "requestChannel", "mcp",
                        "stage", "gate",
                        "decision", outcome,
                        "targetType", "tool",
                        "targetName", toolName));
    }

    public void persistGateReject(String traceId, String userInstruction, String toolName,
                                  GateDecision decision, long startTime) {
        persistGateReject(traceId, userInstruction, toolName, decision, startTime, Map.of());
    }

    public void persistNeedConfirm(String traceId, String userInstruction, String toolName,
                                   GateDecision decision, long startTime, Map<String, Object> parameters,
                                   String confirmationId) {
        double score = decision.getAgenticRiskScore() != null ? decision.getAgenticRiskScore() : 0.0;
        String step4 = String.format(Locale.ROOT,
                "风险评分 %.1f/10，需要在前端二次确认后执行。%s",
                score, decision.getMessage() != null ? decision.getMessage() : "");
        List<Map<String, Object>> steps = buildCotTrace(userInstruction, toolName, decision, step4);
        opsAuditTraceService.save(traceId, "MCP", userInstruction, decision.getRiskLevel().name(), "NEED_CONFIRM",
                toolName, false, decision.getMessage(), steps, System.currentTimeMillis() - startTime,
                httpAuditSubject.currentOperatorId(), opsPathPolicy.getPolicyVersion(),
                Map.of(),
                Map.of(
                        "auditKind", "confirm",
                        "requestChannel", "mcp",
                        "stage", "gate",
                        "decision", "NEED_CONFIRM",
                        "targetType", "tool",
                        "targetName", toolName,
                        "confirmationId", confirmationId));
    }

    public void persistNeedConfirm(String traceId, String userInstruction, String toolName,
                                   GateDecision decision, long startTime) {
        persistNeedConfirm(traceId, userInstruction, toolName, decision, startTime, Map.of(), traceId);
    }

    public void persistSuccess(String traceId, String userInstruction, GateDecision decision, String toolName,
                               Map<String, Object> execResponse, long startTime, Map<String, Object> parameters) {
        boolean ok = Boolean.TRUE.equals(execResponse.get("success"));
        String status = String.valueOf(execResponse.getOrDefault("status", ok ? "SUCCESS" : "ERROR")).trim().toUpperCase(Locale.ROOT);
        boolean warn = ok && "WARN".equals(status);
        String summary = ok ? String.valueOf(execResponse.get("data")) : String.valueOf(execResponse.get("error"));
        String rs = McpSecurityService.formatRiskScoreLine(decision);
        String resultBrief = summary.length() > 600 ? summary.substring(0, 600) + "..." : summary;
        String outcomeLabel = !ok ? "失败" : (warn ? "WARN" : "成功");
        String step4 = String.format(Locale.ROOT,
                "%s 已执行 %s。结果 %s。摘要 %s",
                rs, toolName, outcomeLabel, resultBrief);
        String securityOutcome = !ok ? "EXECUTION_FAILED" : (warn ? "WARN" : "PASS");
        String decisionCode = !ok ? "EXECUTION_FAILED" : (warn ? "WARN" : "PASS");
        List<Map<String, Object>> steps = buildCotTrace(userInstruction, toolName, decision, step4);
        opsAuditTraceService.save(traceId, "MCP", userInstruction, decision.getRiskLevel().name(), securityOutcome, toolName, ok && !warn,
                summary, steps, System.currentTimeMillis() - startTime,
                httpAuditSubject.currentOperatorId(), opsPathPolicy.getPolicyVersion(),
                Map.of(),
                Map.of(
                        "auditKind", "tool",
                        "requestChannel", "mcp",
                        "stage", "execute",
                        "decision", decisionCode,
                        "status", status,
                        "targetType", "tool",
                        "targetName", toolName));
    }

    public void persistSuccess(String traceId, String userInstruction, GateDecision decision, String toolName,
                               Map<String, Object> execResponse, long startTime) {
        persistSuccess(traceId, userInstruction, decision, toolName, execResponse, startTime, Map.of());
    }

    private List<Map<String, Object>> buildCotTrace(String userInstruction, String toolName,
                                                    GateDecision decision, String step4) {
        String s3 = String.format("AI 推理：根据用户指令，将调用工具 %s 完成目标。", toolName);
        if (decision != null && decision.getAgenticRiskDimensions() != null
                && !decision.getAgenticRiskDimensions().isEmpty()) {
            s3 = s3 + " 评分细节: " + decision.getAgenticRiskDimensions();
        }
        List<Map<String, Object>> steps = new ArrayList<>();
        steps.add(TraceService.cotStep(1, "接收", "接收并解析为: " + userInstruction));
        steps.add(TraceService.cotStep(2, "感知",
                "复用当前请求上下文生成感知摘要，不在审计阶段重复触发主机探测。tool=" + toolName));
        steps.add(TraceService.cotStep(3, "推理", s3));
        steps.add(TraceService.cotStep(4, "校验", step4));
        return steps;
    }
}
