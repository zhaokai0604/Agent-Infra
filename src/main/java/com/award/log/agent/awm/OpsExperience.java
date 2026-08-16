package com.award.log.agent.awm;

import java.util.List;
import java.util.Map;

/**
 * 单次 Agent 运行经验，供 AWM 诱导与评估。
 */
public record OpsExperience(
        String traceId,
        String userInput,
        String channel,
        String toolName,
        String securityOutcome,
        boolean executionOk,
        String resultSummary,
        List<Map<String, Object>> steps,
        long durationMs,
        Integer healingScore
) {
}
