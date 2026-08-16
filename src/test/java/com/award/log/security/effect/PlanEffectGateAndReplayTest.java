package com.award.log.security.effect;

import com.award.log.governance.OpsGovernanceProperties;
import com.award.log.governance.OpsGovernanceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanEffectGateAndReplayTest {

    private PlanEffectGate planEffectGate;
    private OpsGovernanceService governance;

    @BeforeEach
    void setUp() {
        governance = new OpsGovernanceService(new OpsGovernanceProperties());
        planEffectGate = new PlanEffectGate(new ToolEffectResolver(), governance);
    }

    @Test
    void planBlocksForbiddenAutoTarget() {
        PlanEffectGate.PlanDecision d = planEffectGate.evaluate(List.of(
                new PlanEffectGate.PlannedCall("CleanTempTool", Map.of("path", "/etc/passwd"))
        ));
        assertEquals(PlanEffectGate.DecisionType.BLOCK, d.type());
        assertEquals("PLAN_GOVERNANCE_FORBIDDEN", d.code());
    }

    @Test
    void planNeedsConfirmOnSensitiveObserveThenWrite() {
        PlanEffectGate.PlanDecision d = planEffectGate.evaluate(List.of(
                new PlanEffectGate.PlannedCall("DiskTool", Map.of("path", "/etc")),
                new PlanEffectGate.PlannedCall("CleanTempTool", Map.of("path", "/tmp/cache", "dryRun", false, "confirmDelete", true))
        ));
        assertEquals(PlanEffectGate.DecisionType.NEED_CONFIRM, d.type());
        assertTrue(d.sensitiveObserveThenWrite());
    }

    @Test
    void governanceHardBlocksSshdRestartOnToolCall() {
        var eval = governance.evaluateToolCall("ServiceRestartTool", Map.of("serviceName", "sshd", "dryRun", false));
        assertEquals("FORBIDDEN", eval.verdict().name());
    }

    @Test
    void tempCleanupOnTmpRequiresConfirm() {
        var eval = governance.evaluateToolCall("CleanTempTool", Map.of("path", "/tmp/x", "dryRun", true));
        assertEquals("CONFIRM_ONLY", eval.verdict().name());
    }
}
