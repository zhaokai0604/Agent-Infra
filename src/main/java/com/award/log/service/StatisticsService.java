package com.award.log.service;

import java.util.Map;

/**
 * 数据统计Service
 */
public interface StatisticsService {
    /**
     * 获取日志近期数据汇总
     * @param days 天数
     * @return 日志汇总数据
     */
    Map<String, Object> getRecentLogSummary(int days);

    /**
     * 获取系统性能数据
     * @param preferredNetworkInterface 指定网卡名称（可选）
     * @return 性能数据
     */
    Map<String, Object> getSystemPerformance(String preferredNetworkInterface);



    /**
     * 获取任务状态统计
     * @return 任务状态统计数据
     */
    Map<String, Object> getTaskStatusStatistics();

    Map<String, Object> getAnomalyLogStatistics(int days);
}