package com.award.log.controller;

import com.award.log.common.Result;
import com.award.log.service.StatisticsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 数据统计Controller
 */
@Slf4j
@Tag(name = "Statistics")
@RestController
@RequestMapping("/admin/statistics")
public class StatisticsController {

    @Autowired
    private StatisticsService statisticsService;

    @Operation(summary = "获取近期日志汇总")
    @GetMapping("/log-summary")
    public Result<Map<String, Object>> getLogSummary(@RequestParam(defaultValue = "7") int days) {
        log.info("获取近期日志汇总，天数: {}", days);
        Map<String, Object> summary = statisticsService.getRecentLogSummary(days);
        return Result.success(summary);
    }

    @Operation(summary = "获取系统性能数据")
    @GetMapping("/performance")
    public Result<Map<String, Object>> getPerformance(@RequestParam(required = false) String networkInterface) {
        log.info("获取系统性能数据，指定网卡: {}", networkInterface);
        Map<String, Object> performance = statisticsService.getSystemPerformance(networkInterface);
        return Result.success(performance);
    }

    @Operation(summary = "获取异常日志统计")
    @GetMapping("/anomaly-statistics")
    public Result<Map<String, Object>> getAnomalyStatistics(@RequestParam(defaultValue = "7") int days) {
        log.info("获取异常日志统计，天数: {}", days);
        Map<String, Object> statistics = statisticsService.getAnomalyLogStatistics(days);
        return Result.success(statistics);
    }

    @Operation(summary = "获取任务状态统计")
    @GetMapping("/task-status")
    public Result<Map<String, Object>> getTaskStatusStatistics() {
        log.info("获取任务状态统计");
        Map<String, Object> statistics = statisticsService.getTaskStatusStatistics();
        return Result.success(statistics);
    }
}