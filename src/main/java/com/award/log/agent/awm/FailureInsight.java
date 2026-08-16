package com.award.log.agent.awm;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reflexion 式失败教训（只存自然语言反思，不存可执行动作）。
 */
public record FailureInsight(
        String insightKey,
        String securityCode,
        String toolName,
        String intentHint,
        String reflection,
        String sourceTraceId,
        int hitCount
) {
    public Map<String, Object> toContextMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("securityCode", securityCode);
        m.put("toolName", toolName != null ? toolName : "");
        m.put("intentHint", intentHint != null ? intentHint : "");
        m.put("reflection", reflection);
        m.put("hitCount", hitCount);
        return m;
    }
}
