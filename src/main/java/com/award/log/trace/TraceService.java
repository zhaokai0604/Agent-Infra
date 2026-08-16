package com.award.log.trace;

import com.award.log.model.TraceLog;
import com.award.log.service.OpsAuditTraceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class TraceService {

    private final OpsAuditTraceService opsAuditTraceService;

    /**
     * 赛题闭环：接收指令 → 安全校验 → 执行 → 结果（阶段快照 steps_json）。
     */
    public void recordFull(
            String traceId,
            String channel,
            String userInput,
            String riskLevel,
            String securityOutcome,
            String toolName,
            boolean executionOk,
            String resultSummary,
            List<Map<String, Object>> steps,
            long durationMs,
            String operatorUserId,
            String policyVersion
    ) {
        opsAuditTraceService.save(
                traceId,
                channel,
                userInput,
                riskLevel,
                securityOutcome,
                toolName,
                executionOk,
                resultSummary,
                steps == null ? new ArrayList<>() : steps,
                durationMs,
                operatorUserId,
                policyVersion
        );
        log.info("运维审计落库 traceId={} channel={} tool={} ok={} durationMs={}",
                traceId, channel, toolName, executionOk, durationMs);
    }

    /**
     * 兼容旧调用：写入一条简化闭环记录。
     */
    public void record(TraceLog traceLog) {
        List<Map<String, Object>> steps = new ArrayList<>();
        steps.add(Map.of("phase", "legacy_trace", "detail", traceLog.getToolName() != null ? traceLog.getToolName() : ""));
        recordFull(
                traceLog.getTraceId(),
                "CHAT",
                traceLog.getUserInput(),
                traceLog.getRiskLevel(),
                "PASS",
                traceLog.getToolName(),
                true,
                traceLog.getResultSummary(),
                steps,
                traceLog.getDurationMs(),
                null,
                null
        );
    }

    public static Map<String, Object> step(String phase, String detail) {
        Map<String, Object> m = new HashMap<>();
        m.put("phase", phase);
        m.put("detail", detail);
        return m;
    }

    /**
     * 思维链（CoT）单步，便于赛题“闭环可回溯”审计展示。
     */
    public static Map<String, Object> cotStep(int index, String titleCn, String narrative) {
        Map<String, Object> m = new HashMap<>();
        m.put("phase", "cot");
        m.put("step", index);
        m.put("title", titleCn);
        m.put("detail", String.format("[Step %d - %s] %s", index, titleCn, narrative));
        return m;
    }
}
