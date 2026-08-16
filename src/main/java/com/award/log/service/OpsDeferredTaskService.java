package com.award.log.service;

import com.award.log.controller.McpExecuteController;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

@Slf4j
@Service
public class OpsDeferredTaskService {

    private static final Set<String> ALLOWED_DEFERRED_TOOLS = Set.of(
            "DiskTool", "DiskAnalyzeTool", "DiskInsightTool", "ProcessTool", "SystemLoadTool", "LogAnalysisTool",
            "CleanTempTool", "ConfigCheckTool", "NetworkTool", "PrivilegeTool", "OsInsightTool");

    private static final int MIN_DELAY_MIN = 1;
    private static final int MAX_DELAY_MIN = 24 * 60;

    private final ThreadPoolTaskScheduler scheduler;
    private final McpExecuteController mcpExecuteController;

    private final ConcurrentHashMap<String, ScheduledFuture<?>> futures = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, TaskMeta> metas = new ConcurrentHashMap<>();

    public OpsDeferredTaskService(
            @Qualifier("opsDeferredTaskScheduler") ThreadPoolTaskScheduler scheduler,
            McpExecuteController mcpExecuteController) {
        this.scheduler = scheduler;
        this.mcpExecuteController = mcpExecuteController;
    }

    public Map<String, Object> schedule(int delayMinutes, String toolName, Map<String, Object> parameters,
                                        String operatorId) {
        validate(delayMinutes, toolName, parameters);

        String taskId = UUID.randomUUID().toString();
        Instant executeAt = Instant.now().plus(delayMinutes, ChronoUnit.MINUTES);
        Map<String, Object> paramCopy = parameters == null ? new HashMap<>() : new HashMap<>(parameters);

        TaskMeta meta = new TaskMeta(taskId, toolName, executeAt, paramCopy,
                operatorId == null ? "" : operatorId, "PENDING");
        metas.put(taskId, meta);

        ScheduledFuture<?> future = scheduler.schedule(() -> runTask(taskId), executeAt);
        futures.put(taskId, future);

        log.info("Created deferred ops task taskId={} tool={} executeAt={} delayMin={} operator={}",
                taskId, toolName, executeAt, delayMinutes, operatorId);

        return toView(meta);
    }

    public boolean cancel(String taskId, String operatorId, boolean admin) {
        TaskMeta meta = metas.get(taskId);
        if (meta == null || (!admin && !sameOwner(meta.createdBy, operatorId))) {
            return false;
        }
        ScheduledFuture<?> future = futures.get(taskId);
        if (future == null) {
            return false;
        }
        boolean cancelled = future.cancel(false);
        futures.remove(taskId);
        metas.remove(taskId);
        log.info("Cancelled deferred ops task taskId={} cancelled={}", taskId, cancelled);
        return cancelled;
    }

    public List<Map<String, Object>> listPending(String operatorId, boolean admin) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (TaskMeta meta : metas.values()) {
            if (!"PENDING".equals(meta.status)) {
                continue;
            }
            if (admin || sameOwner(meta.createdBy, operatorId)) {
                list.add(toView(meta));
            }
        }
        list.sort((a, b) -> String.valueOf(a.get("executeAt")).compareTo(String.valueOf(b.get("executeAt"))));
        return list;
    }

    private void validate(int delayMinutes, String toolName, Map<String, Object> parameters) {
        if (delayMinutes < MIN_DELAY_MIN || delayMinutes > MAX_DELAY_MIN) {
            throw new IllegalArgumentException("delayMinutes must be between " + MIN_DELAY_MIN + " and " + MAX_DELAY_MIN);
        }
        if (toolName == null || toolName.isBlank() || !ALLOWED_DEFERRED_TOOLS.contains(toolName)) {
            throw new IllegalArgumentException("Unsupported deferred tool: " + toolName);
        }
        if ("CleanTempTool".equals(toolName)) {
            Boolean dryRun = getBoolean(parameters, "dryRun");
            Boolean confirmDelete = getBoolean(parameters, "confirmDelete");
            if (Boolean.FALSE.equals(dryRun) && !Boolean.TRUE.equals(confirmDelete)) {
                throw new IllegalArgumentException("Real delete requires dryRun=false and confirmDelete=true");
            }
        }
    }

    private void runTask(String taskId) {
        TaskMeta meta = metas.get(taskId);
        if (meta == null) {
            return;
        }
        meta.status = "RUNNING";
        try {
            log.info("Running deferred ops task taskId={} tool={}", taskId, meta.toolName);
            Map<String, Object> exec = mcpExecuteController.executeDeferredScheduledTool(meta.toolName, meta.parameters);
            meta.status = Boolean.TRUE.equals(exec.get("success")) ? "DONE" : "FAILED";
            log.info("Finished deferred ops task taskId={} success={}", taskId, exec.get("success"));
        } catch (Exception e) {
            log.error("Deferred ops task failed taskId={}", taskId, e);
            meta.status = "FAILED";
        } finally {
            futures.remove(taskId);
            metas.remove(taskId);
        }
    }

    private Map<String, Object> toView(TaskMeta meta) {
        Map<String, Object> map = new HashMap<>();
        map.put("taskId", meta.taskId);
        map.put("toolName", meta.toolName);
        map.put("executeAt", meta.executeAt.toString());
        map.put("status", meta.status);
        map.put("parameters", Collections.unmodifiableMap(new HashMap<>(meta.parameters)));
        map.put("createdBy", meta.createdBy);
        return map;
    }

    private static Boolean getBoolean(Map<String, Object> params, String key) {
        if (params == null || !params.containsKey(key)) {
            return null;
        }
        Object value = params.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        return Boolean.parseBoolean(value.toString());
    }

    private static boolean sameOwner(String expected, String actual) {
        return expected != null && !expected.isBlank() && expected.equals(actual);
    }

    private static final class TaskMeta {
        final String taskId;
        final String toolName;
        final Instant executeAt;
        final Map<String, Object> parameters;
        final String createdBy;
        volatile String status;

        TaskMeta(String taskId, String toolName, Instant executeAt, Map<String, Object> parameters,
                 String createdBy, String status) {
            this.taskId = taskId;
            this.toolName = toolName;
            this.executeAt = executeAt;
            this.parameters = parameters;
            this.createdBy = createdBy;
            this.status = status;
        }
    }
}
