package com.award.log.agent.awm;

import com.award.log.service.OpsAuditTraceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OpsExperienceLoaderTest {

    @Mock
    private OpsAuditTraceService opsAuditTraceService;

    @Test
    void loadRecentSuccessfulUsesStructuredStepsPayload() {
        OpsExperienceLoader loader = new OpsExperienceLoader(opsAuditTraceService);
        when(opsAuditTraceService.listRecentWithSteps(6)).thenReturn(List.of(
                Map.of(
                        "traceId", "t-ok",
                        "userInput", "清理磁盘",
                        "channel", "ASSISTANT",
                        "toolName", "AssistantOrchestrator",
                        "securityOutcome", "EXECUTED",
                        "executionOk", true,
                        "resultSummary", "ok",
                        "durationMs", 120L,
                        "stepsJsonRaw", "[{\"phase\":\"execute\",\"toolName\":\"LogCleanupTool\"}]"
                ),
                Map.of(
                        "traceId", "t-skip",
                        "executionOk", false,
                        "stepsJsonRaw", "[]"
                )
        ));

        List<OpsExperience> result = loader.loadRecentSuccessful(2);

        assertEquals(1, result.size());
        assertEquals("t-ok", result.get(0).traceId());
        assertEquals(1, result.get(0).steps().size());
        assertEquals("LogCleanupTool", result.get(0).steps().get(0).get("toolName"));
    }

    @Test
    void loadByTraceIdParsesEmbeddedStepList() {
        OpsExperienceLoader loader = new OpsExperienceLoader(opsAuditTraceService);
        when(opsAuditTraceService.findByTraceId("trace-1")).thenReturn(Map.of(
                "traceId", "trace-1",
                "userInput", "重启 nginx",
                "channel", "ASSISTANT",
                "toolName", "CpuPressure",
                "securityOutcome", "EXECUTED",
                "executionOk", true,
                "resultSummary", "done",
                "durationMs", 80L,
                "steps", List.of(Map.of("phase", "execute", "toolName", "ServiceRestartTool"))
        ));

        OpsExperience result = loader.loadByTraceId("trace-1");

        assertNotNull(result);
        assertEquals("trace-1", result.traceId());
        assertTrue(result.executionOk());
        assertEquals("ServiceRestartTool", result.steps().get(0).get("toolName"));
    }
}
