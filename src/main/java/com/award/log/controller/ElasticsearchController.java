package com.award.log.controller;

import com.award.log.common.Result;
import com.award.log.model.LogDocument;
import com.award.log.security.RequestUserResolver;
import com.award.log.service.ElasticsearchService;
import com.award.log.task.AnalysisTaskManager;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;

@Slf4j
@Deprecated(since = "delivery-2026-07", forRemoval = false)
@Tag(name = "Elasticsearch", description = "非默认交付面：无挂载 UI / 仅 API，见 docs/deployment/交付API白名单.md")
@RestController
@RequestMapping("/api/elasticsearch")
@RequiredArgsConstructor
public class ElasticsearchController {

    @Autowired(required = false)
    private ElasticsearchService elasticsearchService;

    private final RequestUserResolver requestUserResolver;
    private final AnalysisTaskManager analysisTaskManager;

    @PostMapping("/index")
    public Result<LogDocument> indexLog(HttpServletRequest request, @RequestBody LogDocument logDocument) {
        if (!requireAdmin(request)) {
            return Result.error(403, "Only administrators can index Elasticsearch data");
        }
        if (elasticsearchService == null) {
            return unavailable();
        }
        try {
            return Result.success(elasticsearchService.indexLog(logDocument), "Indexed successfully");
        } catch (Exception e) {
            log.error("Failed to index Elasticsearch log", e);
            return Result.error("Index failed");
        }
    }

    @PostMapping("/bulk-index")
    public Result<Integer> bulkIndexLogs(HttpServletRequest request, @RequestBody List<LogDocument> logDocuments) {
        if (!requireAdmin(request)) {
            return Result.error(403, "Only administrators can bulk index Elasticsearch data");
        }
        if (elasticsearchService == null) {
            return unavailable();
        }
        try {
            return Result.success(elasticsearchService.bulkIndexLogs(logDocuments), "Indexed successfully");
        } catch (Exception e) {
            log.error("Failed to bulk index Elasticsearch logs", e);
            return Result.error("Bulk index failed");
        }
    }

    @GetMapping("/logs")
    public Result<Page<LogDocument>> getLogs(HttpServletRequest request,
                                             @RequestParam(defaultValue = "1") int pageNum,
                                             @RequestParam(defaultValue = "10") int pageSize) {
        if (!requireAdmin(request)) {
            return Result.error(403, "Only administrators can browse Elasticsearch logs");
        }
        if (elasticsearchService == null) {
            return unavailable();
        }
        try {
            Pageable pageable = PageRequest.of(Math.max(pageNum, 1) - 1, Math.max(pageSize, 1));
            return Result.success(elasticsearchService.searchLogs("", pageable), "Loaded successfully");
        } catch (Exception e) {
            log.error("Failed to load Elasticsearch logs", e);
            return Result.error("Load failed");
        }
    }

    @GetMapping("/get/{id}")
    public Result<LogDocument> getLogById(HttpServletRequest request, @PathVariable String id) {
        if (elasticsearchService == null) {
            return unavailable();
        }
        try {
            LogDocument result = elasticsearchService.getLogById(id);
            if (result == null) {
                return Result.error(404, "Log not found");
            }
            if (!requestUserResolver.isAdmin(request) && !canAccessTask(request, result.getTaskId())) {
                return Result.error(404, "Log not found or access denied");
            }
            return Result.success(result, "Loaded successfully");
        } catch (Exception e) {
            log.error("Failed to load Elasticsearch log {}", id, e);
            return Result.error("Load failed");
        }
    }

    @GetMapping("/logs/{id}")
    public Result<LogDocument> getLogByIdLegacyPath(HttpServletRequest request, @PathVariable String id) {
        return getLogById(request, id);
    }

    @GetMapping("/search")
    public Result<Page<LogDocument>> searchLogs(HttpServletRequest request,
                                                @RequestParam(required = false, defaultValue = "") String query,
                                                @RequestParam(required = false) String severity,
                                                @RequestParam(required = false) Boolean anomaly,
                                                @RequestParam(required = false) String startTime,
                                                @RequestParam(required = false) String endTime,
                                                @RequestParam(defaultValue = "1") int pageNum,
                                                @RequestParam(defaultValue = "10") int pageSize) {
        if (!requireAdmin(request)) {
            return Result.error(403, "Only administrators can search Elasticsearch logs");
        }
        if (elasticsearchService == null) {
            return unavailable();
        }
        try {
            Pageable pageable = PageRequest.of(Math.max(pageNum, 1) - 1, Math.max(pageSize, 1));
            LocalDateTime start = blankToNull(startTime) == null ? null : LocalDateTime.parse(startTime);
            LocalDateTime end = blankToNull(endTime) == null ? null : LocalDateTime.parse(endTime);
            Page<LogDocument> result = elasticsearchService.searchLogs(query, severity, anomaly, start, end, pageable);
            return Result.success(result, "Search completed");
        } catch (DateTimeParseException e) {
            return Result.error("Invalid date time format");
        } catch (Exception e) {
            log.error("Failed to search Elasticsearch logs", e);
            return Result.error("Search failed");
        }
    }

