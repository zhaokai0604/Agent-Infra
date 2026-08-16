package com.award.log.controller;

import com.award.log.common.Result;
import com.award.log.security.RequestUserResolver;
import com.award.log.service.OpsDeferredTaskService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@Slf4j
@Deprecated(since = "delivery-2026-07", forRemoval = false)
@Tag(name = "OpsSchedule", description = "非默认交付面：无挂载 UI / 仅 API，见 docs/deployment/交付API白名单.md")
@RestController
@RequestMapping("/api/ops-schedule")
@RequiredArgsConstructor
public class OpsScheduleController {

    private final OpsDeferredTaskService opsDeferredTaskService;
    private final RequestUserResolver requestUserResolver;

    @Operation(summary = "Create deferred ops task")
    @PostMapping("/tasks")
    public Result<Map<String, Object>> createTask(HttpServletRequest request,
                                                  @RequestBody Map<String, Object> body) {
        if (!requestUserResolver.isAdmin(request)) {
            return Result.error(403, "Only administrators can create deferred ops tasks");
        }
        try {
            Object delayMinutesValue = body.get("delayMinutes");
            if (delayMinutesValue == null) {
                return Result.error("delayMinutes cannot be empty");
            }
            int delayMinutes = delayMinutesValue instanceof Number number
                    ? number.intValue()
                    : Integer.parseInt(String.valueOf(delayMinutesValue).trim());
            String toolName = String.valueOf(body.getOrDefault("toolName", "")).trim();
            @SuppressWarnings("unchecked")
            Map<String, Object> parameters = (Map<String, Object>) body.get("parameters");

            Integer userId = requestUserResolver.currentUserId(request);
            String operatorId = userId == null ? "" : String.valueOf(userId);
            Map<String, Object> data = opsDeferredTaskService.schedule(delayMinutes, toolName, parameters, operatorId);
            return Result.success(data, "Deferred task created");
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        } catch (Exception e) {
            log.error("Failed to create deferred ops task", e);
            return Result.error("Create failed: " + e.getMessage());
        }
    }

    @Operation(summary = "List pending deferred ops tasks")
    @GetMapping("/tasks")
    public Result<List<Map<String, Object>>> listTasks(HttpServletRequest request) {
        Integer userId = requestUserResolver.currentUserId(request);
        String operatorId = userId == null ? "" : String.valueOf(userId);
        boolean admin = requestUserResolver.isAdmin(request);
        return Result.success(opsDeferredTaskService.listPending(operatorId, admin));
    }

    @Operation(summary = "Cancel deferred ops task")
    @DeleteMapping("/tasks/{taskId}")
    public Result<Boolean> cancelTask(HttpServletRequest request, @PathVariable String taskId) {
        Integer userId = requestUserResolver.currentUserId(request);
        String operatorId = userId == null ? "" : String.valueOf(userId);
        boolean ok = opsDeferredTaskService.cancel(taskId, operatorId, requestUserResolver.isAdmin(request));
        return ok
                ? Result.success(true, "Cancelled")
                : Result.error(404, "Task not found, already executed, or access denied");
    }
}
