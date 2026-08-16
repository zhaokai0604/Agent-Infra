package com.award.log.agent;

/**
 * 助手回复模式：决定上下文注入深度、系统提示词与是否走工具/编排。
 */
public enum AssistantReplyMode {

    /** 寒暄、自我介绍、纯标点 — 无指标上下文 */
    CHITCHAT,

    /** 解释、追问、总结 — 轻上下文，不主动罗列指标 */
    CONVERSATION,

    /** 运维问答 — 注入指标摘要，先自然语言后结构化数据 */
    OPS_ANALYSIS,

    /** MCP 工具循环 + 全量上下文 */
    TOOL_AGENT,

    /** Playbook 编排（磁盘/CPU/巡检等） */
    ORCHESTRATE
}
