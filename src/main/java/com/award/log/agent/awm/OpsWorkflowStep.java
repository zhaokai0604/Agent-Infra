package com.award.log.agent.awm;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AWM workflow 单步：环境描述 + 推理 + 工具动作（参数模板可含 {path} 等占位符）。
 */
public record OpsWorkflowStep(
        String envDesc,
        String reason,
        String toolName,
        Map<String, String> argsTemplate
) {
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("envDesc", envDesc != null ? envDesc : "");
        m.put("reason", reason != null ? reason : "");
        m.put("toolName", toolName != null ? toolName : "");
        m.put("argsTemplate", argsTemplate != null ? argsTemplate : Map.of());
        return m;
    }

    public static OpsWorkflowStep of(String envDesc, String reason, String toolName) {
        return new OpsWorkflowStep(envDesc, reason, toolName, Map.of());
    }

    public static OpsWorkflowStep of(String envDesc, String reason, String toolName, Map<String, String> args) {
        return new OpsWorkflowStep(envDesc, reason, toolName, args != null ? args : Map.of());
    }
}
