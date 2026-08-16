package com.award.log.agent;

import com.award.log.security.HttpAuditSubject;
import com.award.log.security.OpsPathPolicy;
import com.award.log.service.OpsAuditTraceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AssistantAuditRecorderTest {

    @Mock
    private OpsAuditTraceService opsAuditTraceService;

    @Mock
    private OpsPathPolicy opsPathPolicy;

    @Mock
    private HttpAuditSubject httpAuditSubject;

    @Test
    void addStructuredStepFlattensDetailIntoAuditRow() {
        AssistantAuditRecorder recorder = new AssistantAuditRecorder(
                opsAuditTraceService, opsPathPolicy, httpAuditSubject);
        List<Map<String, Object>> steps = new ArrayList<>();

        recorder.addStructuredStep(steps, "execute", Map.of(
                "toolName", "LogCleanupTool",
                "mode", "PREVIEW"
        ));

        assertEquals(1, steps.size());
        assertEquals("execute", steps.get(0).get("phase"));
        assertEquals("LogCleanupTool", steps.get(0).get("toolName"));
        assertEquals("PREVIEW", steps.get(0).get("mode"));
    }

    @Test
    void recordDelegatesOperatorAndPolicyVersion() {
        AssistantAuditRecorder recorder = new AssistantAuditRecorder(
                opsAuditTraceService, opsPathPolicy, httpAuditSubject);
        when(httpAuditSubject.currentOperatorId()).thenReturn("u-1");
        when(opsPathPolicy.getPolicyVersion()).thenReturn("policy-v1");

        recorder.record(
                "trace-1",
                "清理磁盘",
                "HIGH",
                "EXECUTED",
                "AssistantOrchestrator",
                true,
                "done",
                List.of(Map.of("phase", "execute")),
                88L
        );

        ArgumentCaptor<List<Map<String, Object>>> stepsCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<Map<String, Object>> auditMetaCaptor = ArgumentCaptor.forClass(Map.class);
        verify(opsAuditTraceService).save(
                org.mockito.ArgumentMatchers.eq("trace-1"),
                org.mockito.ArgumentMatchers.eq("ASSISTANT"),
                org.mockito.ArgumentMatchers.eq("清理磁盘"),
                org.mockito.ArgumentMatchers.eq("HIGH"),
                org.mockito.ArgumentMatchers.eq("EXECUTED"),
                org.mockito.ArgumentMatchers.eq("AssistantOrchestrator"),
                org.mockito.ArgumentMatchers.eq(true),
                org.mockito.ArgumentMatchers.eq("done"),
                stepsCaptor.capture(),
                org.mockito.ArgumentMatchers.eq(88L),
                org.mockito.ArgumentMatchers.eq("u-1"),
                org.mockito.ArgumentMatchers.eq("policy-v1"),
                org.mockito.ArgumentMatchers.eq(Map.of()),
                auditMetaCaptor.capture());

        assertTrue(stepsCaptor.getValue().stream().anyMatch(step -> "execute".equals(step.get("phase"))));
        assertEquals("dialogue", auditMetaCaptor.getValue().get("auditKind"));
        assertEquals("assistant", auditMetaCaptor.getValue().get("requestChannel"));
        assertEquals("complete", auditMetaCaptor.getValue().get("stage"));
        assertEquals("EXECUTED", auditMetaCaptor.getValue().get("decision"));
        assertEquals("tool", auditMetaCaptor.getValue().get("targetType"));
        assertEquals("AssistantOrchestrator", auditMetaCaptor.getValue().get("targetName"));
        assertNull(auditMetaCaptor.getValue().get("parentTraceId"));
    }
}
