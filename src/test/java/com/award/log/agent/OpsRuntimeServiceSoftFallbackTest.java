package com.award.log.agent;

import com.award.log.security.McpToolSurface;
import com.award.log.security.RiskLevel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OpsRuntimeServiceSoftFallbackTest {

    @Mock
    private OpsIntentRouter opsIntentRouter;
    @Mock
    private AssistantOrchestrator assistantOrchestrator;
    @Mock
    private OpsPatrolAutomationService patrolAutomationService;
    @Mock
    private ObjectProvider<AutonomousOpsOrchestrator> autonomousOpsOrchestrator;

    @Test
    void nonePlaybookReturnsSoftFallbackInsteadOfDeadEndCopy() {
        when(opsIntentRouter.resolve("随便问问")).thenReturn(OpsIntentRouter.Playbook.NONE);
        OpsRuntimeService runtime = new OpsRuntimeService(
                opsIntentRouter, assistantOrchestrator, patrolAutomationService, autonomousOpsOrchestrator);

        OpsRunResult result = runtime.run("随便问问", McpToolSurface.FULL, RiskLevel.LOW);

        assertTrue(result.softFallback());
        assertEquals(true, result.report().get("softFallback"));
        assertTrue(result.markdown() == null || result.markdown().isBlank());
    }

    @Test
    void runPlaybookUsesProvidedPlaybook() {
        when(assistantOrchestrator.run("磁盘满了", McpToolSurface.FULL, RiskLevel.LOW))
                .thenReturn(new AssistantOrchestrator.RunResult("## ok", "t1", Map.of("securityOutcome", "PREVIEW")));
        OpsRuntimeService runtime = new OpsRuntimeService(
                opsIntentRouter, assistantOrchestrator, patrolAutomationService, autonomousOpsOrchestrator);

        OpsRunResult result = runtime.runPlaybook(
                OpsIntentRouter.Playbook.DISK_CLEANUP, "磁盘满了", McpToolSurface.FULL, RiskLevel.LOW);

        assertEquals("## ok", result.markdown());
        assertEquals("t1", result.traceId());
    }
}
