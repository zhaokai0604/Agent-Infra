package com.award.log.security.effect;

import com.award.log.governance.AssetTier;
import com.award.log.governance.GovernanceAdmissionVerdict;
import com.award.log.governance.OpsGovernanceService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 计划级效果图门控：对多步工具调用的效果闭包做组合裁决，避免“单步放行、组合危险”。
 */
@Component
public class PlanEffectGate {

    private final ToolEffectResolver toolEffectResolver;
    private final OpsGovernanceService opsGovernanceService;
    private final int maxPlanIrreversibility;
    private final int maxCoreRestarts;
    private final int maxWriteSteps;

    @Autowired
    public PlanEffectGate(
            ToolEffectResolver toolEffectResolver,
            OpsGovernanceService opsGovernanceService,
            @Value("${agent.security.plan.max-irreversibility:18}") int maxPlanIrreversibility,
            @Value("${agent.security.plan.max-core-restarts:1}") int maxCoreRestarts,
            @Value("${agent.security.plan.max-write-steps:6}") int maxWriteSteps) {
        this.toolEffectResolver = toolEffectResolver;
        this.opsGovernanceService = opsGovernanceService;
        this.maxPlanIrreversibility = Math.max(1, maxPlanIrreversibility);
        this.maxCoreRestarts = Math.max(0, maxCoreRestarts);
        this.maxWriteSteps = Math.max(1, maxWriteSteps);
    }

    /** 单测便捷构造（非 Spring 注入入口）。 */
    public PlanEffectGate(ToolEffectResolver toolEffectResolver, OpsGovernanceService opsGovernanceService) {
        this(toolEffectResolver, opsGovernanceService, 18, 1, 6);
    }

    public enum DecisionType {
        ALLOW,
        NEED_CONFIRM,
        BLOCK
    }

    public record PlannedCall(String toolName, Map<String, Object> parameters) {
    }

    public record PlanDecision(
            DecisionType type,
            String code,
            String message,
            int writeSteps,
            int totalIrreversibility,
            int coreRestarts,
            boolean sensitiveObserveThenWrite,
            List<Map<String, Object>> effectGraph
    ) {
        public boolean blocked() {
            return type == DecisionType.BLOCK;
        }

        public boolean needsConfirm() {
            return type == DecisionType.NEED_CONFIRM;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", type.name());
            m.put("code", code);
            m.put("message", message);
            m.put("writeSteps", writeSteps);
            m.put("totalIrreversibility", totalIrreversibility);
            m.put("coreRestarts", coreRestarts);
            m.put("sensitiveObserveThenWrite", sensitiveObserveThenWrite);
            m.put("effectGraph", effectGraph);
            return m;
        }
    }

