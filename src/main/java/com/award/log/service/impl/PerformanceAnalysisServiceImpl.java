package com.award.log.service.impl;

import com.award.log.model.PerformanceData;
import com.award.log.service.StatisticsService;
import com.award.log.service.PerformanceAnalysisService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * 性能分析服务实现类
 * 实现性能分析的核心功能
 */
@Slf4j
@Service
public class PerformanceAnalysisServiceImpl implements PerformanceAnalysisService {

    @Resource
    private StatisticsService statisticsService;


    // 用于存储性能数据的内存队列
    private final ConcurrentLinkedQueue<PerformanceData> performanceDataQueue = new ConcurrentLinkedQueue<>();
    private final AtomicLong idGenerator = new AtomicLong(1);
    private static final int MAX_QUEUE_SIZE = 10000; // 最大队列大小

    @Override
    public boolean recordPerformanceData(PerformanceData performanceData) {
        try {
            // 设置ID和采集时间
            performanceData.setId(idGenerator.getAndIncrement());
            if (performanceData.getCollectTime() == null) {
                performanceData.setCollectTime(LocalDateTime.now());
            }

            // 添加到队列
            if (performanceDataQueue.size() >= MAX_QUEUE_SIZE) {
                // 如果队列已满，移除最早的数据
                performanceDataQueue.poll();
            }
            performanceDataQueue.offer(performanceData);
            log.debug("记录性能数据成功: {}={}", performanceData.getDataType(), performanceData.getValue());
            return true;
        } catch (Exception e) {
            log.error("记录性能数据失败", e);
            return false;
        }
    }

    @Override
    public int batchRecordPerformanceData(List<PerformanceData> performanceDataList) {
        int successCount = 0;
        for (PerformanceData data : performanceDataList) {
            if (recordPerformanceData(data)) {
                successCount++;
            }
        }
        return successCount;
    }

    @Override
    public List<Map<String, Object>> getPerformanceTrend(String dataType, LocalDateTime startTime, LocalDateTime endTime, int interval) {
        List<Map<String, Object>> trendData = new ArrayList<>();

        try {
            // 过滤指定时间范围内的数据
            List<PerformanceData> filteredData = performanceDataQueue.stream()
                    .filter(data -> data.getDataType().equals(dataType))
                    .filter(data -> !data.getCollectTime().isBefore(startTime))
                    .filter(data -> !data.getCollectTime().isAfter(endTime))
                    .sorted(Comparator.comparing(PerformanceData::getCollectTime))
                    .collect(Collectors.toList());

            // 按时间间隔聚合数据
            LocalDateTime currentTime = startTime;
            while (currentTime.isBefore(endTime)) {
                LocalDateTime nextTime = currentTime.plusMinutes(interval);
                // 创建副本以满足lambda表达式的要求
                final LocalDateTime currentTimeCopy = currentTime;
                final LocalDateTime nextTimeCopy = nextTime;
                List<PerformanceData> intervalData = filteredData.stream()
                        .filter(data -> !data.getCollectTime().isBefore(currentTimeCopy))
                        .filter(data -> data.getCollectTime().isBefore(nextTimeCopy))
                        .collect(Collectors.toList());

                if (!intervalData.isEmpty()) {
                    // 计算平均值
                    double avgValue = intervalData.stream()
                            .mapToDouble(PerformanceData::getValue)
                            .average()
                            .orElse(0);

                    Map<String, Object> pointData = new HashMap<>();
                    pointData.put("timestamp", currentTime);
                    pointData.put("value", avgValue);
                    pointData.put("count", intervalData.size());
                    trendData.add(pointData);
                }

                currentTime = nextTime;
            }
        } catch (Exception e) {
            log.error("获取性能趋势数据失败", e);
        }

        return trendData;
    }

    @Override
    public Map<String, Object> getPerformanceStatistics(String dataType, LocalDateTime startTime, LocalDateTime endTime) {
        Map<String, Object> statistics = new HashMap<>();

        try {
            // 过滤指定时间范围内的数据
            List<PerformanceData> filteredData = performanceDataQueue.stream()
                    .filter(data -> data.getDataType().equals(dataType))
                    .filter(data -> !data.getCollectTime().isBefore(startTime))
                    .filter(data -> !data.getCollectTime().isAfter(endTime))
                    .collect(Collectors.toList());

            if (!filteredData.isEmpty()) {
                // 计算统计数据
                double avgValue = filteredData.stream().mapToDouble(PerformanceData::getValue).average().orElse(0);
                double maxValue = filteredData.stream().mapToDouble(PerformanceData::getValue).max().orElse(0);
                double minValue = filteredData.stream().mapToDouble(PerformanceData::getValue).min().orElse(0);
                double sumValue = filteredData.stream().mapToDouble(PerformanceData::getValue).sum();

                statistics.put("average", avgValue);
                statistics.put("maximum", maxValue);
                statistics.put("minimum", minValue);
                statistics.put("sum", sumValue);
                statistics.put("count", filteredData.size());
                statistics.put("dataType", dataType);
                statistics.put("timeRange", startTime + " 至 " + endTime);
            }
        } catch (Exception e) {
            log.error("获取性能统计数据失败", e);
        }

        return statistics;
    }

