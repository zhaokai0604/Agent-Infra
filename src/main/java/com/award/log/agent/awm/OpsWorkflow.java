package com.award.log.agent.awm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent Workflow Memory 条目：可复用运维子流程。
 */
public record OpsWorkflow(
        String workflowId,
        String domainTag,
        List<String> findingKinds,
        String title,
        String description,
        List<OpsWorkflowStep> steps,
        String sourceType,
        String sourceTraceId,
        int utilityCount,
        boolean enabled
) {
    public Map<String, Object> toContextMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("workflowId", workflowId);
        m.put("domain", domainTag);
        m.put("findingKinds", findingKinds != null ? findingKinds : List.of());
        m.put("title", title);
        m.put("description", description);
        List<Map<String, Object>> stepMaps = new ArrayList<>();
        if (steps != null) {
            for (OpsWorkflowStep s : steps) {
                stepMaps.add(s.toMap());
            }
        }
        m.put("steps", stepMaps);
        m.put("utilityCount", utilityCount);
        return m;
    }

    public String toMarkdownBrief() {
        StringBuilder sb = new StringBuilder();
        sb.append("**").append(title).append("** (`").append(workflowId).append("`)\n");
        if (description != null && !description.isBlank()) {
            sb.append(description).append("\n");
        }
        if (steps != null) {
            int i = 1;
            for (OpsWorkflowStep s : steps) {
                sb.append(i++).append(". [").append(nullToEmpty(s.envDesc())).append("] ")
                        .append(nullToEmpty(s.reason()))
                        .append(" → `").append(nullToEmpty(s.toolName())).append("`\n");
            }
        }
        return sb.toString().trim();
    }

    public List<String> toolSequence() {
        List<String> seq = new ArrayList<>();
        if (steps == null) {
            return seq;
        }
        for (OpsWorkflowStep s : steps) {
            if (s.toolName() != null && !s.toolName().isBlank()) {
                seq.add(s.toolName());
            }
        }
        return seq;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
