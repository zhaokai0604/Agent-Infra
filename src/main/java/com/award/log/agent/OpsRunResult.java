package com.award.log.agent;

import java.util.Map;

/**
 * 运维运行时统一返回结构（编排 Playbook 与巡检单轨共用）。
 */
public record OpsRunResult(String markdown, String traceId, Map<String, Object> report) {

    /** 剧本未命中：调用方应软落到 tool-agent，而不是展示死胡同文案。 */
    public boolean softFallback() {
        return report != null && Boolean.TRUE.equals(report.get("softFallback"));
    }
}
