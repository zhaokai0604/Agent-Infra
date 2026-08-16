package com.award.log.mcp.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 进程运维合一：读（list）+ 写（kill，默认 dry-run）。
 */
@Component
@RequiredArgsConstructor
public class ProcessOpsTool {

    private final ProcessTool processTool;

    @Tool(name = "processOpsGateway",
            description = "进程运维合一：operation=list|kill")
    public String processOpsGateway(
            @ToolParam(description = "list|kill", required = true) String operation,
            @ToolParam(description = "list 最小 CPU%", required = false) Double minCpu,
            @ToolParam(description = "list 最小内存%", required = false) Double minMem,
            @ToolParam(description = "kill PID", required = false) Integer pid,
            @ToolParam(description = "TERM|KILL", required = false) String signal,
            @ToolParam(description = "dryRun", required = false) Boolean dryRun,
            @ToolParam(description = "confirmKill", required = false) Boolean confirmKill
    ) throws JsonProcessingException {
        return executeGateway(operation, minCpu, minMem, pid, signal, dryRun, confirmKill);
    }

    public String executeGateway(
            String operation,
            Double minCpu,
            Double minMem,
            Integer pid,
            String signal,
            Boolean dryRun,
            Boolean confirmKill
    ) throws JsonProcessingException {
        return processTool.executeGateway(operation, minCpu, minMem, pid, signal, dryRun, confirmKill);
    }
}
