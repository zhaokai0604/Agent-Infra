package com.award.log.mcp.tools;

import com.award.log.mcp.AbstractCommandExecutor;
import com.award.log.mcp.McpToolResponses;
import com.award.log.mcp.MinPrivilegeExecutor;
import com.award.log.mcp.McpToolsConfig;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class ProcessTool extends AbstractCommandExecutor {

    private static final Pattern PS_LIST_PATTERN = Pattern.compile(
        "^(\\S+)\\s+(\\d+)\\s+(\\d+)\\s+([\\d.]+)\\s+([\\d.]+)\\s+(\\S+)\\s+(\\S+)\\s+(.*)$"
    );
    private static final Pattern PS_LIST_LEGACY_PATTERN = Pattern.compile(
        "^(\\S+)\\s+(\\d+)\\s+([\\d.]+)\\s+([\\d.]+)\\s+(\\S+)\\s+(\\S+)\\s+(.*)$"
    );

    private final ObjectMapper objectMapper;
    private final MinPrivilegeExecutor minPrivilegeExecutor;

    @Autowired
    public ProcessTool(
            @Autowired(required = false) ExecutorService mcpToolExecutor,
            ObjectMapper objectMapper,
            MinPrivilegeExecutor minPrivilegeExecutor) {
        super(mcpToolExecutor != null ? mcpToolExecutor : Executors.newCachedThreadPool());
        this.objectMapper = objectMapper;
        this.minPrivilegeExecutor = minPrivilegeExecutor;
    }

    @Tool(name = "listProcesses", description = "列出进程：Linux 用 ps -eo 稳定列格式；Windows 用 WMI 性能计数器近似 CPU%，并按逻辑处理器数归一化到 0-100%，内存为物理内存占比。默认列出 CPU 或内存超过阈值（默认 5%）的进程")
    @Cacheable(value = McpToolsConfig.CACHE_PROCESS_LIST, key = "'processes'", cacheManager = "mcpCacheManager")
    public String listProcesses(
            @ToolParam(description = "最小 CPU 使用率百分比，超过此值的进程会被列出（默认 5.0）", required = false) Double minCpu,
            @ToolParam(description = "最小内存使用率百分比，超过此值的进程会被列出（默认 5.0）", required = false) Double minMem
    ) throws JsonProcessingException {
        long startTime = System.currentTimeMillis();
        double cpuThreshold = minCpu != null ? minCpu : 5.0;
        double memThreshold = minMem != null ? minMem : 5.0;

        log.info("开始执行进程列表检查，CPU阈值: {}%, 内存阈值: {}%", cpuThreshold, memThreshold);

        try {
            if (OsRuntime.isWindows()) {
                return listProcessesWindows(cpuThreshold, memThreshold, startTime);
            }
            AbstractCommandExecutor.CommandResult result = minPrivilegeExecutor.executeSafely(List.of(
                    "ps", "-eo", "user,pid,ppid,pcpu,pmem,etime,stat,command", "--sort=-pcpu,-pmem", "--no-headers"));

            if (!result.success()) {
                String errorJson = McpToolResponses.error(objectMapper,"ps 命令执行失败: " + result.error(), startTime);
                log.error("进程列表检查失败: {}", result.error());
                return errorJson;
            }

            List<Map<String, String>> processList = sortProcessesByUsage(parseOutput(result.output(), cpuThreshold, memThreshold));
            String dataJson = toJson(processList);
            long duration = System.currentTimeMillis() - startTime;

            log.info("进程列表检查完成，共获取 {} 个符合条件的进程，耗时: {}ms", processList.size(), duration);
            return McpToolResponses.success(objectMapper, dataJson, duration);

        } catch (Exception e) {
            log.error("执行进程列表检查时发生异常", e);
            return McpToolResponses.error(objectMapper,"执行进程列表检查时发生异常: " + e.getMessage(), startTime);
        }
    }

    /**
     * Windows：用性能计数器 {@code PercentProcessorTime} 得到近似 CPU%，勿把 {@code Get-Process.CPU}（累计秒）当百分比。
     */
    private String listProcessesWindows(double cpuThreshold, double memThreshold, long startTime) throws JsonProcessingException {
        String psPerf =
                "$cpuCount=[math]::Max(1,[int][Environment]::ProcessorCount);"
                        + "$t=(Get-CimInstance Win32_OperatingSystem).TotalVisibleMemorySize*1KB;"
                        + "$list=@(Get-CimInstance Win32_PerfFormattedData_PerfProc_Process -ErrorAction SilentlyContinue"
                        + "|Where-Object{$_.IDProcess -gt 0 -and $_.Name -ne 'Idle' -and $_.Name -ne '_Total'});"
                        + "$list|Select-Object "
                        + "@{N='ProcessName';E={($_.Name -split '#')[0]}},@{N='Id';E={$_.IDProcess}},"
                        + "@{N='cpuPct';E={[math]::Round([math]::Min(100,[double]$_.PercentProcessorTime/($cpuCount+0.0)),2)}},"
                        + "@{N='memPct';E={[math]::Round(100*[double]$_.WorkingSet/($t+1),2)}},"
                        + "@{N='wsMb';E={[math]::Round($_.WorkingSet/1MB,2)}}"
                        + "|Sort-Object cpuPct,memPct -Descending|ConvertTo-Json -Compress -Depth 5";
        AbstractCommandExecutor.CommandResult result = minPrivilegeExecutor.executeSafely(
                List.of("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", psPerf), 60_000L);
        List<Map<String, String>> processList = parseWindowsProcessJson(result, cpuThreshold, memThreshold, true);
        if (processList.isEmpty() && (!result.success() || result.output() == null || result.output().isBlank())) {
            return McpToolResponses.error(objectMapper,"Windows 进程列表失败: " + (result.success() ? "无数据" : result.error()), startTime);
        }
        if (processList.isEmpty()) {
            processList = listProcessesWindowsMemFallback(cpuThreshold, memThreshold);
        }
        processList = sortProcessesByUsage(processList);
        String dataJson = toJson(processList);
        long duration = System.currentTimeMillis() - startTime;
        return McpToolResponses.success(objectMapper, dataJson, duration);
    }

    /** perf 计数器不可用时：仅按内存占比筛选，CPU 列标注为不可用 */
    private List<Map<String, String>> listProcessesWindowsMemFallback(double cpuThreshold, double memThreshold) {
        String ps =
                "$t=(Get-CimInstance Win32_OperatingSystem).TotalVisibleMemorySize*1KB;"
                        + "Get-Process -ErrorAction SilentlyContinue|Sort-Object WorkingSet64 -Descending|Select-Object -First 60 "
                        + "ProcessName,Id,@{N='cpuPct';E={-1}},"
                        + "@{N='memPct';E={[math]::Round(100*$_.WorkingSet64/($t+1),2)}},"
                        + "@{N='wsMb';E={[math]::Round($_.WS/1MB,2)}}|ConvertTo-Json -Compress";
        AbstractCommandExecutor.CommandResult r = minPrivilegeExecutor.executeSafely(
                List.of("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", ps), 60_000L);
        List<Map<String, String>> list = parseWindowsProcessJson(r, cpuThreshold, memThreshold, false);
        if (!list.isEmpty()) {
            log.info("Windows 进程列表已回退为仅内存阈值筛选（CPU 计数器不可用）");
        }
        return list;
    }

    private List<Map<String, String>> parseWindowsProcessJson(
            AbstractCommandExecutor.CommandResult result,
            double cpuThreshold,
            double memThreshold,
            boolean useCpuThreshold) {
        List<Map<String, String>> processList = new ArrayList<>();
        if (!result.success() || result.output() == null || result.output().isBlank()) {
            return processList;
        }
        try {
            JsonNode node = objectMapper.readTree(result.output());
            JsonNode arr = node.isArray() ? node : objectMapper.createArrayNode().add(node);
            for (JsonNode n : arr) {
                double mem = n.path("memPct").asDouble(0);
                double cpu = n.path("cpuPct").asDouble(0);
                boolean cpuHit = useCpuThreshold && cpu >= 0 && cpu > cpuThreshold;
                boolean memHit = mem > memThreshold;
                if (!cpuHit && !memHit) {
                    continue;
                }
                Map<String, String> info = new LinkedHashMap<>();
                info.put("user", "-");
                info.put("pid", String.valueOf(n.path("Id").asInt(0)));
                if (cpu < 0) {
                    info.put("cpu", "n/a");
                } else {
                    info.put("cpu", String.format("%.1f", cpu));
                }
                info.put("mem", String.format("%.1f", mem));
                if (n.has("wsMb")) {
                    info.put("memMb", String.format("%.1f", n.path("wsMb").asDouble(0)));
                }
                if (cpuHit && memHit) {
                    info.put("reason", "cpu+mem");
                } else if (cpuHit) {
                    info.put("reason", "cpu");
                } else {
                    info.put("reason", "mem");
                }
                info.put("etime", "-");
                info.put("command", n.path("ProcessName").asText(""));
                info.put("state", "running");
                processList.add(info);
            }
        } catch (Exception e) {
            log.warn("解析 Windows 进程 JSON 失败: {}", e.getMessage());
        }
        return processList;
    }

    private List<Map<String, String>> parseOutput(String output, double cpuThreshold, double memThreshold) {
        List<Map<String, String>> processList = new ArrayList<>();
        if (output == null || output.isBlank()) {
            return processList;
        }

        String[] lines = output.split("\n");
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;

            Matcher matcher = PS_LIST_PATTERN.matcher(line);
            boolean hasParentPid = matcher.matches();
            if (!hasParentPid) {
                matcher = PS_LIST_LEGACY_PATTERN.matcher(line);
            }
            if (!matcher.matches()) {
                log.debug("无法解析的行: {}", line);
                continue;
            }

            String user = matcher.group(1);
            String pid = matcher.group(2);
            String ppid = hasParentPid ? matcher.group(3) : "";
            double cpu = parsePercent(matcher.group(hasParentPid ? 4 : 3));
            double mem = parsePercent(matcher.group(hasParentPid ? 5 : 4));
            String etime = matcher.group(hasParentPid ? 6 : 5);
            String state = matcher.group(hasParentPid ? 7 : 6);
            String command = matcher.group(hasParentPid ? 8 : 7).trim();

            if (isSelfInspectionCommand(command)) {
                continue;
            }

            boolean isZombie = state != null && state.contains("Z");
            boolean cpuExceeds = cpu > cpuThreshold;
            boolean memExceeds = mem > memThreshold;

            if (!isZombie && !cpuExceeds && !memExceeds) {
                continue;
            }

            Map<String, String> info = new LinkedHashMap<>();
            info.put("user", user);
            info.put("pid", pid);
            if (!ppid.isBlank()) {
                info.put("ppid", ppid);
            }
            info.put("cpu", String.format("%.1f", cpu));
            info.put("mem", String.format("%.1f", mem));
            if (cpuExceeds && memExceeds) {
                info.put("reason", "cpu+mem");
            } else if (cpuExceeds) {
                info.put("reason", "cpu");
            } else {
                info.put("reason", "mem");
            }
            info.put("etime", etime);
            info.put("command", truncateCommand(command));
            info.put("state", isZombie ? "zombie" : "running");
            processList.add(info);
        }
        return processList;
    }

    private boolean isSelfInspectionCommand(String command) {
        if (command == null) {
            return false;
        }
        String normalized = command.trim().toLowerCase();
        return normalized.equals("ps")
                || normalized.startsWith("ps ")
                || normalized.startsWith("/bin/ps ")
                || normalized.startsWith("/usr/bin/ps ")
                || normalized.contains(" ps -eo ")
                || normalized.contains(" ps --sort=");
    }

    private double parsePercent(String value) {
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    private static List<Map<String, String>> sortProcessesByUsage(List<Map<String, String>> list) {
        if (list == null || list.size() <= 1) {
            return list;
        }
        List<Map<String, String>> sorted = new ArrayList<>(list);
        sorted.sort((a, b) -> {
            double cpuB = parseCpuSortKey(b.get("cpu"));
            double cpuA = parseCpuSortKey(a.get("cpu"));
            if (cpuB != cpuA) {
                return Double.compare(cpuB, cpuA);
            }
            return Double.compare(parsePercentStatic(b.get("mem")), parsePercentStatic(a.get("mem")));
        });
        return sorted;
    }

    private static double parseCpuSortKey(String cpu) {
        if (cpu == null || "n/a".equalsIgnoreCase(cpu.trim())) {
            return -1.0;
        }
        return parsePercentStatic(cpu);
    }

    private static double parsePercentStatic(String value) {
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    private String truncateCommand(String command) {
        if (command.length() > 200) {
            return command.substring(0, 200) + "...";
        }
        return command;
    }

    private String toJson(List<Map<String, String>> list) throws JsonProcessingException {
        return objectMapper.writeValueAsString(list);
    }

    @Tool(name = "inspectProcessOwnership", description = "归因指定 Linux 进程：结合父进程、/proc cgroup、systemd status、可执行文件和 lsof 判断匿名进程（如 s_daemon）属于哪个服务；只读，不结束进程")
    public String inspectProcessOwnership(
            @ToolParam(description = "待归因的进程 PID", required = true) Integer pid
    ) throws JsonProcessingException {
        long startTime = System.currentTimeMillis();
        if (pid == null || pid < 1 || pid > 4_194_304) {
            return McpToolResponses.error(objectMapper, "无效 PID", startTime);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("pid", pid);
        result.put("platform", OsRuntime.isWindows() ? "windows" : "linux");
        if (OsRuntime.isWindows()) {
            AbstractCommandExecutor.CommandResult process = minPrivilegeExecutor.executeSafely(List.of(
                    "powershell.exe", "-NoProfile", "-Command",
                    "$p=Get-CimInstance Win32_Process -Filter 'ProcessId=" + pid + "';"
                            + "if($p){$p|Select-Object ProcessId,ParentProcessId,Name,ExecutablePath,CommandLine|ConvertTo-Json -Compress}"
            ), 60_000L);
            result.put("process", probe(process));
            result.put("association", "Windows parent-process/WMI evidence");
        } else {
            result.put("procStatus", readProcFile(pid, "status"));
            result.put("cgroup", readProcFile(pid, "cgroup"));
            result.put("executable", readProcLink(pid, "exe"));
            result.put("process", probe(minPrivilegeExecutor.executeSafely(List.of(
                    "ps", "-o", "pid=,ppid=,user=,group=,etime=,stat=,comm=,args=", "-p", String.valueOf(pid)))));
            result.put("systemd", probe(minPrivilegeExecutor.executeSafely(List.of(
                    "systemctl", "status", "--no-pager", "--full", String.valueOf(pid)), 60_000L)));
            result.put("openFiles", probe(minPrivilegeExecutor.executeSafely(List.of(
                    "lsof", "-nP", "-p", String.valueOf(pid)), 60_000L)));
            String cgroup = String.valueOf(result.getOrDefault("cgroup", ""));
            result.put("serviceUnit", serviceUnitFromCgroup(cgroup));
            result.put("association", associationHint(cgroup, String.valueOf(result.get("serviceUnit"))));
        }
        return McpToolResponses.success(objectMapper, objectMapper.writeValueAsString(result),
                System.currentTimeMillis() - startTime);
    }

    private String readProcFile(int pid, String name) {
        try {
            return java.nio.file.Files.readString(java.nio.file.Path.of("/proc", String.valueOf(pid), name));
        } catch (Exception e) {
            return "unavailable: " + e.getMessage();
        }
    }

    private String readProcLink(int pid, String name) {
        try {
            return java.nio.file.Files.readSymbolicLink(java.nio.file.Path.of("/proc", String.valueOf(pid), name)).toString();
        } catch (Exception e) {
            return "unavailable: " + e.getMessage();
        }
    }

    private Map<String, Object> probe(AbstractCommandExecutor.CommandResult result) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", result != null && result.success());
        if (result == null) {
            out.put("error", "no result");
            return out;
        }
        String output = result.output();
        if (output != null && output.length() > 16_000) {
            output = output.substring(0, 16_000) + "\n...[truncated]";
        }
        out.put("output", output);
        if (!result.success()) {
            out.put("error", result.error() != null ? result.error() : "exit=" + result.exitCode());
        }
        return out;
    }

    private String serviceUnitFromCgroup(String cgroup) {
        if (cgroup == null || cgroup.isBlank()) {
            return "";
        }
        for (String line : cgroup.split("\\R")) {
            String path = line.substring(line.lastIndexOf(':') + 1).trim();
            for (String part : path.split("/")) {
                if (part.endsWith(".service") || part.endsWith(".scope")) {
                    return part;
                }
            }
        }
        return "";
    }

    private String associationHint(String cgroup, String serviceUnit) {
        if (serviceUnit != null && !serviceUnit.isBlank()) {
            return "cgroup 显示归属于 systemd 单元 " + serviceUnit;
        }
        if (cgroup != null && !cgroup.isBlank() && !cgroup.startsWith("unavailable")) {
            return "已取得 cgroup 与父进程证据，但未解析出 .service/.scope 单元";
        }
        return "未取得 systemd 归属证据，需要结合父进程、命令行和 lsof 人工判断";
    }

    @Tool(name = "terminateProcess",
            description = "终止进程（写操作）：默认 dryRun 预览；真实结束须 dryRun=false 且 confirmKill=true。禁止结束 PID≤10")
    public String terminateProcess(
            @ToolParam(description = "进程 PID", required = true) Integer pid,
            @ToolParam(description = "信号：TERM|KILL，默认 TERM", required = false) String signal,
            @ToolParam(description = "true/null=预览", required = false) Boolean dryRun,
            @ToolParam(description = "真实结束须 true", required = false) Boolean confirmKill
    ) throws JsonProcessingException {
        long startTime = System.currentTimeMillis();
        if (pid == null || pid <= 10) {
            return McpToolResponses.error(objectMapper,"PID 无效或属于受保护范围（须 >10）", startTime);
        }
        com.award.log.security.ChatWriteExecutionPolicy.ResolvedKill write =
                com.award.log.security.ChatWriteExecutionPolicy.resolveKill(dryRun, confirmKill);
        boolean isDryRun = write.dryRun();
        if (!isDryRun && !write.confirmKill()) {
            return McpToolResponses.error(objectMapper,"真实结束进程需 dryRun=false 且 confirmKill=true", startTime);
        }
        String sig = signal == null || signal.isBlank() ? "TERM" : signal.trim().toUpperCase();
        List<String> cmd;
        if (OsRuntime.isWindows()) {
            cmd = "KILL".equals(sig)
                    ? List.of("taskkill", "/F", "/PID", String.valueOf(pid))
                    : List.of("taskkill", "/PID", String.valueOf(pid));
        } else {
            cmd = "KILL".equals(sig) || "SIGKILL".equals(sig)
                    ? List.of("kill", "-9", String.valueOf(pid))
                    : List.of("kill", "-15", String.valueOf(pid));
        }
        if (isDryRun) {
            Map<String, Object> preview = new LinkedHashMap<>();
            preview.put("mode", "DRY-RUN");
            preview.put("pid", pid);
            preview.put("plan", String.join(" ", cmd));
            String data = objectMapper.writeValueAsString(preview);
            return McpToolResponses.success(objectMapper, data, System.currentTimeMillis() - startTime);
        }
        AbstractCommandExecutor.CommandResult result = minPrivilegeExecutor.executeSafely(cmd, 30_000L);
        if (isSimulatedDryRun(result)) {
            return McpToolResponses.error(objectMapper,"全局演练模式已启用：结束进程请求被模拟执行，进程未实际终止", startTime);
        }
        if (!result.success()) {
            return McpToolResponses.error(objectMapper,"结束进程失败: " + (result.error() != null ? result.error() : result.output()), startTime);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("mode", "EXECUTED");
        body.put("pid", pid);
        body.put("command", String.join(" ", cmd));
        body.put("success", true);
        body.put("output", result.output());
        String data = objectMapper.writeValueAsString(body);
        long duration = System.currentTimeMillis() - startTime;
        return McpToolResponses.success(objectMapper, data, duration);
    }

    private boolean isSimulatedDryRun(AbstractCommandExecutor.CommandResult result) {
        return result != null && result.output() != null && result.output().contains("[DRY-RUN]");
    }

    /** MCP HTTP 网关：operation = list | kill */
    public String executeGateway(
            String operation,
            Double minCpu,
            Double minMem,
            Integer pid,
            String signal,
            Boolean dryRun,
            Boolean confirmKill
    ) throws JsonProcessingException {
        String op = operation == null || operation.isBlank() ? "list" : operation.trim().toLowerCase();
        return switch (op) {
            case "list", "ps" -> listProcesses(minCpu, minMem);
            case "kill", "terminate", "stop" -> terminateProcess(pid, signal, dryRun, confirmKill);
            default -> McpToolResponses.error(objectMapper,"未知 operation: " + operation + "；可选 list|kill", System.currentTimeMillis());
        };
    }
}
