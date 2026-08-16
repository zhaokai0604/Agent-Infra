package com.award.log.mcp.tools;

import com.award.log.mcp.AbstractCommandExecutor;
import com.award.log.mcp.McpToolResponses;
import com.award.log.mcp.MinPrivilegeExecutor;
import com.award.log.util.OsRuntime;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 赛题要求的 OS 深度感知：journalctl / ss / lsof（参数与白名单受限）。
 */
@Slf4j
@Component
public class OsInsightTool extends AbstractCommandExecutor {

    private static final int MAX_LINES = 500;
    private static final int MAX_SINCE_MIN = 1440;

    private final ObjectMapper objectMapper;
    private final MinPrivilegeExecutor minPrivilegeExecutor;

    @Autowired
    public OsInsightTool(
            @Autowired(required = false) ExecutorService mcpToolExecutor,
            ObjectMapper objectMapper,
            MinPrivilegeExecutor minPrivilegeExecutor) {
        super(mcpToolExecutor != null ? mcpToolExecutor : Executors.newCachedThreadPool());
        this.objectMapper = objectMapper;
        this.minPrivilegeExecutor = minPrivilegeExecutor;
    }

    @Tool(name = "queryJournalLogs", description = "查询 systemd 日志 journalctl，限制时间窗口与最大行数；可选 JSON 行输出以便解析 __REALTIME_TIMESTAMP")
    public String queryJournalLogs(
            @ToolParam(description = "最近多少分钟内的日志，默认 30，最大 1440", required = false) Integer sinceMinutes,
            @ToolParam(description = "最大行数，默认 200，最大 500", required = false) Integer maxLines,
            @ToolParam(description = "为 true 时使用 -o json（每行一条 JSON，含 Linux 专用时间戳字段）", required = false) Boolean jsonOutput
    ) throws JsonProcessingException {
        int since = sinceMinutes != null ? Math.min(Math.max(sinceMinutes, 1), MAX_SINCE_MIN) : 30;
        int lines = maxLines != null ? Math.min(Math.max(maxLines, 1), MAX_LINES) : 200;
        boolean asJson = Boolean.TRUE.equals(jsonOutput);
        long start = System.currentTimeMillis();
        if (OsRuntime.isWindows()) {
            String ps = "Get-WinEvent -LogName System -MaxEvents " + lines
                    + " | Where-Object { $_.TimeCreated -gt (Get-Date).AddMinutes(-" + since + ") }"
                    + " | Format-List TimeCreated,Id,LevelDisplayName,Message | Out-String -Width 240";
            List<String> cmd = List.of("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", ps);
            CommandResult r = minPrivilegeExecutor.executeSafely(cmd, 90_000L);
            return buildJsonResponse("Get-WinEvent-System", cmd, r, start);
        }
        String sinceArg = since + " min ago";
        List<String> cmd = asJson
                ? List.of("journalctl", "--no-pager", "-n", String.valueOf(lines), "--since", sinceArg, "-o", "json")
                : List.of("journalctl", "--no-pager", "-n", String.valueOf(lines), "--since", sinceArg);
        CommandResult r = minPrivilegeExecutor.executeSafely(cmd);
        return buildJsonResponse("journalctl", cmd, r, start);
    }

    @Tool(name = "listListeningSockets", description = "列出监听套接字（ss -tuln），用于网络上下文感知（替代 netstat）")
    public String listListeningSockets() throws JsonProcessingException {
        long start = System.currentTimeMillis();
        if (OsRuntime.isWindows()) {
            String ps = "Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue"
                    + " | Select-Object LocalAddress,LocalPort,OwningProcess"
                    + " | Sort-Object LocalPort | Format-Table -AutoSize | Out-String -Width 400";
            List<String> cmd = List.of("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", ps);
            CommandResult r = minPrivilegeExecutor.executeSafely(cmd, 60_000L);
            return buildJsonResponse("Get-NetTCPConnection", cmd, r, start);
        }
        List<String> cmd = List.of("ss", "-tuln");
        CommandResult r = minPrivilegeExecutor.executeSafely(cmd);
        return buildJsonResponse("ss", cmd, r, start);
    }

    @Tool(name = "listProcessOpenFiles", description = "列出指定 PID 的打开文件（lsof -p），PID 必填且为正整数")
    public String listProcessOpenFiles(
            @ToolParam(description = "进程 PID", required = true) Integer pid
    ) throws JsonProcessingException {
        long start = System.currentTimeMillis();
        if (pid == null || pid < 1 || pid > 4_194_304) {
            return McpToolResponses.error(objectMapper, "无效 PID", start);
        }
        if (OsRuntime.isWindows()) {
            String ps = "$p=Get-Process -Id " + pid + " -ErrorAction SilentlyContinue;"
                    + "if(-not $p){'NO_SUCH_PROCESS'} else {"
                    + "$p.Modules | Select-Object ModuleName,FileName | Format-Table -AutoSize | Out-String -Width 400}";
            List<String> cmd = List.of("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", ps);
            CommandResult r = minPrivilegeExecutor.executeSafely(cmd, 60_000L);
            return buildJsonResponse("Get-Process-Modules", cmd, r, start);
        }
        List<String> cmd = List.of("lsof", "-nP", "-p", String.valueOf(pid));
        CommandResult r = minPrivilegeExecutor.executeSafely(cmd);
        return buildJsonResponse("lsof", cmd, r, start);
    }

    /** MCP HTTP 网关：operation = journal | ss | lsof */
    public String executeGateway(String operation, Integer sinceMinutes, Integer maxLines, Integer pid) throws JsonProcessingException {
        return executeGateway(operation, sinceMinutes, maxLines, pid, null);
    }

    public String executeGateway(String operation, Integer sinceMinutes, Integer maxLines, Integer pid, Boolean jsonOutput) throws JsonProcessingException {
        if (operation == null || operation.isBlank()) {
            operation = "journal";
        }
        return switch (operation.trim().toLowerCase()) {
            case "journal", "journalctl" -> queryJournalLogs(sinceMinutes, maxLines, jsonOutput);
            case "ss", "sockets" -> listListeningSockets();
            case "lsof", "openfiles" -> listProcessOpenFiles(pid);
            default -> McpToolResponses.error(objectMapper, "未知 operation: " + operation, System.currentTimeMillis());
        };
    }

    private String buildJsonResponse(String tool, List<String> cmd, CommandResult r, long startTime)
            throws JsonProcessingException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tool", tool);
        body.put("command", String.join(" ", cmd));
        body.put("success", r.success());
        body.put("durationMs", System.currentTimeMillis() - startTime);
        if (r.success()) {
            String out = r.output();
            if (out != null && out.length() > 120_000) {
                out = out.substring(0, 120_000) + "\n...[truncated]";
            }
            body.put("output", out);
        } else {
            body.put("error", r.error() != null ? r.error() : ("exit=" + r.exitCode()));
        }
        long duration = System.currentTimeMillis() - startTime;
        String dataJson = objectMapper.writeValueAsString(body);
        if (r.success()) {
            return McpToolResponses.success(objectMapper, dataJson, duration);
        }
        String errMsg = r.error() != null ? r.error() : ("exit=" + r.exitCode());
        if (r.output() != null && !r.output().isBlank()) {
            return McpToolResponses.warn(objectMapper, dataJson, duration);
        }
        return McpToolResponses.error(objectMapper, errMsg, startTime);
    }
}
