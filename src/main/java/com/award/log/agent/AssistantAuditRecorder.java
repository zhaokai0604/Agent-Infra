package com.award.log.agent;

import com.award.log.agent.audit.OpsAuditPhases;
import com.award.log.security.HttpAuditSubject;
import com.award.log.security.OpsPathPolicy;
import com.award.log.service.OpsAuditTraceService;
import com.award.log.trace.TraceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 助手 / 编排器统一审计落库（交班追溯，非演示专用）。
 */
@Component
@RequiredArgsConstructor
public class AssistantAuditRecorder {

    private final OpsAuditTraceService opsAuditTraceService;
    private final OpsPathPolicy opsPathPolicy;
    private final HttpAuditSubject httpAuditSubject;

    public void record(
            String traceId,
            String userInput,
            String riskLevel,
            String securityOutcome,
            String toolName,
            boolean executionOk,
            String resultSummary,
            List<Map<String, Object>> steps,
            long durationMs
    ) {
        opsAuditTraceService.save(
                traceId,
                "ASSISTANT",
                userInput,
                riskLevel,
                securityOutcome,
                toolName,
                executionOk,
                resultSummary,
                steps,
                durationMs,
                httpAuditSubject.currentOperatorId(),
                opsPathPolicy.getPolicyVersion(),
                Map.of(),
                Map.of(
                        "auditKind", "dialogue",
                        "requestChannel", "assistant",
                        "stage", "complete",
                        "decision", securityOutcome,
                        "targetType", "tool",
                        "targetName", toolName == null ? "" : toolName)
        );
    }

    public List<Map<String, Object>> newSteps() {
        return new ArrayList<>();
    }

    public void addStep(List<Map<String, Object>> steps, String phase, String detail) {
        steps.add(TraceService.step(phase, detail));
    }

    public void addStructuredStep(List<Map<String, Object>> steps, String phase, Map<String, Object> detail) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("phase", phase);
        if (detail != null) {
            row.putAll(detail);
        }
        steps.add(row);
    }

    public void addCot(List<Map<String, Object>> steps, int index, String title, String narrative) {
        String cn = title;
        if (title == null || title.isBlank()) {
            cn = OpsAuditPhases.titleCn(index);
        }
        steps.add(TraceService.cotStep(index, cn, narrative));
    }
}