    @GetMapping("/time-range")
    public Result<Page<LogDocument>> getLogsByTimeRange(HttpServletRequest request,
                                                        @RequestParam String startTime,
                                                        @RequestParam String endTime,
                                                        @RequestParam(defaultValue = "0") int page,
                                                        @RequestParam(defaultValue = "10") int size) {
        if (!requireAdmin(request)) {
            return Result.error(403, "Only administrators can query Elasticsearch logs by time range");
        }
        if (elasticsearchService == null) {
            return unavailable();
        }
        try {
            LocalDateTime start = LocalDateTime.parse(startTime);
            LocalDateTime end = LocalDateTime.parse(endTime);
            Pageable pageable = PageRequest.of(Math.max(page, 0), Math.max(size, 1));
            return Result.success(elasticsearchService.getLogsByTimeRange(start, end, pageable), "Loaded successfully");
        } catch (DateTimeParseException e) {
            return Result.error("Invalid date time format");
        } catch (Exception e) {
            log.error("Failed to query Elasticsearch logs by time range", e);
            return Result.error("Load failed");
        }
    }

    @GetMapping("/severity")
    public Result<Page<LogDocument>> getLogsBySeverity(HttpServletRequest request,
                                                       @RequestParam String severity,
                                                       @RequestParam(defaultValue = "0") int page,
                                                       @RequestParam(defaultValue = "10") int size) {
        if (!requireAdmin(request)) {
            return Result.error(403, "Only administrators can query Elasticsearch logs by severity");
        }
        if (elasticsearchService == null) {
            return unavailable();
        }
        try {
            Pageable pageable = PageRequest.of(Math.max(page, 0), Math.max(size, 1));
            return Result.success(elasticsearchService.getLogsBySeverity(severity, pageable), "Loaded successfully");
        } catch (Exception e) {
            log.error("Failed to query Elasticsearch logs by severity", e);
            return Result.error("Load failed");
        }
    }

    @GetMapping("/task/{taskId}")
    public Result<Page<LogDocument>> getLogsByTaskId(HttpServletRequest request,
                                                     @PathVariable String taskId,
                                                     @RequestParam(defaultValue = "0") int page,
                                                     @RequestParam(defaultValue = "10") int size) {
        if (!canAccessTask(request, taskId)) {
            return Result.error(404, "Task not found or access denied");
        }
        if (elasticsearchService == null) {
            return unavailable();
        }
        try {
            Pageable pageable = PageRequest.of(Math.max(page, 0), Math.max(size, 1));
            return Result.success(elasticsearchService.getLogsByTaskId(taskId, pageable), "Loaded successfully");
        } catch (Exception e) {
            log.error("Failed to query Elasticsearch logs for task {}", taskId, e);
            return Result.error("Load failed");
        }
    }

    @GetMapping("/anomaly")
    public Result<Page<LogDocument>> getAnomalyLogs(HttpServletRequest request,
                                                    @RequestParam(defaultValue = "0") int page,
                                                    @RequestParam(defaultValue = "10") int size) {
        if (!requireAdmin(request)) {
            return Result.error(403, "Only administrators can query Elasticsearch anomaly logs");
        }
        if (elasticsearchService == null) {
            return unavailable();
        }
        try {
            Pageable pageable = PageRequest.of(Math.max(page, 0), Math.max(size, 1));
            return Result.success(elasticsearchService.getAnomalyLogs(pageable), "Loaded successfully");
        } catch (Exception e) {
            log.error("Failed to query Elasticsearch anomaly logs", e);
            return Result.error("Load failed");
        }
    }

    @DeleteMapping("/delete/before")
    public Result<Long> deleteLogsBefore(HttpServletRequest request, @RequestParam String beforeTime) {
        if (!requireAdmin(request)) {
            return Result.error(403, "Only administrators can delete Elasticsearch logs by time range");
        }
        if (elasticsearchService == null) {
            return unavailable();
        }
        try {
            LocalDateTime time = LocalDateTime.parse(beforeTime);
            return Result.success(elasticsearchService.deleteLogsBefore(time), "Deleted successfully");
        } catch (DateTimeParseException e) {
            return Result.error("Invalid date time format");
        } catch (Exception e) {
            log.error("Failed to delete Elasticsearch logs", e);
            return Result.error("Delete failed");
        }
    }

    @DeleteMapping("/delete/task/{taskId}")
    public Result<Long> deleteLogsByTaskId(HttpServletRequest request, @PathVariable String taskId) {
        if (!canAccessTask(request, taskId)) {
            return Result.error(404, "Task not found or access denied");
        }
        if (elasticsearchService == null) {
            return unavailable();
        }
        try {
            return Result.success(elasticsearchService.deleteLogsByTaskId(taskId), "Deleted successfully");
        } catch (Exception e) {
            log.error("Failed to delete Elasticsearch logs for task {}", taskId, e);
            return Result.error("Delete failed");
        }
    }

    private Result unavailable() {
        return Result.error(503, "Elasticsearch is disabled");
    }

    private boolean requireAdmin(HttpServletRequest request) {
        return requestUserResolver.isAdmin(request);
    }

    private boolean canAccessTask(HttpServletRequest request, String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return false;
        }
        Integer userId = requestUserResolver.currentUserId(request);
        boolean admin = requestUserResolver.isAdmin(request);
        return analysisTaskManager.canAccessTask(taskId, userId, admin);
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
