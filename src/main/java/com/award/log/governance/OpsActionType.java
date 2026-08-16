package com.award.log.governance;

/**
 * 受治理约束的确定性运维动作（与 Runbook / 巡检步骤 kind 对应）。
 */
public enum OpsActionType {
    TEMP_CLEANUP,
    LOG_CLEANUP,
    SERVICE_RESTART;

    public static OpsActionType fromStepKind(String kind) {
        if (kind == null) {
            return null;
        }
        return switch (kind.trim().toUpperCase()) {
            case "CLEAN_TEMP" -> TEMP_CLEANUP;
            case "CLEAN_LOG" -> LOG_CLEANUP;
            case "RESTART_SERVICE" -> SERVICE_RESTART;
            default -> null;
        };
    }
}
