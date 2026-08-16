package com.award.log.mcp.tools;

import com.award.log.config.OpsRemediationProperties;
import com.award.log.mcp.AbstractCommandExecutor;
import com.award.log.mcp.LogSafetyClassifier;
import com.award.log.mcp.McpToolResponses;
import com.award.log.mcp.MinPrivilegeExecutor;
import com.award.log.security.ChatWriteExecutionPolicy;
import com.award.log.security.OpsPathPolicy;
import com.award.log.util.OsRuntime;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 日志目录陈旧文件清理（写操作）：默认 dry-run；真实删除需 dryRun=false 且 confirmDelete=true。
 * 路径仅限 {@link OpsPathPolicy#isAllowedLogCleanupPath}（默认 /var/log 树下）。
 */
@Slf4j
@Component
public class LogCleanupTool extends AbstractCommandExecutor {

    private final ObjectMapper objectMapper;
    private final MinPrivilegeExecutor minPrivilegeExecutor;
    private final OpsPathPolicy opsPathPolicy;
    private final LogSafetyClassifier logSafetyClassifier;
    private final OpsRemediationProperties remediationProperties;

    @Autowired
    public LogCleanupTool(
            @Autowired(required = false) ExecutorService mcpToolExecutor,
            ObjectMapper objectMapper,
            MinPrivilegeExecutor minPrivilegeExecutor,
            OpsPathPolicy opsPathPolicy,
            LogSafetyClassifier logSafetyClassifier,
            OpsRemediationProperties remediationProperties) {
        super(mcpToolExecutor != null ? mcpToolExecutor : Executors.newCachedThreadPool());
        this.objectMapper = objectMapper;
        this.minPrivilegeExecutor = minPrivilegeExecutor;
        this.opsPathPolicy = opsPathPolicy;
        this.logSafetyClassifier = logSafetyClassifier;
        this.remediationProperties = remediationProperties != null
                ? remediationProperties
                : new OpsRemediationProperties();
    }

    @Tool(name = "cleanupOldLogs",
            description = "清理日志根目录下过期的日志文件（默认仅预览）。路径受限（如 /var/log）；删除需 dryRun=false 且 confirmDelete=true。")
    public String cleanupOldLogs(
            @ToolParam(description = "日志根路径，默认 Linux /var/log", required = false) String path,
            @ToolParam(description = "删除多少天未修改的文件，默认 30，最大 3650", required = false) Integer days,
            @ToolParam(description = "true/null=仅预览；false=允许删除（须 confirmDelete=true）", required = false) Boolean dryRun,
            @ToolParam(description = "必须为 true 才会在 dryRun=false 时执行 rm", required = false) Boolean confirmDelete
    ) throws JsonProcessingException {
        long startTime = System.currentTimeMillis();
        String targetPath = path != null && !path.isBlank() ? path.trim()
                : (OsRuntime.isWindows() ? "C:\\Windows\\Logs" : "/var/log");
        int defaultDays = days != null ? Math.min(Math.max(days, 1), 3650) : 30;
        ChatWriteExecutionPolicy.ResolvedWrite write = ChatWriteExecutionPolicy.resolve(
                opsPathPolicy, targetPath, defaultDays, dryRun, confirmDelete, false);
        int targetDays = Math.max(1, Math.min(write.days(), 3650));
        boolean isDryRun = write.dryRun();
        boolean confirmDeleteResolved = write.confirmDelete();

        log.info("[LogCleanup] path={} days={} dryRun={}", targetPath, targetDays, isDryRun);

        try {
            if (!opsPathPolicy.isAllowedLogCleanupPath(targetPath)) {
                return buildErrorResponse(opsPathPolicy.rejectReason("日志清理路径不在白名单"), startTime);
            }
            if (!isDryRun && !confirmDeleteResolved) {
                return buildErrorResponse(
                        "安全策略：实际删除需 dryRun=false 且 confirmDelete=true（或通过 MCP 二次确认后执行）",
                        startTime);
            }

            if (!isDryRun) {
                String skip = quickWriteSkipReason(targetPath);
                if (skip != null) {
                    long duration = System.currentTimeMillis() - startTime;
                    String data = String.format(
                            "{\"mode\":\"SKIP\",\"path\":\"%s\",\"filesFound\":0,\"filesDeleted\":0,\"message\":\"%s\"}",
                            jsonEscape(targetPath), jsonEscape(skip));
                    log.warn("日志清理快速跳过: path={}, reason={}, durationMs={}", targetPath, skip, duration);
                    return buildWarnResponse(data, duration);
                }
            }

            if (OsRuntime.isWindows()) {
                return cleanupLogsWindows(targetPath, targetDays, isDryRun, startTime);
            }

            AbstractCommandExecutor.CommandResult result = minPrivilegeExecutor.executeSafely(
                    List.of("find", targetPath, "-maxdepth", String.valueOf(Math.max(1, remediationProperties.getMaxScanDepth())),
                            "-type", "f", "(", "-name", "*.log", "-o", "-name", "*.log.*", "-o",
                            "-name", "*.gz", "-o", "-name", "*.xz", "-o", "-name", "*.bz2", ")", "-mtime",
                            "+" + targetDays, "-print"),
                    120_000L);

            if (result.output() == null || result.output().isBlank()) {
                String data = String.format(
                        "{\"mode\":\"%s\",\"path\":\"%s\",\"days\":%d,\"filesFound\":0,\"filesDeleted\":0,\"message\":\"没有符合条件的陈旧日志文件\"}",
                        isDryRun ? "DRY-RUN" : "NOOP", jsonEscape(targetPath), targetDays);
                return finishZeroDeleteResponse(data, targetPath, startTime);
            }

            List<String> fileList = new ArrayList<>();
            for (String line : result.output().split("\n")) {
                if (!line.isBlank()) {
                    fileList.add(line.trim());
                }
            }

            LogSafetyClassifier.FilterResult filtered = logSafetyClassifier.filterDeletable(fileList);
            List<String> deletable = filtered.allowed();
            int protectedSkipped = fileList.size() - deletable.size();

            int filesDeleted = 0;
            int lockedSkipped = 0;
            List<String> previewFiles = new ArrayList<>();
            if (!isDryRun) {
                for (String file : deletable) {
                    if (remediationProperties.isSkipLockedFiles() && isFileLocked(file)) {
                        lockedSkipped++;
                        continue;
                    }
                    AbstractCommandExecutor.CommandResult delResult = minPrivilegeExecutor.executeSafely(List.of("rm", "-f", file));
                    if (delResult.success() && !isSimulatedDryRun(delResult)) {
                        filesDeleted++;
                    }
                }
            }

            int previewCount = Math.min(deletable.size(), 25);
            for (int i = 0; i < previewCount; i++) {
                previewFiles.add(deletable.get(i));
            }

            int maxDepth = Math.max(1, remediationProperties.getMaxScanDepth());
            String data;
            if (isDryRun) {
                data = String.format(
                        "{\"mode\":\"DRY-RUN\",\"path\":\"%s\",\"days\":%d,\"filesFound\":%d,\"deletableCount\":%d,\"protectedSkipped\":%d,\"previewCount\":%d,\"preview\":%s,\"protectedSamples\":%s,\"maxScanDepth\":%d,\"note\":\"仅列出可删除候选；受保护文件已排除。真实删除：dryRun=false 且 confirmDelete=true\"}",
                        jsonEscape(targetPath), targetDays, fileList.size(), deletable.size(), protectedSkipped,
                        previewCount, toJsonArray(previewFiles), toJsonArray(truncateDenied(filtered.deniedReasons(), 8)), maxDepth);
            } else {
                if (filesDeleted == 0 && !deletable.isEmpty() && lockedSkipped < deletable.size()) {
                    return buildErrorResponse(
                            "未删除任何日志文件（可能全局演练模式仍开启、路径无写权限或文件被占用）", startTime);
                }
                data = String.format(
                        "{\"mode\":\"DELETE\",\"path\":\"%s\",\"days\":%d,\"filesFound\":%d,\"deletableCount\":%d,\"protectedSkipped\":%d,\"filesDeleted\":%d,\"lockedSkipped\":%d,\"maxScanDepth\":%d}",
                        jsonEscape(targetPath), targetDays, fileList.size(), deletable.size(), protectedSkipped, filesDeleted, lockedSkipped, maxDepth);
                if (filesDeleted == 0) {
                    return finishZeroDeleteResponse(data, targetPath, startTime);
                }
            }
            return buildSuccessResponse(data, System.currentTimeMillis() - startTime, false);
        } catch (Exception e) {
            log.error("[LogCleanup] 异常", e);
            return buildErrorResponse("日志清理失败: " + e.getMessage(), startTime);
        }
    }

    private String cleanupLogsWindows(String targetPath, int targetDays, boolean isDryRun, long startTime)
            throws JsonProcessingException {
        String safe = targetPath.replace("'", "''");
        int maxDepth = Math.max(1, remediationProperties.getMaxScanDepth());
        String ps = "$p='" + safe + "';$d=" + targetDays
                + ";Get-ChildItem -LiteralPath $p -Recurse -Depth " + maxDepth
                + " -File -ErrorAction SilentlyContinue"
                + "|Where-Object{$_.LastWriteTime -lt (Get-Date).AddDays(-$d) -and ($_.Extension -match '\\.log|gz|xz|bz2' -or $_.Name -match '\\.log\\.')}"
                + "|Select-Object -ExpandProperty FullName|ConvertTo-Json -Compress";
        AbstractCommandExecutor.CommandResult result = minPrivilegeExecutor.executeSafely(
                List.of("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", ps), 120_000L);
        List<String> fileList = new ArrayList<>();
        if (result.success() && result.output() != null && !result.output().isBlank()) {
            try {
                JsonNode node = objectMapper.readTree(result.output());
                if (node.isArray()) {
                    for (JsonNode n : node) {
                        fileList.add(n.asText());
                    }
                } else {
                    fileList.add(node.asText());
                }
            } catch (Exception e) {
                log.warn("解析 Windows 日志清理列表失败: {}", e.getMessage());
            }
        }
        if (fileList.isEmpty()) {
            String data = String.format(
                    "{\"mode\":\"%s\",\"path\":\"%s\",\"days\":%d,\"filesFound\":0,\"filesDeleted\":0,\"maxScanDepth\":%d,\"platform\":\"windows\"}",
                    isDryRun ? "DRY-RUN" : "NOOP", jsonEscape(targetPath), targetDays, maxDepth);
            return finishZeroDeleteResponse(data, targetPath, startTime);
        }
        LogSafetyClassifier.FilterResult filtered = logSafetyClassifier.filterDeletable(fileList);
        List<String> deletable = filtered.allowed();
        int protectedSkipped = fileList.size() - deletable.size();

        int filesDeleted = 0;
        int lockedSkipped = 0;
        List<String> previewFiles = new ArrayList<>();
        if (!isDryRun) {
            for (String file : deletable) {
                if (remediationProperties.isSkipLockedFiles() && isFileLocked(file)) {
                    lockedSkipped++;
                    continue;
                }
                String f = file.replace("'", "''");
                AbstractCommandExecutor.CommandResult delResult = minPrivilegeExecutor.executeSafely(List.of(
                        "powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command",
                        "Remove-Item -LiteralPath '" + f + "' -Force -ErrorAction SilentlyContinue"));
                if (delResult.success() && !isSimulatedDryRun(delResult)) {
                    filesDeleted++;
                }
            }
        }
        int previewCount = Math.min(deletable.size(), 25);
        for (int i = 0; i < previewCount; i++) {
            previewFiles.add(deletable.get(i));
        }
        String data;
        if (isDryRun) {
            data = String.format(
                    "{\"mode\":\"DRY-RUN\",\"path\":\"%s\",\"days\":%d,\"filesFound\":%d,\"deletableCount\":%d,\"protectedSkipped\":%d,\"previewCount\":%d,\"preview\":%s,\"protectedSamples\":%s,\"maxScanDepth\":%d,\"platform\":\"windows\"}",
                    jsonEscape(targetPath), targetDays, fileList.size(), deletable.size(), protectedSkipped,
                    previewCount, toJsonArray(previewFiles), toJsonArray(truncateDenied(filtered.deniedReasons(), 8)), maxDepth);
        } else {
            if (filesDeleted == 0 && !deletable.isEmpty() && lockedSkipped < deletable.size()) {
                return buildErrorResponse(
                        "未删除任何日志文件（可能全局演练模式仍开启、路径无写权限或文件被占用）", startTime);
            }
            data = String.format(
                    "{\"mode\":\"DELETE\",\"path\":\"%s\",\"days\":%d,\"filesFound\":%d,\"deletableCount\":%d,\"protectedSkipped\":%d,\"filesDeleted\":%d,\"lockedSkipped\":%d,\"maxScanDepth\":%d,\"platform\":\"windows\"}",
                    jsonEscape(targetPath), targetDays, fileList.size(), deletable.size(), protectedSkipped, filesDeleted, lockedSkipped, maxDepth);
            if (filesDeleted == 0) {
                return finishZeroDeleteResponse(data, targetPath, startTime);
            }
        }
        return buildSuccessResponse(data, System.currentTimeMillis() - startTime, false);
    }

    private boolean isFileLocked(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return false;
        }
        Path path = Path.of(filePath);
        if (!Files.isRegularFile(path)) {
            return false;
        }
        try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "rw");
             FileChannel channel = raf.getChannel()) {
            FileLock lock = channel.tryLock();
            if (lock == null) {
                return true;
            }
            lock.release();
            return false;
        } catch (Exception e) {
            return true;
        }
    }

    /**
     * 目标目录存在、删除数为 0、耗时 &gt;1000ms → WARN（避免假 SUCCESS）。
     */
    private String finishZeroDeleteResponse(String dataJson, String targetPath, long startTime)
            throws JsonProcessingException {
        long duration = System.currentTimeMillis() - startTime;
        if (targetDirectoryExists(targetPath) && duration > 1000L) {
            String enriched = enrichZeroDeleteWarnPayload(dataJson, duration);
            log.warn("日志清理零删除告警: path={}, durationMs={}", targetPath, duration);
            return buildWarnResponse(enriched, duration);
        }
        return buildSuccessResponse(dataJson, duration, false);
    }

    private static boolean targetDirectoryExists(String targetPath) {
        if (targetPath == null || targetPath.isBlank()) {
            return false;
        }
        try {
            Path p = Path.of(targetPath);
            return Files.exists(p) && Files.isDirectory(p);
        } catch (Exception e) {
            return false;
        }
    }

    private String enrichZeroDeleteWarnPayload(String dataJson, long durationMs) {
        try {
            JsonNode node = objectMapper.readTree(dataJson);
            if (node != null && node.isObject()) {
                com.fasterxml.jackson.databind.node.ObjectNode obj =
                        (com.fasterxml.jackson.databind.node.ObjectNode) node;
                obj.put("status", "WARN");
                obj.put("durationMs", durationMs);
                if (!obj.has("message") || obj.path("message").asText("").isBlank()) {
                    obj.put("message", "目录存在但删除数量为 0，且耗时超过 1000ms");
                } else {
                    obj.put("message", obj.path("message").asText()
                            + "（目录存在且耗时超过 1000ms，结果为 WARN）");
                }
                return objectMapper.writeValueAsString(obj);
            }
        } catch (Exception ignored) {
            // fall through
        }
        return dataJson;
    }

    private boolean isSimulatedDryRun(AbstractCommandExecutor.CommandResult result) {
        return result != null && result.output() != null && result.output().contains("[DRY-RUN]");
    }

    /** 真删前快速探针：无写权限则 SKIP，避免长时间扫描后再失败。 */
    private String quickWriteSkipReason(String targetPath) {
        try {
            Path dir = Path.of(targetPath);
            if (!Files.exists(dir) || !Files.isDirectory(dir)) {
                return "SKIP: 目标目录不存在";
            }
            if (!Files.isWritable(dir)) {
                return "SKIP: 无写权限";
            }
            Path probe = dir.resolve(".threshcore-wprobe-" + System.nanoTime() + ".tmp");
            try {
                Files.writeString(probe, "w");
                Files.deleteIfExists(probe);
            } catch (Exception e) {
                return "SKIP: 无写权限";
            }
            return null;
        } catch (Exception e) {
            return "SKIP: 无写权限";
        }
    }

    private static String jsonEscape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String toJsonArray(List<String> list) throws JsonProcessingException {
        return objectMapper.writeValueAsString(list);
    }

    private static List<String> truncateDenied(List<String> denied, int max) {
        if (denied == null || denied.isEmpty()) {
            return List.of();
        }
        return denied.size() <= max ? denied : denied.subList(0, max);
    }

    private String buildSuccessResponse(String data, long duration, boolean cacheHit) throws JsonProcessingException {
        return McpToolResponses.success(objectMapper, data, duration);
    }

    private String buildWarnResponse(String data, long duration) throws JsonProcessingException {
        return McpToolResponses.warn(objectMapper, data, duration);
    }

    private String buildErrorResponse(String error, long duration) throws JsonProcessingException {
        return McpToolResponses.error(objectMapper, error, duration);
    }
}
