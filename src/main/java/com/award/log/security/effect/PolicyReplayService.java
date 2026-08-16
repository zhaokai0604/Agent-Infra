package com.award.log.security.effect;

import com.award.log.governance.OpsGovernanceService;
import com.award.log.mcp.WriteToolResultSupport;
import com.award.log.security.AgenticRiskScoreEngine;
import com.award.log.security.McpInvocationSecurityGate;
import com.award.log.security.McpInvocationSecurityGate.GateDecision;
import com.award.log.security.McpInvocationSecurityGate.McpSecurityProfile;
import com.award.log.security.OpsPathPolicy;
import com.award.log.service.OpsAuditTraceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 策略可回放：对工具调用 / 计划 / 历史审计快照重新裁决，并对比策略版本差异。
 */
@Service
@RequiredArgsConstructor
public class PolicyReplayService {

    private final McpInvocationSecurityGate securityGate;
    private final ToolEffectResolver toolEffectResolver;
    private final PlanEffectGate planEffectGate;
    private final OpsGovernanceService opsGovernanceService;
    private final AgenticRiskScoreEngine riskScoreEngine;
    private final OpsPathPolicy opsPathPolicy;
    private final OpsAuditTraceService opsAuditTraceService;

    public Map<String, Object> replayTool(
            String toolName,
            Map<String, Object> parameters,
            String userMessage,
            String profileName) {
        McpSecurityProfile profile = parseProfile(profileName);
        Map<String, Object> params = parameters == null ? Map.of() : parameters;
        GateDecision decision = securityGate.evaluate(toolName, params, userMessage, profile);
        ToolEffect effect = toolEffectResolver.resolve(toolName, params);
        String fingerprint = EffectFingerprint.of(toolName, params, effect);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", true);
        out.put("mode", "TOOL");
        out.put("policySnapshot", currentPolicySnapshot());
        out.put("toolName", toolName);
        out.put("parameters", params);
        out.put("profile", profile.name());
        out.put("decision", decisionToMap(decision));
        out.put("toolEffect", effect.toMap());
        out.put("effectFingerprint", fingerprint);
        if (opsGovernanceService != null) {
            var gov = opsGovernanceService.evaluateToolCall(toolName, params);
            Map<String, Object> govMap = new LinkedHashMap<>();
            govMap.put("verdict", gov.verdict().name());
            govMap.put("assetTier", gov.assetTier().name());
            govMap.put("target", gov.target());
            govMap.put("reason", gov.reason());
            out.put("governance", govMap);
        }
        out.put("requestedRealWrite", WriteToolResultSupport.requestedRealWrite(params));
        return out;
    }

