package com.award.log.mcp.tools;

import com.award.log.mcp.AbstractCommandExecutor;
import com.award.log.mcp.McpToolResponses;
import com.award.log.mcp.MinPrivilegeExecutor;
import com.award.log.util.OsRuntime;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 防火墙状态只读巡检（ufw / firewalld / netsh）。
 */
@Slf4j
@Component
public class FirewallTool extends AbstractCommandExecutor {

    private static final int MAX_OUTPUT = 50_000;

    private final ObjectMapper objectMapper;
    private final MinPrivilegeExecutor minPrivilegeExecutor;

    @Autowired
    public FirewallTool(
            @Autowired(required = false) ExecutorService mcpToolExecutor,
            ObjectMapper objectMapper,
            MinPrivilegeExecutor minPrivilegeExecutor) {
        super(mcpToolExecutor != null ? mcpToolExecutor : Executors.newCachedThreadPool());
        this.objectMapper = objectMapper;
        this.minPrivilegeExecutor = minPrivilegeExecutor;
    }

    @Tool(name = "checkFirewallStatus",
            description = "检查主机防火墙状态（Linux 依次尝试 ufw、firewalld；Windows 使用 netsh advfirewall），只读")
    public String checkFirewallStatus() throws JsonProcessingException {
        long start = System.currentTimeMillis();

        if (OsRuntime.isWindows()) {
            List<String> cmd = List.of("netsh", "advfirewall", "show", "allprofiles", "state");
            CommandResult r = minPrivilegeExecutor.executeSafely(cmd, 30_000L);
            return wrap("netsh-advfirewall", cmd, r, start, r.success());
        }

        List<Probe> probes = List.of(
                new Probe("ufw", List.of("ufw", "status", "verbose")),
                new Probe("firewalld", List.of("firewall-cmd", "--state")),
                new Probe("iptables-summary", List.of("bash", "-c", "iptables -L -n 2>/dev/null | head -n 40 || echo 'iptables not available'"))
        );

        List<Map<String, Object>> results = new ArrayList<>();
        boolean anySuccess = false;

        for (Probe probe : probes) {
            CommandResult r = minPrivilegeExecutor.executeSafely(probe.command, 25_000L);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("backend", probe.name);
            entry.put("command", String.join(" ", probe.command));
            entry.put("success", r.success());
            if (r.success() && r.output() != null) {
                anySuccess = true;
                String out = r.output();
                entry.put("output", out.length() > 15_000 ? out.substring(0, 15_000) + "\n...[truncated]" : out);
            } else {
                entry.put("error", r.error() != null ? r.error() : ("exit=" + r.exitCode()));
            }
            results.add(entry);
            if (r.success() && !"iptables-summary".equals(probe.name)) {
                break;
            }
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tool", "firewall-status");
        body.put("probes", results);
        body.put("anyBackendResponded", anySuccess);
        body.put("durationMs", System.currentTimeMillis() - start);

        long duration = System.currentTimeMillis() - start;
        String dataJson = objectMapper.writeValueAsString(body);
        return McpToolResponses.successOrWarn(objectMapper, dataJson, duration, anySuccess);
    }

    private String wrap(String tool, List<String> cmd, CommandResult r, long start, boolean ok)
            throws JsonProcessingException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tool", tool);
        body.put("command", String.join(" ", cmd));
        body.put("success", ok);
        body.put("durationMs", System.currentTimeMillis() - start);
        if (r.output() != null) {
            String out = r.output();
            body.put("output", out.length() > MAX_OUTPUT ? out.substring(0, MAX_OUTPUT) + "\n...[truncated]" : out);
        }
        if (!ok) {
            body.put("error", r.error());
        }
        long duration = System.currentTimeMillis() - start;
        String dataJson = objectMapper.writeValueAsString(body);
        if (ok) {
            return McpToolResponses.success(objectMapper, dataJson, duration);
        }
        if (r.output() != null && !r.output().isBlank()) {
            return McpToolResponses.warn(objectMapper, dataJson, duration);
        }
        String errMsg = r.error() != null && !r.error().isBlank() ? r.error() : "防火墙状态查询失败";
        return McpToolResponses.error(objectMapper, errMsg, start);
    }

    private record Probe(String name, List<String> command) {}
}
