package com.award.log.controller;

import com.award.log.common.Result;
import com.award.log.security.RequestUserResolver;
import com.award.log.service.AiLogAlarmService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Tag(name = "AlarmHistory", description = "非默认交付面：无挂载 UI / 仅 API，见 docs/deployment/交付API白名单.md")
@RestController
@RequestMapping("/api/alarm/history")
public class AlarmHistoryController {

    private final AiLogAlarmService aiLogAlarmService;
    private final JdbcTemplate jdbcTemplate;
    private final RequestUserResolver requestUserResolver;

    @Value("${spring.datasource.url:}")
    private String datasourceUrl;

    public AlarmHistoryController(AiLogAlarmService aiLogAlarmService,
                                  JdbcTemplate jdbcTemplate,
                                  RequestUserResolver requestUserResolver) {
        this.aiLogAlarmService = aiLogAlarmService;
        this.jdbcTemplate = jdbcTemplate;
        this.requestUserResolver = requestUserResolver;
    }

    @Operation(summary = "Get alarm history")
    @GetMapping("/list")
    public Result<Map<String, Object>> getAlarmHistory(HttpServletRequest request,
                                                       @RequestParam(defaultValue = "1") int pageNum,
                                                       @RequestParam(defaultValue = "10") int pageSize,
                                                       @RequestParam(required = false) String level,
                                                       @RequestParam(required = false) String taskId) {
        Integer userId = requestUserResolver.currentUserId(request);
        boolean admin = requestUserResolver.isAdmin(request);
        Map<String, Object> history = aiLogAlarmService.getAlarmHistoryForUser(
                pageNum, pageSize, level, taskId, userId, admin);
        return Result.success(history);
    }

    @Operation(summary = "Get alarm statistics")
    @GetMapping("/statistics")
    public Result<Map<String, Object>> getAlarmStatistics(HttpServletRequest request,
                                                          @RequestParam(defaultValue = "7") int days,
                                                          @RequestParam(required = false) String level,
                                                          @RequestParam(required = false) String taskId) {
        Integer userId = requestUserResolver.currentUserId(request);
        boolean admin = requestUserResolver.isAdmin(request);
        Map<String, Object> statistics = aiLogAlarmService.getAlarmStatisticsForUser(
                days, level, taskId, userId, admin);
        return Result.success(statistics);
    }

    @Operation(summary = "Get alarm trend")
    @GetMapping("/trend")
    public Result<List<Map<String, Object>>> getAlarmTrend(HttpServletRequest request,
                                                           @RequestParam(defaultValue = "7") int days,
                                                           @RequestParam(required = false) String level,
                                                           @RequestParam(required = false) String taskId) {
        Integer userId = requestUserResolver.currentUserId(request);
        boolean admin = requestUserResolver.isAdmin(request);
        return Result.success(aiLogAlarmService.getAlarmTrendForUser(days, level, taskId, userId, admin));
    }

    @Operation(summary = "Get alarm level distribution")
    @GetMapping("/level-distribution")
    public Result<List<Map<String, Object>>> getAlarmLevelDistribution(HttpServletRequest request,
                                                                       @RequestParam(defaultValue = "7") int days,
                                                                       @RequestParam(required = false) String level,
                                                                       @RequestParam(required = false) String taskId) {
        Integer userId = requestUserResolver.currentUserId(request);
        boolean admin = requestUserResolver.isAdmin(request);
        return Result.success(aiLogAlarmService.getAlarmLevelDistributionForUser(
                days, level, taskId, userId, admin));
    }

    @Operation(summary = "Get alarm root cause statistics")
    @GetMapping("/root-cause-statistics")
    public Result<List<Map<String, Object>>> getAlarmRootCauseStatistics(HttpServletRequest request,
                                                                         @RequestParam(defaultValue = "7") int days,
                                                                         @RequestParam(required = false) String level,
                                                                         @RequestParam(required = false) String taskId) {
        Integer userId = requestUserResolver.currentUserId(request);
        boolean admin = requestUserResolver.isAdmin(request);
        return Result.success(aiLogAlarmService.getAlarmRootCauseStatisticsForUser(
                days, level, taskId, userId, admin));
    }

    @Operation(summary = "Reprocess alarms for a task")
    @GetMapping("/process-by-task")
    public Result<Void> processAlarmsByTaskId(HttpServletRequest request, @RequestParam String taskId) {
        Integer userId = requestUserResolver.currentUserId(request);
        boolean admin = requestUserResolver.isAdmin(request);
        try {
            aiLogAlarmService.processAlarmsByTaskIdForUser(taskId, userId, admin);
            return Result.success();
        } catch (IllegalArgumentException e) {
            return Result.error(404, e.getMessage());
        } catch (Exception e) {
            log.error("Failed to process alarms for task {}", taskId, e);
            return Result.error("Process failed");
        }
    }

    @Operation(summary = "Alarm history debug summary")
    @GetMapping("/debug/summary")
    public Result<Map<String, Object>> debugSummary(HttpServletRequest request) {
        if (!requestUserResolver.isAdmin(request)) {
            return Result.error(403, "Only administrators can view alarm debug summary");
        }
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM log_alarm", Long.class);
        String currentDb = jdbcTemplate.queryForObject("SELECT DATABASE()", String.class);
        Map<String, Object> data = new HashMap<>();
        data.put("alarmTotal", total == null ? 0 : total);
        data.put("currentDatabase", currentDb);
        data.put("datasourceUrl", datasourceUrl);
        return Result.success(data);
    }
}