    public Map<String, Object> replayPlan(List<Map<String, Object>> steps) {
        List<PlanEffectGate.PlannedCall> calls = new ArrayList<>();
        if (steps != null) {
            for (Map<String, Object> step : steps) {
                if (step == null) {
                    continue;
                }
                String toolName = stringVal(step.get("toolName"));
                if (toolName.isBlank()) {
                    toolName = stringVal(step.get("tool"));
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> params = step.get("parameters") instanceof Map<?, ?> m
                        ? new LinkedHashMap<>((Map<String, Object>) m)
                        : new LinkedHashMap<>();
                if (!toolName.isBlank()) {
                    calls.add(new PlanEffectGate.PlannedCall(toolName, params));
                }
            }
        }
        PlanEffectGate.PlanDecision planDecision = planEffectGate.evaluate(calls);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", true);
        out.put("mode", "PLAN");
        out.put("policySnapshot", currentPolicySnapshot());
        out.put("stepCount", calls.size());
        out.put("planDecision", planDecision.toMap());
        return out;
    }

    /**
     * 对比：当前策略 vs 关闭治理硬覆盖后的评分-only 倾向（用于实验/答辩）。
     */
    public Map<String, Object> compareTool(
            String toolName,
            Map<String, Object> parameters,
            String userMessage,
            String profileName) {
        Map<String, Object> current = replayTool(toolName, parameters, userMessage, profileName);
        Map<String, Object> params = parameters == null ? Map.of() : parameters;
        McpSecurityProfile profile = parseProfile(profileName);

        // 评分-only：复用门控但标注治理是否会改变结论
        GateDecision scored = securityGate.evaluate(toolName, params, userMessage, profile);
        var gov = opsGovernanceService != null
                ? opsGovernanceService.evaluateToolCall(toolName, params)
                : null;

        String scoreOnlyTendency;
        if (scored.getType() == GateDecision.Type.BLOCK
                && !"GOVERNANCE_FORBIDDEN".equals(scored.getCode())) {
            scoreOnlyTendency = "BLOCK";
        } else if (scored.getType() == GateDecision.Type.NEED_CONFIRM
                && scored.getMessage() != null
                && scored.getMessage().contains("治理要求人工确认")) {
            scoreOnlyTendency = "ALLOW_OR_SCORE_CONFIRM";
        } else {
            scoreOnlyTendency = scored.getType().name();
        }

        Map<String, Object> delta = new LinkedHashMap<>();
        delta.put("currentDecision", ((Map<?, ?>) current.get("decision")).get("type"));
        delta.put("scorePathTendency", scoreOnlyTendency);
        delta.put("governanceChangedOutcome",
                gov != null && (
                        "GOVERNANCE_FORBIDDEN".equals(scored.getCode())
                                || (scored.getMessage() != null && scored.getMessage().contains("治理要求人工确认"))
                ));
        if (gov != null) {
            delta.put("governanceVerdict", gov.verdict().name());
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", true);
        out.put("mode", "COMPARE");
        out.put("policySnapshot", currentPolicySnapshot());
        out.put("current", current);
        out.put("delta", delta);
        return out;
    }

    public Map<String, Object> replayAudit(String traceId) {
        Map<String, Object> audit = opsAuditTraceService.findByTraceId(traceId);
        if (audit == null || audit.isEmpty()) {
            return Map.of("success", false, "error", "未找到审计记录: " + traceId);
        }
        String toolName = stringVal(audit.get("toolName"));
        String userInput = stringVal(audit.get("userInput"));
        String storedOutcome = stringVal(audit.get("securityOutcome"));
        String storedPolicy = stringVal(audit.get("policyVersion"));

        Map<String, Object> params = Map.of();
        Object steps = audit.get("steps");
        if (steps instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map<?, ?> first) {
            Object p = first.get("parameters");
            if (p instanceof Map<?, ?> pm) {
                @SuppressWarnings("unchecked")
                Map<String, Object> cast = (Map<String, Object>) pm;
                params = cast;
            }
            if (toolName.isBlank()) {
                toolName = stringVal(first.get("toolName"));
            }
        }

        Map<String, Object> fresh = toolName.isBlank()
                ? Map.of("success", false, "error", "审计缺少 toolName，无法回放")
                : replayTool(toolName, params, userInput, "INITIAL_REQUEST");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", true);
        out.put("mode", "AUDIT_REPLAY");
        out.put("traceId", traceId);
        out.put("storedOutcome", storedOutcome);
        out.put("storedPolicyVersion", storedPolicy);
        out.put("fresh", fresh);
        Object freshType = fresh.get("decision") instanceof Map<?, ?> d ? d.get("type") : null;
        out.put("outcomeChanged", freshType != null
                && !normalizeOutcome(storedOutcome).equalsIgnoreCase(String.valueOf(freshType)));
        return out;
    }

    public Map<String, Object> currentPolicySnapshot() {
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("pathPolicyVersion", opsPathPolicy != null ? opsPathPolicy.getPolicyVersion() : "unknown");
        snap.put("riskScoreAutoMax", riskScoreEngine.getAutoMax());
        snap.put("riskScoreConfirmMax", riskScoreEngine.getConfirmMax());
        snap.put("governanceEnabled", opsGovernanceService != null && opsGovernanceService.isEnabled());
        if (opsGovernanceService != null) {
            snap.put("governanceProfile", opsGovernanceService.summaryForPlatform().get("deliveryProfile"));
        }
        snap.put("effectEngine", "ToolEffect+PlanEffect+CapabilityToken+EvidenceContract");
        return snap;
    }

    private static Map<String, Object> decisionToMap(GateDecision decision) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", decision.getType().name());
        m.put("code", decision.getCode());
        m.put("message", decision.getMessage());
        m.put("riskLevel", decision.getRiskLevel() != null ? decision.getRiskLevel().name() : null);
        m.put("riskScore", decision.getAgenticRiskScore());
        m.put("riskDimensions", decision.getAgenticRiskDimensions());
        m.put("riskExplanation", decision.getAgenticRiskExplanation());
        return m;
    }

    private static McpSecurityProfile parseProfile(String profileName) {
        if (profileName == null || profileName.isBlank()) {
            return McpSecurityProfile.INITIAL_REQUEST;
        }
        String p = profileName.trim().toUpperCase(Locale.ROOT);
        try {
            return McpSecurityProfile.valueOf(p);
        } catch (Exception e) {
            if (p.contains("POST")) {
                return McpSecurityProfile.POST_CONFIRMATION;
            }
            if (p.contains("DEFER")) {
                return McpSecurityProfile.DEFERRED_SCHEDULED;
            }
            if (p.contains("CHAT")) {
                return McpSecurityProfile.CHAT_AGENT_TOOL;
            }
            return McpSecurityProfile.INITIAL_REQUEST;
        }
    }

    private static String stringVal(Object v) {
        return v == null ? "" : String.valueOf(v).trim();
    }

    private static String normalizeOutcome(String stored) {
        if (stored == null) {
            return "";
        }
        String s = stored.trim().toUpperCase(Locale.ROOT);
        if (s.contains("NEED") || s.contains("CONFIRM")) {
            return "NEED_CONFIRM";
        }
        if (s.contains("BLOCK") || s.contains("REJECT") || s.contains("DENY") || s.contains("FORBIDDEN")) {
            return "BLOCK";
        }
        if (s.contains("ALLOW") || s.contains("SUCCESS") || s.contains("OK")) {
            return "ALLOW";
        }
        return s;
    }
}
