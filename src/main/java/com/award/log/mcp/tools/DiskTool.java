package com.award.log.mcp.tools;

import com.award.log.mcp.AbstractCommandExecutor;
import com.award.log.mcp.MinPrivilegeExecutor;
import com.award.log.mcp.McpToolsConfig;
import com.award.log.mcp.McpToolResponses;
import com.award.log.security.OpsPathPolicy;
import com.award.log.util.OsRuntime;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class DiskTool extends AbstractCommandExecutor {

    private static final Pattern DF_OUTPUT_PATTERN = Pattern.compile(
        "^([\\w/\\-\\.]+)\\s+([\\d.]+[KMGTPE]?)\\s+([\\d.]+[KMGTPE]?)\\s+([\\d.]+[KMGTPE]?)\\s+(\\d+%)\\s+(.+)$"
    );

    private final ObjectMapper objectMapper;
    private final MinPrivilegeExecutor minPrivilegeExecutor;
    private final OpsPathPolicy opsPathPolicy;

    @Autowired
    public DiskTool(
            @Autowired(required = false) ExecutorService mcpToolExecutor,
            ObjectMapper objectMapper,
            MinPrivilegeExecutor minPrivilegeExecutor,
            OpsPathPolicy opsPathPolicy) {
        super(mcpToolExecutor != null ? mcpToolExecutor : Executors.newCachedThreadPool());
        this.objectMapper = objectMapper;
        this.minPrivilegeExecutor = minPrivilegeExecutor;
        this.opsPathPolicy = opsPathPolicy;
    }

    @Tool(name = "checkDiskUsage", description = "执行 df -h 命令，检查磁盘使用情况，返回每个分区的文件系统、大小、已用、可用、使用率和挂载点信息")
    @Cacheable(value = McpToolsConfig.CACHE_DISK_USAGE, key = "'disk'", cacheManager = "mcpCacheManager")
    public String checkDiskUsage() throws JsonProcessingException {
        long startTime = System.currentTimeMillis();
        log.info("开始执行磁盘使用率检查");

        try {
            if (OsRuntime.isWindows()) {
                return checkDiskUsageWindows(startTime);
            }
            AbstractCommandExecutor.CommandResult result = minPrivilegeExecutor.executeSafely(List.of("df", "-h"));

            if (!result.success()) {
                String errorJson = buildErrorResponse("df 命令执行失败: " + result.error(), startTime);
                log.error("磁盘检查失败: {}", result.error());
                return errorJson;
            }

            List<Map<String, String>> diskInfoList = parseOutput(result.output());
            String dataJson = toJson(diskInfoList);
            long duration = System.currentTimeMillis() - startTime;

            log.info("磁盘使用率检查完成，共获取 {} 个分区信息，耗时: {}ms", diskInfoList.size(), duration);
            return buildSuccessResponse(dataJson, duration, false);

        } catch (Exception e) {
            log.error("执行磁盘检查时发生异常", e);
            return buildErrorResponse("执行磁盘检查时发生异常: " + e.getMessage(), startTime);
        }
    }

    /**
     * 在白名单根路径下执行 du，按占用排序，用于「磁盘满 → 定位大目录」赛题链路。
     */
    @Tool(name = "rankDiskUsageUnderPath",
            description = "在允许的目录（如 /tmp、/var/log）下按磁盘占用排序列出子目录，参数 rootPath/maxDepth/topN")
    public String rankDiskUsageUnderPath(
            @ToolParam(description = "扫描根路径，默认 /tmp", required = false) String rootPath,
            @ToolParam(description = "du 深度 1-4，默认 2", required = false) Integer maxDepth,
            @ToolParam(description = "返回前 N 条，默认 15，最大 40", required = false) Integer topN
    ) throws JsonProcessingException {
        long startTime = System.currentTimeMillis();
        String root = (rootPath != null && !rootPath.isBlank()) ? rootPath.trim()
                : (OsRuntime.isWindows() ? "C:\\Windows\\Logs" : "/tmp");
        int depth = maxDepth != null ? Math.min(Math.max(maxDepth, 1), 4) : 2;
        int limit = topN != null ? Math.min(Math.max(topN, 5), 40) : 15;

        if (!opsPathPolicy.isAllowedDiskInsightRoot(root)) {
            log.warn("磁盘热点路径拦截: {}", root);
            return buildErrorResponse(opsPathPolicy.rejectReason("磁盘热点扫描路径"), startTime);
        }

        try {
            if (OsRuntime.isWindows()) {
                return rankDiskUsageWindows(root, limit, depth, startTime);
            }
            List<String> cmd = List.of("du", "-xk", "--max-depth=" + depth, root);
            AbstractCommandExecutor.CommandResult result = minPrivilegeExecutor.executeSafely(cmd, 120_000L);
            if (!result.success()) {
                return buildErrorResponse("du 命令执行失败: " + result.error(), startTime);
            }

            List<Map<String, Object>> ranked = parseDuLines(result.output(), limit);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("root", opsPathPolicy.normalizeUnixPath(root));
            payload.put("maxDepth", depth);
            payload.put("topN", limit);
            payload.put("entries", ranked);

            String dataJson = objectMapper.writeValueAsString(payload);
            long duration = System.currentTimeMillis() - startTime;
            log.info("磁盘热点扫描完成 root={} entries={} 耗时={}ms", root, ranked.size(), duration);
            return buildSuccessResponse(dataJson, duration, false);
        } catch (Exception e) {
            log.error("磁盘热点扫描异常", e);
            return buildErrorResponse("磁盘热点扫描异常: " + e.getMessage(), startTime);
        }
    }

    private List<Map<String, Object>> parseDuLines(String output, int topN) {
        List<Map<String, Object>> rows = new ArrayList<>();
        if (output == null || output.isBlank()) {
            return rows;
        }
        for (String raw : output.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            String sizePart;
            String pathPart;
            int tab = line.indexOf('\t');
            if (tab >= 0) {
                sizePart = line.substring(0, tab).trim();
                pathPart = line.substring(tab + 1).trim();
            } else {
                int sp = line.indexOf(' ');
                if (sp <= 0) {
                    continue;
                }
                sizePart = line.substring(0, sp).trim();
                pathPart = line.substring(sp).trim();
            }
            if (!pathPart.startsWith("/")) {
                continue;
            }
            long kb;
            try {
                kb = Long.parseLong(sizePart);
            } catch (NumberFormatException e) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("kb", kb);
            row.put("path", pathPart);
            rows.add(row);
        }
        rows.sort(Comparator.comparingLong((Map<String, Object> m) -> (Long) m.get("kb")).reversed());
        if (rows.size() > topN) {
            return new ArrayList<>(rows.subList(0, topN));
        }
        return rows;
    }

    private String checkDiskUsageWindows(long startTime) throws JsonProcessingException {
        String ps = "Get-PSDrive -PSProvider FileSystem | Select-Object Name,Used,Free,@{N='Size';E={$_.Used+$_.Free}} | ConvertTo-Json -Compress";
        AbstractCommandExecutor.CommandResult result = minPrivilegeExecutor.executeSafely(
                List.of("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", ps), 60_000L);
        if (!result.success()) {
            return buildErrorResponse("Windows 磁盘检查失败: " + result.error(), startTime);
        }
        List<Map<String, String>> rows = new ArrayList<>();
        try {
            JsonNode arr = objectMapper.readTree(result.output());
            JsonNode node = arr.isArray() ? arr : objectMapper.createArrayNode().add(arr);
            for (JsonNode n : node) {
                Map<String, String> m = new LinkedHashMap<>();
                m.put("filesystem", n.path("Name").asText(""));
                m.put("used", String.valueOf(n.path("Used").asLong(0)));
                m.put("available", String.valueOf(n.path("Free").asLong(0)));
                long size = n.path("Size").asLong(0);
                m.put("size", String.valueOf(size));
                int pct = size > 0 ? (int) Math.round(100.0 * n.path("Used").asLong(0) / size) : 0;
                m.put("usePercent", pct + "%");
                m.put("mountedOn", n.path("Name").asText(""));
                rows.add(m);
            }
        } catch (Exception e) {
            log.warn("解析 PowerShell 磁盘 JSON 失败，回退原始输出: {}", e.getMessage());
            Map<String, String> m = new LinkedHashMap<>();
            m.put("raw", result.output());
            rows.add(m);
        }
        rows.sort((a, b) -> Integer.compare(parseUsePercent(b.get("usePercent")), parseUsePercent(a.get("usePercent"))));
        String dataJson = toJson(rows);
        long duration = System.currentTimeMillis() - startTime;
        return buildSuccessResponse(dataJson, duration, false);
    }

    private String rankDiskUsageWindows(String root, int limit, int depth, long startTime) throws JsonProcessingException {
        String safe = root.replace("'", "''");
        // 一级子目录按占用排序（递归统计各子目录下文件总大小）；大目录可能较慢，已放宽超时
        String ps = "$r='" + safe + "';$lim=" + limit
                + ";Get-ChildItem -LiteralPath $r -Directory -ErrorAction SilentlyContinue"
                + "|ForEach-Object{$s=(Get-ChildItem $_.FullName -File -Recurse -ErrorAction SilentlyContinue"
                + "|Measure-Object -Property Length -Sum).Sum;if($null -eq $s){$s=0}"
                + ";[pscustomobject]@{kb=[long]($s/1KB);path=$_.FullName}}"
                + "|Sort-Object kb -Descending|Select-Object -First $lim|ConvertTo-Json -Compress";
        AbstractCommandExecutor.CommandResult result = minPrivilegeExecutor.executeSafely(
                List.of("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", ps), 120_000L);
        if (!result.success()) {
            return buildErrorResponse("Windows 目录占用扫描失败: " + result.error(), startTime);
        }
        List<Map<String, Object>> ranked = new ArrayList<>();
        try {
            JsonNode node = objectMapper.readTree(result.output());
            if (node.isArray()) {
                for (JsonNode n : node) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("kb", n.path("kb").asLong(0));
                    row.put("path", n.path("path").asText(""));
                    ranked.add(row);
                }
            } else if (node.has("kb")) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("kb", node.path("kb").asLong(0));
                row.put("path", node.path("path").asText(""));
                ranked.add(row);
            }
        } catch (Exception e) {
            log.warn("解析目录占用 JSON 失败: {}", e.getMessage());
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("root", OpsPathPolicy.normalizeWindowsPath(root));
        payload.put("maxDepth", depth);
        payload.put("topN", limit);
        payload.put("entries", ranked);
        payload.put("platform", "windows");
        long duration = System.currentTimeMillis() - startTime;
        return buildSuccessResponse(objectMapper.writeValueAsString(payload), duration, false);
    }

    private List<Map<String, String>> parseOutput(String output) {
        List<Map<String, String>> diskInfoList = new ArrayList<>();
        if (output == null || output.isBlank()) {
            return diskInfoList;
        }

        String[] lines = output.split("\n");
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;

            Matcher matcher = DF_OUTPUT_PATTERN.matcher(line);
            if (matcher.matches()) {
                Map<String, String> info = new LinkedHashMap<>();
                info.put("filesystem", matcher.group(1));
                info.put("size", matcher.group(2));
                info.put("used", matcher.group(3));
                info.put("available", matcher.group(4));
                info.put("usePercent", matcher.group(5));
                info.put("mountedOn", matcher.group(6).trim());
                diskInfoList.add(info);
            } else {
                log.debug("无法解析的行: {}", line);
            }
        }
        return diskInfoList;
    }

    private static int parseUsePercent(String usePercent) {
        if (usePercent == null || usePercent.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(usePercent.replace("%", "").trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private String toJson(List<Map<String, String>> list) throws JsonProcessingException {
        return objectMapper.writeValueAsString(list);
    }

    private String buildSuccessResponse(String data, long duration, boolean cacheHit) throws JsonProcessingException {
        return McpToolResponses.success(objectMapper, data, duration);
    }

    private String buildErrorResponse(String error, long duration) throws JsonProcessingException {
        return McpToolResponses.error(objectMapper, error, duration);
    }
}
