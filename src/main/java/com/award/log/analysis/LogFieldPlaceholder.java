package com.award.log.analysis;

/**
 * 日志字段占位文案（解析失败时统一用语，避免用虚假值冒充真实观测）。
 */
public final class LogFieldPlaceholder {

    /** 未能从行内正则解析出 yyyy-MM-dd HH:mm:ss / Windows 风格时间时使用 */
    public static final String TIME_UNPARSED = "（未解析到标准时间戳，请以该行正文内时间为准）";

    private LogFieldPlaceholder() {
    }
}
