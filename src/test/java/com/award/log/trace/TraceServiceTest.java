package com.award.log.trace;

import com.award.log.model.TraceLog;
import com.award.log.service.OpsAuditTraceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class TraceServiceTest {

    @Mock
    private OpsAuditTraceService opsAuditTraceService;

    private TraceService traceService;

    @BeforeEach
    void setUp() {
        traceService = new TraceService(opsAuditTraceService);
    }

    @Test
    void recordFullPersistsStepsAndMetadata() {
        List<Map<String, Object>> steps = List.of(TraceService.cotStep(1, "接收", "parsed"));

        traceService.recordFull(
                "trace-1", "MCP", "check disk", "LOW", "PASS",
                "DiskTool", true, "ok", steps, 120L, "42", "policy-v2");

        verify(opsAuditTraceService).save(
                eq("trace-1"), eq("MCP"), eq("check disk"), eq("LOW"), eq("PASS"),
                eq("DiskTool"), eq(true), eq("ok"), eq(steps), eq(120L), eq("42"), eq("policy-v2"));
    }

    @Test
    void recordFullUsesEmptyStepsWhenNull() {
        traceService.recordFull(
                "trace-2", "ASSISTANT", "hello", null, "NEED_CONFIRM",
                "CleanTempTool", false, "preview", null, 50L, null, null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, Object>>> stepsCaptor = ArgumentCaptor.forClass(List.class);
        verify(opsAuditTraceService).save(
                eq("trace-2"), anyString(), anyString(), isNull(), anyString(),
                anyString(), eq(false), anyString(), stepsCaptor.capture(),
                anyLong(), isNull(), isNull());
        assertTrue(stepsCaptor.getValue().isEmpty());
    }

    @Test
    void recordLegacyTraceLogBuildsCotStep() {
        TraceLog log = TraceLog.builder()
                .traceId("legacy-1")
                .userInput("user cmd")
                .riskLevel("MEDIUM")
                .toolName("LogCleanupTool")
                .resultSummary("done")
                .durationMs(80L)
                .build();

        traceService.record(log);

        verify(opsAuditTraceService).save(
                eq("legacy-1"), eq("CHAT"), eq("user cmd"), eq("MEDIUM"), eq("PASS"),
                eq("LogCleanupTool"), eq(true), eq("done"), anyList(), eq(80L), isNull(), isNull());
    }

    @Test
    void staticStepHelpersBuildExpectedShape() {
        Map<String, Object> step = TraceService.step("perceive", "disk snapshot");
        assertEquals("perceive", step.get("phase"));
        assertEquals("disk snapshot", step.get("detail"));

        Map<String, Object> cot = TraceService.cotStep(2, "感知", "loaded metrics");
        assertEquals("cot", cot.get("phase"));
        assertEquals(2, cot.get("step"));
        assertTrue(String.valueOf(cot.get("detail")).contains("感知"));
    }
}
