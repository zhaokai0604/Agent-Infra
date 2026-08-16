package com.award.log.mcp.tools;

import com.award.log.mcp.AbstractCommandExecutor;
import com.award.log.mcp.McpToolResponses;
import com.award.log.mcp.MinPrivilegeExecutor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * Docker 容器只读巡检（docker ps / inspect）。
 */
@Slf4j
@Component
public class DockerTool extends AbstractCommandExecutor {

    private static final Pattern CONTAINER_NAME = Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9_.-]{0,127}$");
    private static final int MAX_OUTPUT = 100_000;

    private final ObjectMapper objectMapper;
    private final MinPrivilegeExecutor minPrivilegeExecutor;

    @Autowired
    public DockerTool(
            @Autowired(required = false) ExecutorService mcpToolExecutor,
            ObjectMapper objectMapper,
            MinPrivilegeExecutor minPrivilegeExecutor) {
        super(mcpToolExecutor != null ? mcpToolExecutor : Executors.newCachedThreadPool());
        this.objectMapper = objectMapper;
        this.minPrivilegeExecutor = minPrivilegeExecutor;
    }

    @Tool(name = "listDockerContainers",
            description = "列出 Docker 容器状态（docker ps），用于容器化环境运维巡检；未安装 Docker 时返回明确错误")
    public String listDockerContainers(
            @ToolParam(description = "是否包含已停止容器（-a），默认 false", required = false) Boolean includeStopped
    ) throws JsonProcessingException {
        long start = System.currentTimeMillis();
        List<String> cmd = new ArrayList<>();
        cmd.add("docker");
        cmd.add("ps");
        if (Boolean.TRUE.equals(includeStopped)) {
            cmd.add("-a");
        }
        cmd.add("--format");
        cmd.add("table {{.ID}}\\t{{.Names}}\\t{{.Status}}\\t{{.Ports}}\\t{{.Image}}");

        CommandResult r = minPrivilegeExecutor.executeSafely(cmd, 30_000L);
        return wrapCommandResult("docker-ps", cmd, r, start, r.success());
    }

    @Tool(name = "inspectDockerContainer",
            description = "查看单个 Docker 容器详情（docker inspect --format 摘要），容器名/ID 仅允许字母数字与 ._-")
    public String inspectDockerContainer(
            @ToolParam(description = "容器名称或 ID", required = true) String containerName
    ) throws JsonProcessingException {
        long start = System.currentTimeMillis();
        if (containerName == null || containerName.isBlank()) {
            return McpToolResponses.error(objectMapper,"容器名称不能为空", start);
        }
        String name = containerName.trim();
        if (!CONTAINER_NAME.matcher(name).matches()) {
            return McpToolResponses.error(objectMapper,"容器名称格式不合法", start);
        }

        List<String> cmd = List.of(
                "docker", "inspect", name,
                "--format", "{{.Name}}|{{.State.Status}}|{{.State.Health.Status}}|{{.Config.Image}}|{{range .NetworkSettings.Networks}}{{.IPAddress}} {{end}}"
        );
        CommandResult r = minPrivilegeExecutor.executeSafely(cmd, 30_000L);
        return wrapCommandResult("docker-inspect", cmd, r, start, r.success());
    }

    @Tool(name = "restartDockerContainer",
            description = "重启 Docker 容器（写操作）：默认 dryRun 预览；真实执行需 dryRun=false 且 confirmRestart=true")
    public String restartDockerContainer(
            @ToolParam(description = "容器名称或 ID", required = true) String containerName,
            @ToolParam(description = "true/null=预览", required = false) Boolean dryRun,
            @ToolParam(description = "真实重启须 true", required = false) Boolean confirmRestart
    ) throws JsonProcessingException {
        long start = System.currentTimeMillis();
        if (containerName == null || containerName.isBlank()) {
            return McpToolResponses.error(objectMapper,"容器名称不能为空", start);
        }
        String name = containerName.trim();
        if (!CONTAINER_NAME.matcher(name).matches()) {
            return McpToolResponses.error(objectMapper,"容器名称格式不合法", start);
        }
        com.award.log.security.ChatWriteExecutionPolicy.ResolvedRestart write =
                com.award.log.security.ChatWriteExecutionPolicy.resolveRestart(dryRun, confirmRestart);
        boolean isDryRun = write.dryRun();
        if (!isDryRun && !write.confirmRestart()) {
            return McpToolResponses.error(objectMapper,"真实重启需 dryRun=false 且 confirmRestart=true", start);
        }
        List<String> cmd = List.of("docker", "restart", name);
        if (isDryRun) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("mode", "DRY-RUN");
            body.put("plan", String.join(" ", cmd));
            body.put("containerName", name);
            long duration = System.currentTimeMillis() - start;
            return McpToolResponses.success(objectMapper, objectMapper.writeValueAsString(body), duration);
        }
        CommandResult r = minPrivilegeExecutor.executeSafely(cmd, 60_000L);
        if (isSimulatedDryRun(r)) {
            return McpToolResponses.error(objectMapper,"全局演练模式已启用：docker restart 被模拟执行，容器未实际重启", start);
        }
        if (!r.success()) {
            return McpToolResponses.error(objectMapper,"docker restart failed: " + (r.error() != null ? r.error() : r.output()), start);
        }
        return buildWriteSuccess("docker-restart", name, cmd, r, start);
    }

    @Tool(name = "stopDockerContainer",
            description = "停止 Docker 容器（写操作）：默认 dryRun；真实停止需 dryRun=false 且 confirmStop=true")
    public String stopDockerContainer(
            @ToolParam(description = "容器名称或 ID", required = true) String containerName,
            @ToolParam(description = "true/null=预览", required = false) Boolean dryRun,
            @ToolParam(description = "真实停止须 true", required = false) Boolean confirmStop
    ) throws JsonProcessingException {
        long start = System.currentTimeMillis();
        if (containerName == null || containerName.isBlank()) {
            return McpToolResponses.error(objectMapper,"容器名称不能为空", start);
        }
        String name = containerName.trim();
        if (!CONTAINER_NAME.matcher(name).matches()) {
            return McpToolResponses.error(objectMapper,"容器名称格式不合法", start);
        }
        com.award.log.security.ChatWriteExecutionPolicy.ResolvedStop write =
                com.award.log.security.ChatWriteExecutionPolicy.resolveStop(dryRun, confirmStop);
        boolean isDryRun = write.dryRun();
        if (!isDryRun && !write.confirmStop()) {
            return McpToolResponses.error(objectMapper,"真实停止需 dryRun=false 且 confirmStop=true", start);
        }
        List<String> cmd = List.of("docker", "stop", name);
        if (isDryRun) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("mode", "DRY-RUN");
            body.put("plan", String.join(" ", cmd));
            body.put("containerName", name);
            long duration = System.currentTimeMillis() - start;
            return McpToolResponses.success(objectMapper, objectMapper.writeValueAsString(body), duration);
        }
        CommandResult r = minPrivilegeExecutor.executeSafely(cmd, 60_000L);
        if (isSimulatedDryRun(r)) {
            return McpToolResponses.error(objectMapper,"全局演练模式已启用：docker stop 被模拟执行，容器未实际停止", start);
        }
        if (!r.success()) {
            return McpToolResponses.error(objectMapper,"docker stop failed: " + (r.error() != null ? r.error() : r.output()), start);
        }
        return buildWriteSuccess("docker-stop", name, cmd, r, start);
    }

    /** MCP HTTP 网关：operation = list | inspect | restart | stop */
    public String executeGateway(
            String operation,
            Boolean includeStopped,
            String containerName,
            Boolean dryRun,
            Boolean confirmRestart,
            Boolean confirmStop
    ) throws JsonProcessingException {
        String op = operation == null || operation.isBlank() ? "list" : operation.trim().toLowerCase();
        return switch (op) {
            case "list", "ps" -> listDockerContainers(includeStopped);
            case "inspect" -> inspectDockerContainer(containerName);
            case "restart" -> restartDockerContainer(containerName, dryRun, confirmRestart);
            case "stop" -> stopDockerContainer(containerName, dryRun, confirmStop);
            default -> McpToolResponses.error(objectMapper, "未知 operation: " + operation + "；可选 list|inspect|restart|stop",
                    System.currentTimeMillis());
        };
    }

    private String wrapCommandResult(String tool, List<String> cmd, CommandResult r, long start, boolean ok)
            throws JsonProcessingException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tool", tool);
        body.put("command", String.join(" ", cmd));
        body.put("success", ok);
        body.put("durationMs", System.currentTimeMillis() - start);
        if (r.success() && r.output() != null) {
            String out = r.output();
            if (out.length() > MAX_OUTPUT) {
                out = out.substring(0, MAX_OUTPUT) + "\n...[truncated]";
            }
            body.put("output", out);
        } else {
            body.put("error", r.error() != null ? r.error() : ("exit=" + r.exitCode()));
            if (r.output() != null && !r.output().isBlank()) {
                body.put("stderrOrOutput", truncate(r.output()));
            }
        }
        long duration = System.currentTimeMillis() - start;
        String dataJson = objectMapper.writeValueAsString(body);
        if (ok) {
            return McpToolResponses.success(objectMapper, dataJson, duration);
        }
        String errMsg = r.error() != null ? r.error() : "docker command failed";
        if (r.output() != null && !r.output().isBlank()) {
            return McpToolResponses.warn(objectMapper, dataJson, duration);
        }
        return McpToolResponses.error(objectMapper, errMsg, start);
    }

    private String truncate(String s) {
        return s.length() > 4000 ? s.substring(0, 4000) + "..." : s;
    }

    private String buildWriteSuccess(String tool, String containerName, List<String> cmd, CommandResult r, long start)
            throws JsonProcessingException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("mode", "EXECUTED");
        body.put("tool", tool);
        body.put("containerName", containerName);
        body.put("command", String.join(" ", cmd));
        body.put("success", true);
        if (r.output() != null && !r.output().isBlank()) {
            body.put("output", truncate(r.output()));
        }
        long duration = System.currentTimeMillis() - start;
        return McpToolResponses.success(objectMapper, objectMapper.writeValueAsString(body), duration);
    }

    private boolean isSimulatedDryRun(CommandResult result) {
        return result != null && result.output() != null && result.output().contains("[DRY-RUN]");
    }
}
