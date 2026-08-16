package com.award.log.controller;

import com.award.log.common.Result;
import com.award.log.model.TaskAlarmConfig;
import com.award.log.security.RequestUserResolver;
import com.award.log.service.AlarmConfigService;
import com.award.log.task.AnalysisTaskManager;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@Slf4j
@Tag(name = "AlarmConfig", description = "非默认交付面：无挂载 UI / 仅 API，见 docs/deployment/交付API白名单.md")
@RestController
@RequestMapping("/api/alarm")
public class AlarmConfigController {

    private final AlarmConfigService alarmConfigService;
    private final AnalysisTaskManager analysisTaskManager;
    private final RequestUserResolver requestUserResolver;

    public AlarmConfigController(AlarmConfigService alarmConfigService,
                                 AnalysisTaskManager analysisTaskManager,
                                 RequestUserResolver requestUserResolver) {
        this.alarmConfigService = alarmConfigService;
        this.analysisTaskManager = analysisTaskManager;
        this.requestUserResolver = requestUserResolver;
    }

    @Operation(summary = "Get default alarm config")
    @GetMapping("/config")
    public Result<TaskAlarmConfig> getAlarmConfig() {
        try {
            return Result.success(alarmConfigService.getDefaultConfig(), "Default alarm config loaded");
        } catch (Exception e) {
            log.error("Failed to load default alarm config", e);
            return Result.error("Load failed");
        }
    }

    @Operation(summary = "Save default alarm config")
    @PostMapping("/config")
    public Result<TaskAlarmConfig> saveAlarmConfig(HttpServletRequest request,
                                                   @RequestBody(required = false) Map<String, Object> config) {
        if (!requestUserResolver.isAdmin(request)) {
            return Result.error(403, "Only administrators can update default alarm config");
        }
        try {
            TaskAlarmConfig saved = alarmConfigService.saveDefaultConfig(config == null ? Map.of() : config);
            return Result.success(saved, "Default alarm config saved");
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        } catch (Exception e) {
            log.error("Failed to save default alarm config", e);
            return Result.error("Save failed");
        }
    }

    @Operation(summary = "Get task alarm config")
    @GetMapping("/config/{taskId}")
    public Result<TaskAlarmConfig> getTaskAlarmConfig(HttpServletRequest request, @PathVariable String taskId) {
        if (!canAccessTask(request, taskId)) {
            return Result.error(404, "Task not found or access denied");
        }
        try {
            return Result.success(alarmConfigService.getEffectiveConfig(taskId), "Task alarm config loaded");
        } catch (Exception e) {
            log.error("Failed to load task alarm config for {}", taskId, e);
            return Result.error("Load failed");
        }
    }

    @Operation(summary = "Save task alarm config")
    @PostMapping("/config/{taskId}")
    public Result<TaskAlarmConfig> saveTaskAlarmConfig(HttpServletRequest request,
                                                       @PathVariable String taskId,
                                                       @RequestBody(required = false) Map<String, Object> config) {
        if (!canAccessTask(request, taskId)) {
            return Result.error(404, "Task not found or access denied");
        }
        try {
            TaskAlarmConfig saved = alarmConfigService.saveTaskConfig(taskId, config == null ? Map.of() : config);
            return Result.success(saved, "Task alarm config saved");
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        } catch (Exception e) {
            log.error("Failed to save task alarm config for {}", taskId, e);
            return Result.error("Save failed");
        }
    }

    private boolean canAccessTask(HttpServletRequest request, String taskId) {
        Integer userId = requestUserResolver.currentUserId(request);
        boolean admin = requestUserResolver.isAdmin(request);
        return analysisTaskManager.canAccessTask(taskId, userId, admin);
    }
}