    @Override
    public Map<String, Object> getMultiDimensionPerformanceData(LocalDateTime startTime, LocalDateTime endTime, int interval) {
        Map<String, Object> multiDimensionData = new HashMap<>();

        try {
            // 定义需要分析的数据类型
            List<String> dataTypes = Arrays.asList("CPU", "MEMORY", "DISK", "NETWORK", "LOAD");

            // 获取每种数据类型的趋势
            for (String dataType : dataTypes) {
                List<Map<String, Object>> trendData = getPerformanceTrend(dataType, startTime, endTime, interval);
                multiDimensionData.put(dataType.toLowerCase() + "Trend", trendData);
            }

            multiDimensionData.put("startTime", startTime);
            multiDimensionData.put("endTime", endTime);
            multiDimensionData.put("interval", interval);
        } catch (Exception e) {
            log.error("获取多维度性能数据失败", e);
        }

        return multiDimensionData;
    }

    @Override
    public Map<String, Object> getPerformanceBaseline(String dataType, int days) {
        Map<String, Object> baselineData = new HashMap<>();

        try {
            LocalDateTime endTime = LocalDateTime.now();
            LocalDateTime startTime = endTime.minusDays(days);

            // 获取历史数据
            List<PerformanceData> historicalData = performanceDataQueue.stream()
                    .filter(data -> data.getDataType().equals(dataType))
                    .filter(data -> !data.getCollectTime().isBefore(startTime))
                    .filter(data -> !data.getCollectTime().isAfter(endTime))
                    .collect(Collectors.toList());

            if (!historicalData.isEmpty()) {
                // 计算基线数据
                double avgValue = historicalData.stream().mapToDouble(PerformanceData::getValue).average().orElse(0);
                double maxValue = historicalData.stream().mapToDouble(PerformanceData::getValue).max().orElse(0);
                double minValue = historicalData.stream().mapToDouble(PerformanceData::getValue).min().orElse(0);

                // 计算标准差
                double variance = historicalData.stream()
                        .mapToDouble(data -> Math.pow(data.getValue() - avgValue, 2))
                        .average()
                        .orElse(0);
                double stdDev = Math.sqrt(variance);

                baselineData.put("average", avgValue);
                baselineData.put("maximum", maxValue);
                baselineData.put("minimum", minValue);
                baselineData.put("standardDeviation", stdDev);
                baselineData.put("threshold", avgValue + 2 * stdDev); // 阈值：平均值 + 2倍标准差
                baselineData.put("days", days);
                baselineData.put("dataType", dataType);
            }
        } catch (Exception e) {
            log.error("获取性能基线数据失败", e);
        }

        return baselineData;
    }

    @Override
    public int cleanupExpiredPerformanceData(LocalDateTime beforeTime) {
        int cleanupCount = 0;

        try {
            Iterator<PerformanceData> iterator = performanceDataQueue.iterator();
            while (iterator.hasNext()) {
                PerformanceData data = iterator.next();
                if (data.getCollectTime().isBefore(beforeTime)) {
                    iterator.remove();
                    cleanupCount++;
                }
            }
            log.info("清理过期性能数据成功，清理数量: {}", cleanupCount);
        } catch (Exception e) {
            log.error("清理过期性能数据失败", e);
        }

        return cleanupCount;
    }

    /**
     * 定期收集系统性能数据
     */
    public void collectSystemPerformanceData() {
        try {
            Map<String, Object> current = statisticsService.getSystemPerformance(null);
            LocalDateTime now = LocalDateTime.now();

            recordMetric("CPU", current.get("cpuUsage"), now);
            recordMetric("MEMORY", current.get("memoryUsage"), now);
            recordMetric("DISK", current.get("diskUsage"), now);
            recordMetric("NETWORK", current.get("networkUsage"), now);
            recordMetric("LOAD", current.get("cpuLoad"), now);

        } catch (Exception e) {
            log.error("收集系统性能数据失败", e);
        }
    }

    private void recordMetric(String type, Object rawValue, LocalDateTime collectTime) {
        double value = rawValue instanceof Number ? ((Number) rawValue).doubleValue() : 0.0;
        PerformanceData data = new PerformanceData();
        data.setDataType(type);
        data.setValue(value);
        data.setUnit("%");
        data.setCollectTime(collectTime);
        recordPerformanceData(data);
    }
}
