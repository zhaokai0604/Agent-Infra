package com.award.log.controller;

import com.award.log.common.PageResult;
import com.award.log.common.Result;
import com.award.log.dto.EnhancedLogParseResultEntity;
import com.award.log.security.RequestUserResolver;
import com.award.log.service.AiDiagnosisService;
import com.award.log.service.LogAnalysisService;
import com.award.log.task.AnalysisTaskManager;
import com.award.log.task.TaskInfo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@Tag(name = "Log Analysis Async")
@RestController
@RequestMapping("/log")
public class LogAnalysisController {

    @Autowired
    private LogAnalysisService logAnalysisService;

    @Autowired
    private AiDiagnosisService aiDiagnosisService;

    @Autowired
    private AnalysisTaskManager taskManager;

    @Autowired
    private RequestUserResolver requestUserResolver;

    @Operation(summary = "Upload log file")
    @PostMapping("/upload")
    public Result<String> uploadLog(HttpServletRequest request, @RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            return Result.error("File cannot be empty");
        }
        Integer userId = requestUserResolver.currentUserId(request);
        String taskId = UUID.randomUUID().toString();
        try {
            File destFile = createSafeTempUpload(taskId, file);
            taskManager.initTask(taskId, userId, safeOriginalFilename(file));
            logAnalysisService.startAnalysisAsync(destFile, taskId);
            return Result.success(taskId);
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        } catch (Exception e) {
            log.error("Upload failed for task {}", taskId, e);
            return Result.error("Upload failed");
        }
    }

    @Operation(summary = "Upload multiple log files")
    @PostMapping("/upload/multi")
    public Result<List<String>> uploadLogs(HttpServletRequest request, @RequestParam("files") MultipartFile[] files) {
        if (files == null || files.length == 0) {
            return Result.error("Files cannot be empty");
        }
        Integer userId = requestUserResolver.currentUserId(request);
        List<String> taskIds = new ArrayList<>();
        try {
            for (MultipartFile file : files) {
                if (file.isEmpty()) {
                    continue;
                }
                String taskId = UUID.randomUUID().toString();
                File destFile = createSafeTempUpload(taskId, file);
                taskManager.initTask(taskId, userId, safeOriginalFilename(file));
                logAnalysisService.startAnalysisAsync(destFile, taskId);
                taskIds.add(taskId);
            }
            return Result.success(taskIds);
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        } catch (Exception e) {
            log.error("Batch upload failed", e);
            return Result.error("Upload failed");
        }
    }

    @Operation(summary = "Get task progress")
    @GetMapping("/task/{taskId}")
    public Result<TaskInfo> getTaskStatus(HttpServletRequest request, @PathVariable String taskId) {
        TaskInfo task = resolveOwnedTask(request, taskId);
        if (task == null) {
            return Result.error(404, "Task not found or access denied");
        }
        return Result.success(task);
    }

    @Operation(summary = "Get task history")
    @GetMapping("/history")
    public Result<PageResult<TaskInfo>> getHistory(HttpServletRequest request,
                                                   @RequestParam(defaultValue = "1") int pageNum,
                                                   @RequestParam(defaultValue = "10") int pageSize,
                                                   @RequestParam(required = false) String fileName,
                                                   @RequestParam(required = false) String status,
                                                   @RequestParam(required = false) String startTime,
                                                   @RequestParam(required = false) String endTime) {
        Integer userId = requestUserResolver.currentUserId(request);
        boolean admin = requestUserResolver.isAdmin(request);
        return Result.success(taskManager.getTasksPageForUser(
                userId, admin, pageNum, pageSize, fileName, status, startTime, endTime));
    }

    @Operation(summary = "Get final task report")
    @GetMapping("/report/{taskId}")
    public Result<TaskInfo> getTaskReport(HttpServletRequest request, @PathVariable String taskId) {
        TaskInfo task = resolveOwnedTask(request, taskId);
        if (task == null) {
            return Result.error(404, "Task not found or access denied");
        }
        return Result.success(task);
    }

    @Operation(summary = "Paginated task details (big-data safe)")
    @GetMapping("/report/{taskId}/details")
    public Result<PageResult<EnhancedLogParseResultEntity>> getTaskReportDetails(
            HttpServletRequest request,
            @PathVariable String taskId,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "50") int pageSize,
            @RequestParam(defaultValue = "false") boolean anomalyOnly) {
        TaskInfo task = resolveOwnedTask(request, taskId);
        if (task == null) {
            return Result.error(404, "Task not found or access denied");
        }
        return Result.success(taskManager.getTaskDetailsPage(taskId, pageNum, pageSize, anomalyOnly));
    }

    @Operation(summary = "Download report file")
    @GetMapping("/download/{taskId}/{type}")
    public void downloadReport(HttpServletRequest request,
                               @PathVariable String taskId,
                               @PathVariable String type,
                               HttpServletResponse response) {
        try {
            TaskInfo task = resolveOwnedTask(request, taskId);
            if (task == null) {
                response.sendError(404, "Task not found or access denied");
                return;
            }
            boolean csv = "csv".equalsIgnoreCase(type);
            boolean html = "html".equalsIgnoreCase(type);
            if (!csv && !html) {
                response.sendError(400, "Unsupported report type");
                return;
            }
            String fileName = csv ? "log_analysis_result.csv" : "log_analysis_report.html";
            File file = Paths.get(System.getProperty("user.dir"), "target", "output", taskId, fileName).toFile();
            if (!file.exists() && "COMPLETED".equalsIgnoreCase(task.getStatus())) {
                List<EnhancedLogParseResultEntity> reload = taskManager.loadDetailsForExport(taskId);
                if (reload != null && !reload.isEmpty()) {
                    logAnalysisService.ensureReportArtifacts(taskId, reload);
                    file = Paths.get(System.getProperty("user.dir"), "target", "output", taskId, fileName).toFile();
                }
            }
            if (!file.exists()) {
                response.sendError(404, "Report file not generated");
                return;
            }
            response.setContentType(csv ? "text/csv;charset=UTF-8" : "text/html;charset=UTF-8");
            response.setHeader("Content-Disposition", "attachment; filename=" + fileName);
            try (InputStream is = new FileInputStream(file); OutputStream os = response.getOutputStream()) {
                byte[] buffer = new byte[8192];
                int len;
                while ((len = is.read(buffer)) != -1) {
                    os.write(buffer, 0, len);
                }
                os.flush();
            }
        } catch (Exception e) {
            log.error("Download report failed for task {}", taskId, e);
            try {
                response.sendError(500, "Download failed");
            } catch (Exception ignored) {
            }
        }
    }

    @Operation(summary = "Run AI diagnosis")
    @PostMapping("/diagnose/{taskId}")
    public Result<String> performDiagnosis(HttpServletRequest request, @PathVariable String taskId) {
        try {
            TaskInfo task = resolveOwnedTask(request, taskId);
            if (task == null || task.getResult() == null) {
                return Result.error(404, "Task not found, access denied, or analysis not completed");
            }
            String diagnosis = aiDiagnosisService.generateDiagnosisFromFullResult(task.getResult());
            taskManager.updateAiDiagnosis(taskId, diagnosis);
            return Result.success(diagnosis);
        } catch (Exception e) {
            log.error("Diagnosis failed for task {}", taskId, e);
            return Result.error("Diagnosis failed");
        }
    }

    @Operation(summary = "Quick diagnose single log")
    @PostMapping("/quick-diagnose")
    public Result<String> quickDiagnose(@RequestBody EnhancedLogParseResultEntity logEntry) {
        try {
            return Result.success(aiDiagnosisService.diagnoseSingleLog(logEntry));
        } catch (Exception e) {
            log.error("Quick diagnose failed", e);
            return Result.error("Diagnosis failed");
        }
    }

    @Operation(summary = "Pause analysis task")
    @PostMapping("/pause/{taskId}")
    public Result<String> pauseTask(HttpServletRequest request, @PathVariable String taskId) {
        if (!canOperateTask(request, taskId)) {
            return Result.error(404, "Task not found or access denied");
        }
        try {
            logAnalysisService.pauseAnalysis(taskId);
            return Result.success("Task paused");
        } catch (Exception e) {
            log.error("Pause task failed for {}", taskId, e);
            return Result.error("Pause task failed");
        }
    }

    @Operation(summary = "Resume analysis task")
    @PostMapping("/resume/{taskId}")
    public Result<String> resumeTask(HttpServletRequest request, @PathVariable String taskId) {
        if (!canOperateTask(request, taskId)) {
            return Result.error(404, "Task not found or access denied");
        }
        try {
            logAnalysisService.resumeAnalysis(taskId);
            return Result.success("Task resumed");
        } catch (Exception e) {
            log.error("Resume task failed for {}", taskId, e);
            return Result.error("Resume task failed");
        }
    }

    @Operation(summary = "Cancel analysis task")
    @PostMapping("/cancel/{taskId}")
    public Result<String> cancelTask(HttpServletRequest request, @PathVariable String taskId) {
        if (!canOperateTask(request, taskId)) {
            return Result.error(404, "Task not found or access denied");
        }
        try {
            logAnalysisService.cancelAnalysis(taskId);
            return Result.success("Task cancelled");
        } catch (Exception e) {
            log.error("Cancel task failed for {}", taskId, e);
            return Result.error("Cancel task failed");
        }
    }

    @Operation(summary = "Delete analysis task")
    @DeleteMapping("/delete/{taskId}")
    public Result<Boolean> deleteTask(HttpServletRequest request, @PathVariable String taskId) {
        try {
            Integer userId = requestUserResolver.currentUserId(request);
            boolean admin = requestUserResolver.isAdmin(request);
            boolean success = taskManager.deleteTaskForUser(taskId, userId, admin);
            return success ? Result.success(true, "Task deleted") : Result.error(404, "Task not found or access denied");
        } catch (Exception e) {
            log.error("Delete task failed for {}", taskId, e);
            return Result.error("Delete task failed");
        }
    }

    private TaskInfo resolveOwnedTask(HttpServletRequest request, String taskId) {
        Integer userId = requestUserResolver.currentUserId(request);
        boolean admin = requestUserResolver.isAdmin(request);
        return taskManager.getTaskForUser(taskId, userId, admin);
    }

    private boolean canOperateTask(HttpServletRequest request, String taskId) {
        Integer userId = requestUserResolver.currentUserId(request);
        boolean admin = requestUserResolver.isAdmin(request);
        return taskManager.canAccessTask(taskId, userId, admin);
    }

    private File createSafeTempUpload(String taskId, MultipartFile file) throws Exception {
        String safeName = safeOriginalFilename(file);
        Path tempDir = Paths.get(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();
        Path destPath = tempDir.resolve(taskId + "_" + safeName).normalize();
        if (!destPath.startsWith(tempDir)) {
            throw new IllegalArgumentException("Illegal upload path");
        }
        File destFile = destPath.toFile();
        file.transferTo(destFile);
        return destFile;
    }

    private String safeOriginalFilename(MultipartFile file) {
        String original = file.getOriginalFilename();
        if (original == null || original.isBlank()) {
            return "upload.log";
        }
        String normalized = Paths.get(original).getFileName().toString();
        String sanitized = normalized.replaceAll("[\\r\\n\\\\/]+", "_");
        if (sanitized.contains("..")) {
            sanitized = sanitized.replace("..", "_");
        }
        if (sanitized.isBlank()) {
            return "upload.log";
        }
        return sanitized;
    }
}
