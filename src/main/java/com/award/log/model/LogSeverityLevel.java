package com.award.log.model;

/**
 * 日志等级枚举（带风险权重+可视化配色，异常加权计算核心）
 * 创新点：等级得分加权参与异常评分，致命/错误等级权重翻倍，提升故障识别优先级
 */
public enum LogSeverityLevel {
    FATAL_LEVEL("致命故障", 5, "#E53935", 2.0), // 系统崩溃/不可恢复→权重2.0（最高）
    ERROR_LEVEL("运行错误", 4, "#FF5722", 1.8), // 功能异常/人工干预→权重1.8
    WARNING_LEVEL("风险警告", 3, "#FFC107", 1.2), // 潜在风险/非核心→权重1.2
    INFO_LEVEL("系统信息", 2, "#1E88E5", 1.0),   // 正常运行→权重1.0
    DEBUG_LEVEL("调试跟踪", 1, "#4CAF50", 1.0), // 开发调试→权重1.0
    UNKNOWN_LEVEL("未知等级", 0, "#9E9E9E", 1.0);// 无等级→权重1.0（兜底）

    private final String severityCnName; // 等级中文名称（可视化）
    private final int severityScore;     // 基础得分（0-5）
    private final String colorCode;      // 可视化十六进制配色（HTML/ECharts）
    private final double weightFactor;   // 风险权重因子（异常评分加权）

    LogSeverityLevel(String severityCnName, int severityScore, String colorCode, double weightFactor) {
        this.severityCnName = severityCnName;
        this.severityScore = severityScore;
        this.colorCode = colorCode;
        this.weightFactor = weightFactor;
    }

    public String getSeverityCnName() { return severityCnName; }
    public int getSeverityScore() { return severityScore; }
    public String getColorCode() { return colorCode; }
    public double getWeightFactor() { return weightFactor; }
}
