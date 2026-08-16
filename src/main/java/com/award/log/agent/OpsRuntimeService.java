package com.award.log.agent;

import com.award.log.security.McpToolSurface;
import com.award.log.security.RiskLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Runtime router for patrol automation and playbook orchestration.
 */
@Service
@RequiredArgsConstructor
public class OpsRuntimeService {

    private final OpsIntentRouter opsIntentRouter;
    private final AssistantOrchestrator assistantOrchestrator;
    private final OpsPatrolAutomationService patrolAutomationService;
    private final ObjectProvider<AutonomousOpsOrchestrator> autonomousOpsOrchestrator;

    @Value("${agent.runtime.enabled:true}")
    private boolean enabled;

    public boolean shouldOrchestrate(String userMessage) {
        return enabled && opsIntentRouter.shouldOrchestrate(userMessage);
    }

    public boolean shouldOrchestrateFromContext(
            String userMessage,
            java.util.List<com.award.log.service.impl.UnifiedAssistantService.ChatTurn> history) {
        return enabled && opsIntentRouter.shouldOrchestrateFromContext(userMessage, history);
    }

    public OpsRunResult run(String userMessage, McpToolSurface surface, RiskLevel intentRisk) {
        OpsIntentRouter.Playbook playbook = opsIntentRouter.resolve(userMessage);
        return runResolved(userMessage, surface, intentRisk, playbook);
    }

    public OpsRunResult runFromContext(
            String userMessage,
            java.util.List<com.award.log.service.impl.UnifiedAssistantService.ChatTurn> history,
            McpToolSurface surface,
            RiskLevel intentRisk) {
        OpsIntentRouter.Playbook playbook = opsIntentRouter.resolveFromContext(userMessage, history);
        return runResolved(userMessage, surface, intentRisk, playbook);
    }

    /** 使用已解析的剧本，避免二次分类导致软落空窗。 */
    public OpsRunResult runPlaybook(
            OpsIntentRouter.Playbook playbook,
            String userMessage,
            McpToolSurface surface,
            RiskLevel intentRisk) {
        return runResolved(
                userMessage,
                surface,
                intentRisk,
                playbook == null ? OpsIntentRouter.Playbook.NONE : playbook);
    }

    private OpsRunResult runResolved(
            String userMessage,
            McpToolSurface surface,
            RiskLevel intentRisk,
            OpsIntentRouter.Playbook playbook) {
        return switch (playbook) {
            case PATROL_AUTOMATION -> patrolAutomationService.run();
            case DISK_CLEANUP, CPU_PRESSURE, PATROL_CONTINUATION -> {
                AssistantOrchestrator.RunResult result = assistantOrchestrator.run(userMessage, surface, intentRisk);
                yield new OpsRunResult(result.markdown(), result.traceId(), result.streamMeta());
            }
            case NONE -> new OpsRunResult(
                    "",
                    null,
                    Map.of(
                            "softFallback", true,
                            "softFallbackReason", "PLAYBOOK_NONE",
                            "hintZh", "未匹配固定运维剧本，改走工具增强诊断"));
        };
    }

    public OpsRunResult runScheduledAutonomous(McpToolSurface surface, boolean forceRemediate) {
        AutonomousOpsOrchestrator orchestrator = autonomousOpsOrchestrator.getIfAvailable();
        if (orchestrator != null && forceRemediate) {
            AutonomousOpsOrchestrator.RunResult result = orchestrator.runScheduled(surface, true);
            return new OpsRunResult(result.markdown(), result.traceId(), result.report());
        }
        return patrolAutomationService.run();
    }
}
