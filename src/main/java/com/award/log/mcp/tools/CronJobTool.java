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
 * 计划任务只读巡检：Linux crontab / cron.d；Windows schtasks。
 */
@Slf4j
@Component
public class CronJobTool extends AbstractCommandExecutor {

    private static final int MAX_OUTPUT = 80_000;

    private final ObjectMapper objectMapper;
    private final MinPrivilegeExecutor minPrivilegeExecutor;

    @Autowired
    public CronJobTool(
            @Autowired(required = false) ExecutorService mcpToolExecutor,
            ObjectMapper objectMapper,
            MinPrivilegeExecutor minPrivilegeExecutor) {
        super(mcpToolExecutor != null ? mcpToolExecutor : Executors.newCachedThreadPool());
        this.objectMapper = objectMapper;
        this.minPrivilegeExecutor = minPrivilegeExecutor;
    }

    @Tool(name = "listCronJobs",
            description = "列出当前用户或系统的计划任务（Linux: crontab -l 与 /etc/cron.d；Windows: schtasks 摘要），只读")
    public String listCronJobs(
            @ToolParam(description = "范围：user（当前用户，默认）| system（系统级 cron.d / schtasks）", required = false) String scope
    ) throws JsonProcessingException {
        long start = System.currentTimeMillis();
        String s = scope == null || scope.isBlank() ? "user" : scope.trim().toLowerCase();

        if (OsRuntime.isWindows()) {
            List<String> cmd = "system".equals(s)
                    ? List.of("schtasks", "/query", "/fo", "LIST", "/v")
                    : List.of("schtasks", "/query", "/fo", "TABLE", "/nh");
            CommandResult r = minPrivilegeExecutor.executeSafely(cmd, 60_000L);
            return wrap("schtasks", cmd, r, start);
        }

        if ("system".equals(s)) {
            List<String> cmd = List.of("bash", "-c",
                    "echo '=== /etc/cron.d ==='; ls -la /etc/cron.d 2>/dev/null; "
                            + "for f in /etc/cron.d/*; do [ -f \"$f\" ] && echo \"--- $f ---\" && cat \"$f\"; done; "
                            + "echo '=== crontab root ==='; crontab -l -u root 2>/dev/null || echo '(no root crontab)'");
            CommandResult r = minPrivilegeExecutor.executeSafely(cmd, 45_000L);
            return wrap("cron-system", cmd, r, start);
        }

        List<String> cmd = List.of("bash", "-c", "crontab -l 2>/dev/null || echo '(no crontab for current user)'");
        CommandResult r = minPrivilegeExecutor.executeSafely(cmd, 20_000L);
        return wrap("crontab-user", cmd, r, start);
    }

    private String wrap(String tool, List<String> cmd, CommandResult r, long start) throws JsonProcessingException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tool", tool);
        body.put("command", String.join(" ", cmd));
        body.put("success", r.success() || (r.output() != null && !r.output().isBlank()));
        body.put("durationMs", System.currentTimeMillis() - start);
        String out = r.output();
        if (out != null && out.length() > MAX_OUTPUT) {
            out = out.substring(0, MAX_OUTPUT) + "\n...[truncated]";
        }
        body.put("output", out != null ? out : "");
        if (!r.success() && r.error() != null) {
            body.put("warning", r.error());
        }
        long duration = System.currentTimeMillis() - start;
        boolean ok = Boolean.TRUE.equals(body.get("success"));
        String dataJson = objectMapper.writeValueAsString(body);
        if (ok) {
            return McpToolResponses.success(objectMapper, dataJson, duration);
        }
        return McpToolResponses.warn(objectMapper, dataJson, duration);
    }
}
