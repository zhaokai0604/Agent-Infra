package com.award.log.service;

/**
 * AI智能分析服务接口
 */
public interface AiAnalysisService {

    /**
     * 分析日志
     * @param logContent 日志内容
     * @return 分析结果
     */
    String analyzeLog(String logContent);

    /**
     * 分析性能数据
     * @param metricName 指标名称
     * @param value 指标值
     * @return 分析结果
     */
    String analyzePerformance(String metricName, double value);

    /**
     * 分析告警
     * @param alertContent 告警内容
     * @return 分析结果
     */
    String analyzeAlert(String alertContent);

    /**
     * 生成分析报告
     * @param period 时间段
     * @return 分析报告
     */
    String generateReport(String period);
}
