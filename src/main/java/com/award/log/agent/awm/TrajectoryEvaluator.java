package com.award.log.agent.awm;

import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 判断轨迹是否适合写入 Workflow Memory（对齐 AWM 的 Leval：仅成功轨迹诱导）。
 */
@Component
public class TrajectoryEvaluator {

    public boolean shouldInduce(OpsExperience experience) {
        if (experience == null || !experience.executionOk()) {
            return false;
        }
        String outcome = experience.securityOutcome();
        if (outcome == null || outcome.isBlank()) {
            return false;
        }
        if (isRejected(outcome)) {
            return false;
        }
        return switch (outcome) {
            case "EXECUTED", "REMEDIATED", "DIAGNOSED" -> true;
            case "PREVIEW" -> hasStructuredToolSteps(experience);
            case "ERROR", "READ_ONLY_SURFACE" -> false;
            default -> outcome.startsWith("DIAGNOSED") && hasExecuteSteps(experience);
        };
    }

    private boolean hasStructuredToolSteps(OpsExperience experience) {
        if (experience == null || experience.steps() == null) {
            return false;
        }
        long toolSteps = experience.steps().stream()
                .filter(s -> {
                    Object phase = s.get("phase");
                    return phase != null && !"workflow".equalsIgnoreCase(String.valueOf(phase));
                })
                .filter(s -> s.get("toolName") != null
                        || (s.get("detail") instanceof Map<?, ?> m && m.get("toolName") != null))
                .count();
        return toolSteps >= 2;
    }

    public boolean isRejected(String securityOutcome) {
        if (securityOutcome == null) {
            return false;
        }
        String u = securityOutcome.toUpperCase();
        return u.startsWith("REJECT") || u.contains("BLOCK");
    }

    private boolean hasExecuteSteps(OpsExperience experience) {
        if (experience.steps() == null) {
            return false;
        }
        return experience.steps().stream()
                .anyMatch(s -> "execute".equalsIgnoreCase(String.valueOf(s.get("phase"))));
    }
}
