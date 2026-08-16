package com.award.log.service;

import com.award.log.model.PerformanceData;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 性能分析服务接口
 * 用于分析系统性能数据和生成性能趋势图表
 */
public interface PerformanceAnalysisService {

    /**
     * 记录性能数据
     * @param performanceData 性能数据
     * @return 是否记录成功
     */
    boolean recordPerformanceData(PerformanceData performanceData);

    /**
     * 批量记录性能数据
     * @param performanceDataList 性能数据列表
     * @return 记录成功的数量
     */
    int batchRecordPerformanceData(List<PerformanceData> performanceDataList);

    /**
     * 获取性能趋势数据
     * @param dataType 数据类型
     * @param startTime 开始时间
     * @param endTime 结束时间
     * @param interval 时间间隔（分钟）
     * @return 性能趋势数据
     */
    List<Map<String, Object>> getPerformanceTrend(String dataType, LocalDateTime startTime, LocalDateTime endTime, int interval);

    /**
     * 获取性能统计数据
     * @param dataType 数据类型
     * @param startTime 开始时间
     * @param endTime 结束时间
     * @return 性能统计数据
     */
    Map<String, Object> getPerformanceStatistics(String dataType, LocalDateTime startTime, LocalDateTime endTime);

    /**
     * 获取多维度性能数据
     * @param startTime 开始时间
     * @param endTime 结束时间
     * @param interval 时间间隔（分钟）
     * @return 多维度性能数据
     */
    Map<String, Object> getMultiDimensionPerformanceData(LocalDateTime startTime, LocalDateTime endTime, int interval);

    /**
     * 获取性能基线数据
     * @param dataType 数据类型
     * @param days 天数
     * @return 性能基线数据
     */
    Map<String, Object> getPerformanceBaseline(String dataType, int days);

    /**
     * 清理过期的性能数据
     * @param beforeTime 清理时间点之前的数据
     * @return 清理成功的数量
     */
    int cleanupExpiredPerformanceData(LocalDateTime beforeTime);

    /**
     * 收集系统性能数据
     */
    void collectSystemPerformanceData();
}
