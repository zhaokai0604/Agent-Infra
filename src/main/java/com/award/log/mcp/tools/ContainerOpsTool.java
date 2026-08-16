package com.award.log.mcp.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 容器运维合一：读（list / inspect）+ 写（restart / stop，默认 dry-run）。
 */
@Component
@RequiredArgsConstructor
public class ContainerOpsTool {

    private final DockerTool dockerTool;

    @Tool(name = "containerOpsGateway",
            description = "Docker 容器运维合一：operation=list|inspect|restart|stop")
    public String containerOpsGateway(
            @ToolParam(description = "list|inspect|restart|stop", required = true) String operation,
            @ToolParam(description = "list 含已停止", required = false) Boolean includeStopped,
            @ToolParam(description = "容器名", required = false) String containerName,
            @ToolParam(description = "写操作 dryRun", required = false) Boolean dryRun,
            @ToolParam(description = "restart 确认", required = false) Boolean confirmRestart,
            @ToolParam(description = "stop 确认", required = false) Boolean confirmStop
    ) throws JsonProcessingException {
        return executeGateway(operation, includeStopped, containerName, dryRun, confirmRestart, confirmStop);
    }

    public String executeGateway(
            String operation,
            Boolean includeStopped,
            String containerName,
            Boolean dryRun,
            Boolean confirmRestart,
            Boolean confirmStop
    ) throws JsonProcessingException {
        return dockerTool.executeGateway(operation, includeStopped, containerName, dryRun, confirmRestart, confirmStop);
    }
}
