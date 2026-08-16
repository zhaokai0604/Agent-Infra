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
import java.util.regex.Pattern;

/**
 * Systemd / Windows 服务只读状态巡检。
 */
@Slf4j
@Component
public class SystemdTool extends AbstractCommandExecutor {

    private static final Pattern SERVICE_NAME = Pattern.compile("^[a-zA-Z0-9@._\\-]{1,128}$");
    private static final int MAX_OUTPUT = 80_000;

    private final ObjectMapper objectMapper;
    private final MinPrivilegeExecutor minPrivilegeExecutor;
    private final ServiceRestartTool serviceRestartTool;

    @Autowired
    public SystemdTool(
            @Autowired(required = false) ExecutorService mcpToolExecutor,
            ObjectMapper objectMapper,
            MinPrivilegeExecutor minPrivilegeExecutor,
            ServiceRestartTool serviceRestartTool) {
        super(mcpToolExecutor != null ? mcpToolExecutor : Executors.newCachedThreadPool());
        this.objectMapper = objectMapper;
        this.minPrivilegeExecutor = minPrivilegeExecutor;
        this.serviceRestartTool = serviceRestartTool;
    }

    @Tool(name = "listFailedSystemdUnits",
            description = "列出异常服务：Linux 用 systemctl --failed；Windows 用「应自动启动但未运行」的服务")
    public String listFailedSystemdUnits() throws JsonProcessingException {
        long start = System.currentTimeMillis();
        List<String> cmd;
        if (OsRuntime.isWindows()) {
            // 仅「应自动启动却未运行」或异常状态；排除大量正常的 Disabled/手动停止服务
            String ps = "Get-Service | Where-Object {"
                    + " ($_.Status -eq 'Stopped' -and $_.StartType -eq 'Automatic')"
                    + " -or ($_.Status -in @('StopPending','Paused'))"
                    + " } | Select-Object Name,Status,StartType"
                    + " | Format-Table -AutoSize | Out-String -Width 200";
            cmd = List.of("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", ps);
        } else {
            cmd = List.of("systemctl", "--failed", "--no-pager", "--no-legend");
        }
        CommandResult r = minPrivilegeExecutor.executeSafely(cmd, 45_000L);
        return wrap(OsRuntime.isWindows() ? "windows-service-anomaly" : "systemd-failed", cmd, r, start);
    }

    @Tool(name = "getServiceStatus",
            description = "查询单个服务单元状态（Linux: systemctl status；Windows: sc query），只读")
    public String getServiceStatus(
            @ToolParam(description = "服务单元名，如 nginx、mysql（不含 .service 亦可）", required = true) String serviceName
    ) throws JsonProcessingException {
        long start = System.currentTimeMillis();
        if (serviceName == null || serviceName.isBlank()) {
            return McpToolResponses.error(objectMapper, "服务名不能为空", start);
        }
        String svc = serviceName.trim();
        if (!SERVICE_NAME.matcher(svc).matches()) {
            return McpToolResponses.error(objectMapper, "服务名格式不合法", start);
        }
        String unit = svc.endsWith(".service") ? svc : svc + ".service";

        List<String> cmd;
        if (OsRuntime.isWindows()) {
            cmd = List.of("sc", "query", svc.replace(".service", ""));
        } else {
            cmd = List.of("systemctl", "status", unit, "--no-pager");
        }
        CommandResult r = minPrivilegeExecutor.executeSafely(cmd, 30_000L);
        return wrap("service-status", cmd, r, start);
    }

    /** MCP HTTP 网关：operation = failed | status | restart */
    public String executeGateway(
            String operation,
            String serviceName,
            Boolean dryRun,
            Boolean confirmRestart
    ) throws JsonProcessingException {
        String op = operation == null || operation.isBlank() ? "failed" : operation.trim().toLowerCase();
        return switch (op) {
            case "failed", "list-failed" -> listFailedSystemdUnits();
            case "status" -> getServiceStatus(serviceName);
            case "restart", "reload" -> serviceRestartTool.restartService(serviceName, dryRun, confirmRestart);
            default -> McpToolResponses.error(objectMapper, "未知 operation: " + operation + "；可选 failed|status|restart",
                    System.currentTimeMillis());
        };
    }

    private String wrap(String tool, List<String> cmd, CommandResult r, long start) throws JsonProcessingException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tool", tool);
        body.put("command", String.join(" ", cmd));
        boolean ok = r.success() || (r.output() != null && !r.output().isBlank());
        body.put("success", ok);
        body.put("durationMs", System.currentTimeMillis() - start);
        String out = r.output();
        if (out != null && out.length() > MAX_OUTPUT) {
            out = out.substring(0, MAX_OUTPUT) + "\n...[truncated]";
        }
        body.put("output", out != null ? out : "");
        if (!r.success() && r.error() != null) {
            body.put("stderr", r.error());
        }
        long duration = System.currentTimeMillis() - start;
        String dataJson = objectMapper.writeValueAsString(body);
        if (ok) {
            return McpToolResponses.success(objectMapper, dataJson, duration);
        }
        return McpToolResponses.warn(objectMapper, dataJson, duration);
    }
}
