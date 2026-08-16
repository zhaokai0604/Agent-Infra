package com.award.log.mcp.tools;

import com.award.log.mcp.AbstractCommandExecutor;
import com.award.log.mcp.McpToolResponses;
import com.award.log.mcp.MinPrivilegeExecutor;
import com.award.log.mcp.McpToolsConfig;
import com.award.log.util.OsRuntime;
import com.award.log.service.StatisticsService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class SystemLoadTool extends AbstractCommandExecutor {

    private static final Pattern TOP_CPU_LINE = Pattern.compile(
        "%?Cpu\\(s\\)?:\\s*([\\d.]+)\\s*id.*?\\s*([\\d.]+)\\s*wa.*?"
    );
    private static final Pattern TOP_MEM_LINE = Pattern.compile(
        "KiB Mem :\\s*([\\d]+)\\s*total,\\s*([\\d]+)\\s*free,\\s*([\\d]+)\\s*used"
    );
    private static final Pattern LOADAVG_PATTERN = Pattern.compile(
        "^([\\d.]+)\\s+([\\d.]+)\\s+([\\d.]+)"
    );

    private final ObjectMapper objectMapper;
    private final MinPrivilegeExecutor minPrivilegeExecutor;
    private final StatisticsService statisticsService;

    @Autowired
    public SystemLoadTool(
            @Autowired(required = false) ExecutorService mcpToolExecutor,
            ObjectMapper objectMapper,
            MinPrivilegeExecutor minPrivilegeExecutor,
            StatisticsService statisticsService) {
        super(mcpToolExecutor != null ? mcpToolExecutor : Executors.newCachedThreadPool());
        this.objectMapper = objectMapper;
        this.minPrivilegeExecutor = minPrivilegeExecutor;
        this.statisticsService = statisticsService;
    }

    @Tool(name = "checkSystemLoad", description = "检查系统负载与 CPU/内存概况；Linux 优先解析 top 与 /proc/loadavg，失败或非 Linux 时回退到与仪表盘一致的 JVM/OSHI 采集（负载在 Windows 上可能为 0）")
    @Cacheable(value = McpToolsConfig.CACHE_SYSTEM_LOAD, key = "'sysload'", cacheManager = "mcpCacheManager")
    public String checkSystemLoad() throws JsonProcessingException {
        long startTime = System.currentTimeMillis();
        log.info("开始执行系统负载检查");

        try {
            double cpuUsage;
            double memUsage;
            double[] loadAvg;
            if (OsRuntime.isWindows()) {
                cpuUsage = readPerfPercent("cpuUsage");
                memUsage = readPerfPercent("memoryUsage");
                loadAvg = new double[]{0.0, 0.0, 0.0};
                if (cpuUsage < 0) {
                    cpuUsage = getCpuUsage();
                }
                if (memUsage < 0) {
                    memUsage = getMemUsage();
                }
            } else {
                cpuUsage = getCpuUsage();
                memUsage = getMemUsage();
                loadAvg = getLoadAverage();

                if (cpuUsage < 0) {
                    cpuUsage = readPerfPercent("cpuUsage");
                }
                if (memUsage < 0) {
                    memUsage = readPerfPercent("memoryUsage");
                }
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("cpuUsagePercent", Math.round(cpuUsage * 10) / 10.0);
            result.put("memUsagePercent", Math.round(memUsage * 10) / 10.0);
            result.put("loadAvg1min", Math.round(loadAvg[0] * 100) / 100.0);
            result.put("loadAvg5min", Math.round(loadAvg[1] * 100) / 100.0);
            result.put("loadAvg15min", Math.round(loadAvg[2] * 100) / 100.0);

            String dataJson = toJson(result);
            long duration = System.currentTimeMillis() - startTime;

            log.info("系统负载检查完成，CPU: {}%, 内存: {}%, 负载: {}/{}/{}, 耗时: {}ms",
                cpuUsage, memUsage, loadAvg[0], loadAvg[1], loadAvg[2], duration);

            return McpToolResponses.success(objectMapper, dataJson, duration);

        } catch (Exception e) {
            log.error("执行系统负载检查时发生异常", e);
            return McpToolResponses.error(objectMapper, "执行系统负载检查时发生异常: " + e.getMessage(), startTime);
        }
    }

    private double getCpuUsage() {
        try {
            AbstractCommandExecutor.CommandResult result = minPrivilegeExecutor.executeSafely(List.of("top", "-bn1"));
            if (!result.success()) {
                log.warn("获取 CPU 使用率失败: {}", result.error());
                return -1.0;
            }

            String[] lines = result.output().split("\n");
            for (String line : lines) {
                Matcher cpuMatcher = TOP_CPU_LINE.matcher(line);
                if (cpuMatcher.find()) {
                    double idle = parseDoubleOrDefault(cpuMatcher.group(2), 100.0);
                    return Math.max(0, 100.0 - idle);
                }
            }
        } catch (Exception e) {
            log.warn("获取 CPU 使用率异常: {}", e.getMessage());
        }
        return -1.0;
    }

    private double getMemUsage() {
        try {
            AbstractCommandExecutor.CommandResult result = minPrivilegeExecutor.executeSafely(List.of("top", "-bn1"));
            if (!result.success()) {
                log.warn("获取内存使用率失败: {}", result.error());
                return -1.0;
            }

            String[] lines = result.output().split("\n");
            for (String line : lines) {
                Matcher memMatcher = TOP_MEM_LINE.matcher(line);
                if (memMatcher.find()) {
                    double total = parseDoubleOrDefault(memMatcher.group(1), 1.0);
                    double used = parseDoubleOrDefault(memMatcher.group(3), 0.0);
                    if (total > 0) {
                        return (used / total) * 100.0;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("获取内存使用率异常: {}", e.getMessage());
        }
        return -1.0;
    }

    /** top 解析失败时（Windows、BusyBox、非英文 top 等）用与仪表盘一致的 OSHI/JVM 指标兜底。 */
    private double readPerfPercent(String key) {
        if (statisticsService == null) {
            return -1.0;
        }
        try {
            Map<String, Object> perf = statisticsService.getSystemPerformance(null);
            Object v = perf.get(key);
            if (v instanceof Number n) {
                double d = n.doubleValue();
                if (d >= 0 && d <= 100) {
                    return d;
                }
            }
        } catch (Exception e) {
            log.warn("读取 {} 兜底指标失败: {}", key, e.getMessage());
        }
        return -1.0;
    }

    private double[] getLoadAverage() {
        double[] loadAvg = new double[]{0.0, 0.0, 0.0};
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/loadavg"))) {
            String line = reader.readLine();
            if (line != null) {
                Matcher matcher = LOADAVG_PATTERN.matcher(line);
                if (matcher.find()) {
                    loadAvg[0] = parseDoubleOrDefault(matcher.group(1), 0.0);
                    loadAvg[1] = parseDoubleOrDefault(matcher.group(2), 0.0);
                    loadAvg[2] = parseDoubleOrDefault(matcher.group(3), 0.0);
                }
            }
        } catch (Exception e) {
            log.warn("读取 /proc/loadavg 失败: {}", e.getMessage());
        }
        return loadAvg;
    }

    private double parseDoubleOrDefault(String value, double defaultValue) {
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException | NullPointerException e) {
            return defaultValue;
        }
    }

    private String toJson(Map<String, Object> map) throws JsonProcessingException {
        return objectMapper.writeValueAsString(map);
    }
}
