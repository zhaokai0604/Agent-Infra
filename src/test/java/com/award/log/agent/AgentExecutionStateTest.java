package com.award.log.agent;

import com.award.log.agent.awm.OpsWorkflow;
import com.award.log.agent.awm.OpsWorkflowStep;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AgentExecutionStateTest {

    @Test
    void planStateShouldMarkWritePendingAsPlanPhase() {
        Map<String, Object> state = AgentExecutionState.build(
                "TOOL_AGENT",
                false,
                false,
                List.of("DiskTool", "LogCleanupTool"),
                "DIAGNOSE_THEN_CONFIRM",
                null,
                null,
                workflow()
        );

        assertEquals("plan", state.get("phase"));
        assertEquals(Boolean.TRUE, state.get("awaitingConfirm"));
        assertTrue(((List<?>) state.get("pendingWriteTools")).contains("LogCleanupTool"));
        assertNotNull(state.get("phaseTrail"));
    }

    @Test
    void executedStateShouldPromoteToVerifyPhase() {
        Map<String, Object> state = AgentExecutionState.build(
                "ORCHESTRATE",
                true,
                true,
                List.of("SystemLoadTool", "DiskTool"),
                "EXECUTE",
                "trace-12345678",
                "EXECUTED",
                workflow()
        );

        assertEquals("verify", state.get("phase"));
        assertEquals("已执行验证", state.get("phaseLabel"));
        assertEquals("EXECUTED", state.get("securityOutcome"));
        assertEquals("trace-12345678", state.get("traceId"));
        assertEquals("demo-workflow", state.get("awmWorkflowId"));
    }

    @Test
    void failureStateShouldPromoteToReflectPhase() {
        Map<String, Object> state = AgentExecutionState.build(
                "TOOL_AGENT",
                false,
                false,
                List.of("DiskTool"),
                "DIAGNOSE",
                "trace-fail",
                "ERROR",
                workflow()
        );

        assertEquals("reflect", state.get("phase"));
        assertTrue(String.valueOf(state.get("reflection")).contains("失败"));
        assertTrue(String.valueOf(state.get("nextAction")).contains("Reflexion"));
    }

    private static OpsWorkflow workflow() {
        return new OpsWorkflow(
                "demo-workflow",
                "disk",
                List.of("DISK_PRESSURE"),
                "Demo Workflow",
                "Demo workflow for agent state test",
                List.of(OpsWorkflowStep.of("collect", "collect metrics", "DiskTool", Map.of())),
                "seed",
                "trace-demo",
                0,
                true
        );
    }
}
