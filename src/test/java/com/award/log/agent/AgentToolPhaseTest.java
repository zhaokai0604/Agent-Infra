package com.award.log.agent;

import com.award.log.security.McpToolSurface;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentToolPhaseTest {

    @Test
    void unconfirmedForcesReadOnlySurface() {
        assertEquals(McpToolSurface.READ_ONLY,
                AgentToolPhase.effectiveSurface(McpToolSurface.FULL, false));
        assertEquals(McpToolSurface.FULL,
                AgentToolPhase.effectiveSurface(McpToolSurface.FULL, true));
        assertEquals(McpToolSurface.READ_ONLY,
                AgentToolPhase.effectiveSurface(McpToolSurface.READ_ONLY, true));
    }

    @Test
    void diagnoseDeniesHybridWriteBeans() {
        assertTrue(AgentToolPhase.denyBeanInDiagnosePhase("DockerTool"));
        assertTrue(AgentToolPhase.denyBeanInDiagnosePhase("SystemdTool"));
        assertFalse(AgentToolPhase.denyBeanInDiagnosePhase("DiskTool"));
        assertFalse(AgentToolPhase.denyBeanInDiagnosePhase("ProcessTool"));
    }

    @Test
    void writeMountedOnlyWhenAllowAndNotReadOnly() {
        assertFalse(AgentToolPhase.writeToolsMounted(false, McpToolSurface.READ_ONLY));
        assertFalse(AgentToolPhase.writeToolsMounted(true, McpToolSurface.READ_ONLY));
        assertTrue(AgentToolPhase.writeToolsMounted(true, McpToolSurface.FULL));
    }

    @Test
    void observeAndPendingWriteSplit() {
        List<String> planned = List.of("DiskTool", "CleanTempTool", "SystemLoadTool");
        assertEquals(List.of("DiskTool", "SystemLoadTool"), AgentSkillPlan.observeTools(planned));
        assertEquals(List.of("CleanTempTool"), AgentSkillPlan.pendingWriteTools(planned));
    }

    @Test
    void diagnosePhaseRulesSayToolsNotMounted() {
        String rules = AssistantReplyPrompts.toolAgentPhaseRules(
                false, List.of("CleanTempTool"), false);
        assertTrue(rules.contains("未挂载") || rules.contains("写工具未挂载"));
    }
}
