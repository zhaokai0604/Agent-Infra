package com.award.log.agent;

/**
 * 细粒度用户意图分类。与 {@link AssistantReplyMode} 配合：Category 决定「说什么」，Mode 决定「怎么走链路」。
 */
public enum AssistantIntentCategory {

    /** 空消息 */
    EMPTY,

    /** 你好、在吗 */
    GREETING,

    /** 再见、拜拜 */
    FAREWELL,

    /** 谢谢、辛苦了 */
    GRATITUDE,

    /** 好的、收到、明白了 */
    ACKNOWLEDGMENT,

    /** 你是谁、你能做什么 */
    CAPABILITY_INQUIRY,

    /** 怎么用、如何开始 */
    USAGE_HELP,

    /** 单字符 ?、没听懂 */
    CLARIFICATION,

    /** 接着、刚才、上面那个 */
    FOLLOW_UP,

    /** 为什么、怎么回事 */
    EXPLANATION,

    /** 总结一下、概括 */
    SUMMARIZATION,

    /** 哪个更好、区别是什么 */
    COMPARISON,

    /** 不对、错了、重新来 */
    CORRECTION,

    /** 取消、不要了、算了 */
    CANCEL,

    /** 不要调用工具、纯文字 */
    DECLINE_TOOLS,

    /** 确认执行、开始清理（写操作） */
    CONFIRM_WRITE,

    /** 仅预览、不要真删 */
    PREVIEW_ONLY,

    /** 查一下 CPU/内存（只读指标） */
    METRICS_QUERY,

    /** 磁盘满、CPU 高等具体运维问题 */
    OPS_DIAGNOSIS,

    /** 一键巡检、全面检查 */
    PATROL_ORCHESTRATE,

    /** 继续处理巡检待办 */
    PATROL_CONTINUATION,

    /** 无法归类的一般对话 */
    GENERAL
}
