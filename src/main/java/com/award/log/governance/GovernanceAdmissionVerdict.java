package com.award.log.governance;

/**
 * 动作准入结论：在风险分 / dry-run 之前先过资产与动作矩阵。
 */
public enum GovernanceAdmissionVerdict {
    /** 允许进入 HYBRID 低分自动车道（仍受 ops.dry-run 等约束） */
    ALLOW_AUTO,
    /** 仅待确认 / 审批后执行 */
    CONFIRM_ONLY,
    /** 禁止纳入自动或待确认方案 */
    FORBIDDEN
}
