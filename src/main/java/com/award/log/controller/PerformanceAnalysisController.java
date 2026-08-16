package com.award.log.controller;

import com.award.log.common.Result;
import com.award.log.model.PerformanceData;
import com.award.log.service.PerformanceAnalysisService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 性能分析Controller
 * 提供性能数据的记录、查询和分析功能
 */
@Slf4j
@Deprecated(since = "delivery-2026-07", forRemoval = false)
@Tag(name = "PerformanceAnalysis", description = "非默认交付面：扩展细接口；演示统计用 /admin/statistics/performance，见 docs/deployment/交付API白名单.md")
@RestController
@RequestMapping("/api/performance")
public class PerformanceAnalysisController {

    @Autowired
    private PerformanceAnalysisService performanceAnalysisService;

    @Operation(summary = "记录性能数据")
    @PostMapping("/record")
    public Result<Boolean> recordPerformanceData(@RequestBody PerformanceData performanceData) {
        log.info("记录性能数据: {}={}", performanceData.getDataType(), performanceData.getValue());
        boolean success = performanceAnalysisService.recordPerformanceData(performanceData);
        return Result.success(success);
    }

    @Operation(summary = "批量记录性能数据")
    @PostMapping("/batch-record")
    public Result<Integer> batchRecordPerformanceData(@RequestBody List<PerformanceData> performanceDataList) {
        log.info("批量记录性能数据，数量: {}", performanceDataList.size());
        int successCount = performanceAnalysisService.batchRecordPerformanceData(performanceDataList);
        return Result.success(successCount);
    }

    @Operation(summary = "获取性能趋势数据")
    @GetMapping("/trend")
    public Result<List<Map<String, Object>>> getPerformanceTrend(
            @RequestParam String dataType,
            @RequestParam String startTime,
            @RequestParam String endTime,
            @RequestParam(defaultValue = "5") int interval) {
        log.info("获取性能趋势数据，类型: {}, 时间范围: {} - {}, 间隔: {}分钟", dataType, startTime, endTime, interval);
        
        LocalDateTime start = LocalDateTime.parse(startTime);
        LocalDateTime end = LocalDateTime.parse(endTime);
        
        List<Map<String, Object>> trendData = performanceAnalysisService.getPerformanceTrend(dataType, start, end, interval);
        return Result.success(trendData);
    }

    @Operation(summary = "获取性能统计数据")
    @GetMapping("/statistics")
    public Result<Map<String, Object>> getPerformanceStatistics(
            @RequestParam String dataType,
            @RequestParam String startTime,
            @RequestParam String endTime) {
        log.info("获取性能统计数据，类型: {}, 时间范围: {} - {}", dataType, startTime, endTime);
        
        LocalDateTime start = LocalDateTime.parse(startTime);
        LocalDateTime end = LocalDateTime.parse(endTime);
        
        Map<String, Object> statistics = performanceAnalysisService.getPerformanceStatistics(dataType, start, end);
        return Result.success(statistics);
    }

    @Operation(summary = "获取多维度性能数据")
    @GetMapping("/multi-dimension")
    public Result<Map<String, Object>> getMultiDimensionPerformanceData(
            @RequestParam String startTime,
            @RequestParam String endTime,
            @RequestParam(defaultValue = "5") int interval) {
        log.info("获取多维度性能数据，时间范围: {} - {}, 间隔: {}分钟", startTime, endTime, interval);
        
        LocalDateTime start = LocalDateTime.parse(startTime);
        LocalDateTime end = LocalDateTime.parse(endTime);
        
        Map<String, Object> multiDimensionData = performanceAnalysisService.getMultiDimensionPerformanceData(start, end, interval);
        return Result.success(multiDimensionData);
    }

    @Operation(summary = "获取性能基线数据")
    @GetMapping("/baseline")
    public Result<Map<String, Object>> getPerformanceBaseline(
            @RequestParam String dataType,
            @RequestParam(defaultValue = "7") int days) {
        log.info("获取性能基线数据，类型: {}, 天数: {}", dataType, days);
        
        Map<String, Object> baselineData = performanceAnalysisService.getPerformanceBaseline(dataType, days);
        return Result.success(baselineData);
    }

    @Operation(summary = "清理过期性能数据")
    @PostMapping("/cleanup")
    public Result<Integer> cleanupExpiredPerformanceData(@RequestParam String beforeTime) {
        log.info("清理过期性能数据，时间: {}", beforeTime);
        
        LocalDateTime before = LocalDateTime.parse(beforeTime);
        int cleanupCount = performanceAnalysisService.cleanupExpiredPerformanceData(before);
        return Result.success(cleanupCount);
    }

    @Operation(summary = "收集系统性能数据")
    @PostMapping("/collect-system-data")
    public Result<Void> collectSystemPerformanceData() {
        log.info("收集系统性能数据");
        performanceAnalysisService.collectSystemPerformanceData();
        return Result.success();
    }
}
