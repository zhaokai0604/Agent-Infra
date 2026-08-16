package com.award.log.agent.audit;

/**
 * 赛题闭环六步（与 ThreshCore 审计展示一致）。
 */
public final class OpsAuditPhases {

    public static final String RECEIVE = "receive";
    public static final String PERCEIVE = "perceive";
    public static final String REASON = "reason";
    public static final String SECURITY = "security";
    public static final String EXECUTE = "execute";
    public static final String VERIFY = "verify";

    public static final String RECEIVE_CN = "接收";
    public static final String PERCEIVE_CN = "感知";
    public static final String REASON_CN = "推理";
    public static final String SECURITY_CN = "安全校验";
    public static final String EXECUTE_CN = "执行";
    public static final String VERIFY_CN = "验证";

    private OpsAuditPhases() {
    }

    public static String titleCn(int stepIndex) {
        return switch (stepIndex) {
            case 1 -> RECEIVE_CN;
            case 2 -> PERCEIVE_CN;
            case 3 -> REASON_CN;
            case 4 -> SECURITY_CN;
            case 5 -> EXECUTE_CN;
            case 6 -> VERIFY_CN;
            default -> "步骤" + stepIndex;
        };
    }
}