    public PlanDecision evaluate(List<PlannedCall> calls) {
        if (calls == null || calls.isEmpty()) {
            return new PlanDecision(DecisionType.ALLOW, "EMPTY_PLAN", "空计划", 0, 0, 0, false, List.of());
        }

        List<Map<String, Object>> graph = new ArrayList<>();
        int writeSteps = 0;
        int totalIrreversibility = 0;
        int coreRestarts = 0;
        boolean sawSensitiveObserve = false;
        boolean sawWriteAfterSensitiveObserve = false;
        boolean anyGovernanceConfirm = false;
        String forbidReason = null;

        for (PlannedCall call : calls) {
            if (call == null || call.toolName() == null || call.toolName().isBlank()) {
                continue;
            }
            Map<String, Object> params = call.parameters() == null ? Map.of() : call.parameters();
            ToolEffect effect = toolEffectResolver.resolve(call.toolName(), params);
            OpsGovernanceService.GovernanceEvaluation gov = opsGovernanceService != null
                    ? opsGovernanceService.evaluateToolCall(call.toolName(), params)
                    : null;

            Map<String, Object> node = new LinkedHashMap<>(effect.toMap());
            node.put("toolName", call.toolName());
            if (gov != null) {
                node.put("governanceVerdict", gov.verdict().name());
                node.put("assetTier", gov.assetTier().name());
                node.put("governanceReason", gov.reason());
                if (gov.verdict() == GovernanceAdmissionVerdict.FORBIDDEN && forbidReason == null) {
                    forbidReason = gov.reason();
                }
                if (gov.verdict() == GovernanceAdmissionVerdict.CONFIRM_ONLY) {
                    anyGovernanceConfirm = true;
                }
            }
            graph.add(node);

            if (effect.action() == EffectAction.OBSERVE && looksSensitiveTarget(effect)) {
                sawSensitiveObserve = true;
            }
            if (effect.writeEffect()) {
                writeSteps++;
                totalIrreversibility += effect.irreversibility();
                if (sawSensitiveObserve) {
                    sawWriteAfterSensitiveObserve = true;
                }
                if (effect.action() == EffectAction.RESTART && gov != null && isCoreTier(gov.assetTier())) {
                    coreRestarts++;
                }
            }
        }

        if (forbidReason != null) {
            return new PlanDecision(DecisionType.BLOCK, "PLAN_GOVERNANCE_FORBIDDEN",
                    "计划含治理禁止步骤：" + forbidReason,
                    writeSteps, totalIrreversibility, coreRestarts, sawWriteAfterSensitiveObserve, graph);
        }
        if (writeSteps > maxWriteSteps) {
            return new PlanDecision(DecisionType.BLOCK, "PLAN_WRITE_STEPS_HIGH",
                    "计划写步骤过多：" + writeSteps + " > " + maxWriteSteps,
                    writeSteps, totalIrreversibility, coreRestarts, sawWriteAfterSensitiveObserve, graph);
        }
        if (totalIrreversibility > maxPlanIrreversibility) {
            return new PlanDecision(DecisionType.BLOCK, "PLAN_IRREVERSIBILITY_HIGH",
                    "计划累计不可逆分过高：" + totalIrreversibility + " > " + maxPlanIrreversibility,
                    writeSteps, totalIrreversibility, coreRestarts, sawWriteAfterSensitiveObserve, graph);
        }
        if (coreRestarts > maxCoreRestarts) {
            return new PlanDecision(DecisionType.BLOCK, "PLAN_CORE_RESTART_FANOUT",
                    "计划触及过多核心服务重启：" + coreRestarts + " > " + maxCoreRestarts,
                    writeSteps, totalIrreversibility, coreRestarts, sawWriteAfterSensitiveObserve, graph);
        }
        if (sawWriteAfterSensitiveObserve) {
            return new PlanDecision(DecisionType.NEED_CONFIRM, "PLAN_SENSITIVE_OBSERVE_THEN_WRITE",
                    "计划先观测敏感目标后执行写操作，须人工确认组合风险",
                    writeSteps, totalIrreversibility, coreRestarts, true, graph);
        }
        if (writeSteps >= 2 && totalIrreversibility >= Math.max(8, maxPlanIrreversibility / 2)) {
            return new PlanDecision(DecisionType.NEED_CONFIRM, "PLAN_COMPOSITE_WRITE",
                    "计划包含多步写操作且累计不可逆分较高，须人工确认",
                    writeSteps, totalIrreversibility, coreRestarts, false, graph);
        }
        if (anyGovernanceConfirm && writeSteps > 0) {
            return new PlanDecision(DecisionType.NEED_CONFIRM, "PLAN_GOVERNANCE_CONFIRM",
                    "计划含须确认的治理写动作",
                    writeSteps, totalIrreversibility, coreRestarts, false, graph);
        }
        return new PlanDecision(DecisionType.ALLOW, "PLAN_OK", "计划效果闭包可放行",
                writeSteps, totalIrreversibility, coreRestarts, false, graph);
    }

    private static boolean isCoreTier(AssetTier tier) {
        return tier == AssetTier.CORE_STATELESS || tier == AssetTier.CORE_STATEFUL || tier == AssetTier.FORBIDDEN_AUTO;
    }

    private static boolean looksSensitiveTarget(ToolEffect effect) {
        if (effect == null) {
            return false;
        }
        String id = effect.targetId() == null ? "" : effect.targetId().toLowerCase(Locale.ROOT).replace('\\', '/');
        return id.contains("/etc")
                || id.contains("/.ssh")
                || id.contains("c:/windows")
                || id.contains("system32")
                || id.contains("/boot")
                || id.contains("shadow");
    }
}
