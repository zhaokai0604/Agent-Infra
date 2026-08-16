package com.award.log.mcp.tools;

import com.award.log.config.AgentOpsProperties;
import com.award.log.mcp.AbstractCommandExecutor;
import com.award.log.mcp.McpToolResponses;
import com.award.log.mcp.MinPrivilegeExecutor;
import com.award.log.security.ChatWriteExecutionPolicy;
import com.award.log.util.OsRuntime;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 受控服务重启（写操作）：仅允许配置白名单内的单元名；默认 dry-run 展示将执行的命令。
 */
@Slf4j
@Component
public class ServiceRestartTool extends AbstractCommandExecutor {

    private final ObjectMapper objectMapper;
    private final MinPrivilegeExecutor minPrivilegeExecutor;
    private final AgentOpsProperties agentOpsProperties;

    @Autowired
    public ServiceRestartTool(
            @Autowired(required = false) ExecutorService mcpToolExecutor,
            ObjectMapper objectMapper,
            MinPrivilegeExecutor minPrivilegeExecutor,
            AgentOpsProperties agentOpsProperties) {
        super(mcpToolExecutor != null ? mcpToolExecutor : Executors.newCachedThreadPool());
        this.objectMapper = objectMapper;
        this.minPrivilegeExecutor = minPrivilegeExecutor;
        this.agentOpsProperties = agentOpsProperties;
    }

    private Set<String> currentAllowlist() {
        Set<String> allowlist = new LinkedHashSet<>();
        List<String> configured = agentOpsProperties.getServiceRestart().getAllowlist();
        if (configured != null) {
            for (String p : configured) {
                String t = p.trim().toLowerCase(Locale.ROOT);
                if (!t.isEmpty()) {
                    allowlist.add(t);
                }
            }
        }
        return allowlist;
    }

    @Tool(name = "restartService",
            description = "重启白名单内的非关键服务（Linux: systemctl）。默认 dryRun=true 仅预览；真实重启需 dryRun=false 且 confirmRestart=true。")
    public String restartService(
            @ToolParam(description = "服务单元名，如 nginx（不含 .service 亦可）", required = true) String serviceName,
            @ToolParam(description = "true/null=仅预览；false=执行重启（须 confirmRestart=true）", required = false) Boolean dryRun,
            @ToolParam(description = "必须为 true 才会在 dryRun=false 时执行重启", required = false) Boolean confirmRestart
    ) throws JsonProcessingException {
        long startTime = System.currentTimeMillis();
        if (serviceName == null || serviceName.isBlank()) {
            return McpToolResponses.error(objectMapper, "serviceName 不能为空", startTime);
        }
        String unit = serviceName.trim().toLowerCase(Locale.ROOT).replace(".service", "");
        Set<String> allowlist = currentAllowlist();
        if (!allowlist.contains(unit)) {
            return McpToolResponses.error(objectMapper,
                    "服务不在允许重启列表内（agent.service-restart.allowlist）。当前允许: " + allowlist,
                    startTime);
        }

        ChatWriteExecutionPolicy.ResolvedRestart write =
                ChatWriteExecutionPolicy.resolveRestart(dryRun, confirmRestart);
        boolean isDryRun = write.dryRun();
        boolean confirmRestartResolved = write.confirmRestart();
        if (!isDryRun && !confirmRestartResolved) {
            return McpToolResponses.error(objectMapper,
                    "真实重启需 dryRun=false 且 confirmRestart=true（建议先经过 MCP 二次确认）", startTime);
        }

        log.info("[ServiceRestart] unit={} dryRun={}", unit, isDryRun);

        try {
            if (OsRuntime.isWindows()) {
                return restartWindows(unit, isDryRun, startTime);
            }

            String svc = unit.endsWith(".service") ? unit : unit + ".service";
            if (isDryRun) {
                String plan = "将执行: systemctl restart " + svc + "（当前为预览，未执行）";
                String data = "{\"mode\":\"DRY-RUN\",\"service\":\"" + svc + "\",\"plan\":\"" + escapeJson(plan) + "\"}";
                return McpToolResponses.success(objectMapper, data, System.currentTimeMillis() - startTime);
            }

            AbstractCommandExecutor.CommandResult r = minPrivilegeExecutor.executeSafely(
                    List.of("systemctl", "restart", svc), 120_000L);
            if (isSimulatedDryRun(r)) {
                return McpToolResponses.error(objectMapper,
                        "全局演练模式已启用：systemctl restart 被模拟执行，服务未实际重启", startTime);
            }
            if (!r.success()) {
                return McpToolResponses.error(objectMapper,
                        "服务重启失败: " + (r.error() != null ? r.error() : r.output()), startTime);
            }
            String data = String.format(Locale.ROOT,
                    "{\"mode\":\"EXECUTED\",\"service\":\"%s\",\"success\":%s,\"stderr\":\"%s\"}",
                    svc, true, escapeJson(r.error() == null ? "" : r.error()));
            return McpToolResponses.success(objectMapper, data, System.currentTimeMillis() - startTime);
        } catch (Exception e) {
            log.error("[ServiceRestart] 异常", e);
            return McpToolResponses.error(objectMapper, "服务重启失败: " + e.getMessage(), startTime);
        }
    }

    private String restartWindows(String unit, boolean isDryRun, long startTime) throws JsonProcessingException {
        // Windows 使用 Get-Service / Restart-Service，名称需与白名单一致（小写匹配 Service Name 常见不齐 — 简化：仅预览）
        if (isDryRun) {
            String data = "{\"mode\":\"DRY-RUN\",\"service\":\"" + unit + "\",\"plan\":\"将尝试 Restart-Service -Name "
                    + unit + "（预览）\",\"platform\":\"windows\"}";
            return McpToolResponses.success(objectMapper, data, System.currentTimeMillis() - startTime);
        }
        String ps = "Restart-Service -Name '" + unit.replace("'", "''") + "' -Force -ErrorAction Stop";
        AbstractCommandExecutor.CommandResult r = minPrivilegeExecutor.executeSafely(
                List.of("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", ps), 120_000L);
        if (isSimulatedDryRun(r)) {
            return McpToolResponses.error(objectMapper,
                    "全局演练模式已启用：Restart-Service 被模拟执行，服务未实际重启", startTime);
        }
        if (!r.success()) {
            return McpToolResponses.error(objectMapper,
                    "服务重启失败: " + (r.error() != null ? r.error() : r.output()), startTime);
        }
        String data = String.format(Locale.ROOT,
                "{\"mode\":\"EXECUTED\",\"service\":\"%s\",\"success\":%s,\"platform\":\"windows\",\"stderr\":\"%s\"}",
                unit, true, escapeJson(r.error() == null ? "" : r.error()));
        return McpToolResponses.success(objectMapper, data, System.currentTimeMillis() - startTime);
    }

    private boolean isSimulatedDryRun(AbstractCommandExecutor.CommandResult result) {
        return result != null && result.output() != null && result.output().contains("[DRY-RUN]");
    }

    private static String escapeJson(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
