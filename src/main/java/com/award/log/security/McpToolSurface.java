package com.award.log.security;

/**
 * MCP 工具面：在「用户 utterance 风险偏高」时收缩为只读工具集，写类工具在 INITIAL 请求下由安全门拦截。
 */
public enum McpToolSurface {
    /** 全量白名单工具（仍受 {@link McpInvocationSecurityGate} 各条规则约束） */
    FULL,
    /** 仅允许观测/分析类工具；禁止临时清理、日志删除、服务重启、提权探测等 */
    READ_ONLY
}
