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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class NetworkTool extends AbstractCommandExecutor {

    private static final Pattern PING_LINE_PATTERN = Pattern.compile(
        "(\\d+) packets transmitted, (\\d+) (?:packets )?received, (\\d+(?:\\.\\d+)?)%? packet loss"
    );
    private static final Pattern PING_TIME_PATTERN = Pattern.compile(
        "rtt min/avg/max/mdev = ([\\d.]+)/([\\d.]+)/([\\d.]+)/([\\d.]+)"
    );
    private static final Pattern TRACE_LINE_PATTERN = Pattern.compile(
        "^\\s*(\\d+)\\s+([\\d.]+|\\*|\\S+)\\s+([\\d.]+|\\*|\\S+)\\s+([\\d.]+|\\*|\\S+)"
    );
    /** Windows ping 英文/中文大致兼容 */
    private static final Pattern WIN_PING_SENT = Pattern.compile(
            "(?:Sent|已发送)\\s*=\\s*(\\d+).*?(?:Received|已接收)\\s*=\\s*(\\d+).*?(?:Lost|丢失)\\s*=\\s*(\\d+)",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern WIN_PING_LOSS = Pattern.compile("\\(\\s*(\\d+)%?\\s*(?:loss|丢失)\\s*\\)", Pattern.CASE_INSENSITIVE);
    private static final Pattern WIN_PING_AVG = Pattern.compile(
            "(?:Average|平均)\\s*=\\s*(\\d+)\\s*ms", Pattern.CASE_INSENSITIVE);

    private final ObjectMapper objectMapper;
    private final MinPrivilegeExecutor minPrivilegeExecutor;

    @Autowired
    public NetworkTool(
            @Autowired(required = false) ExecutorService mcpToolExecutor,
            ObjectMapper objectMapper,
            MinPrivilegeExecutor minPrivilegeExecutor) {
        super(mcpToolExecutor != null ? mcpToolExecutor : Executors.newCachedThreadPool());
        this.objectMapper = objectMapper;
        this.minPrivilegeExecutor = minPrivilegeExecutor;
    }

    @Tool(name = "diagnoseNetwork", description = "执行网络诊断，支持 ping 和 traceroute，检测网络连通性、延迟和路由路径")
    public String diagnoseNetwork(
            @ToolParam(description = "目标主机名或 IP 地址（必填）", required = true) String target,
            @ToolParam(description = "诊断类型：ping 或 traceroute（默认 ping）", required = false) String type,
            @ToolParam(description = "ping 包数量或 traceroute 最大跳数（默认 4）", required = false) Integer count
    ) throws JsonProcessingException {
        long startTime = System.currentTimeMillis();

        if (target == null || target.isBlank()) {
            return McpToolResponses.error(objectMapper, "目标地址不能为空", startTime);
        }

        String targetHost = target.trim();
        String diagnoseType = (type != null && !type.isBlank()) ? type.toLowerCase() : "ping";
        int maxCount = count != null ? count : 4;

        log.info("开始网络诊断，目标: {}, 类型: {}, 次数: {}", targetHost, diagnoseType, maxCount);

        try {
            long duration = System.currentTimeMillis() - startTime;
            if ("traceroute".equals(diagnoseType) || "trace".equals(diagnoseType)) {
                TracerouteResult tr = runTraceroute(targetHost, maxCount);
                duration = System.currentTimeMillis() - startTime;
                log.info("网络诊断完成，目标: {}, 类型: {}, 耗时: {}ms", targetHost, diagnoseType, duration);
                if (tr.commandFailed()) {
                    return McpToolResponses.error(objectMapper, "traceroute 命令执行失败", startTime);
                }
                return McpToolResponses.success(objectMapper, tr.dataJson(), duration);
            }

            PingResult pr = runPing(targetHost, maxCount);
            duration = System.currentTimeMillis() - startTime;
            log.info("网络诊断完成，目标: {}, 类型: {}, 耗时: {}ms", targetHost, diagnoseType, duration);
            if (pr.commandFailed()) {
                return McpToolResponses.error(objectMapper, "ping 命令执行失败", startTime);
            }
            if (!pr.pingHealthy()) {
                return McpToolResponses.warn(objectMapper, pr.dataJson(), duration);
            }
            return McpToolResponses.success(objectMapper, pr.dataJson(), duration);

        } catch (Exception e) {
            log.error("执行网络诊断时发生异常", e);
            return McpToolResponses.error(objectMapper, "执行网络诊断时发生异常: " + e.getMessage(), startTime);
        }
    }

    private record PingResult(String dataJson, boolean commandFailed, boolean pingHealthy) {}

    private record TracerouteResult(String dataJson, boolean commandFailed) {}

    private PingResult runPing(String target, int count) {
        AbstractCommandExecutor.CommandResult result = minPrivilegeExecutor.executeSafely(
                OsRuntime.isWindows()
                        ? List.of("ping", "-n", String.valueOf(count), target)
                        : List.of("ping", "-c", String.valueOf(count), target)
        );

        String blob = result.output() != null ? result.output() : "";
        String[] lines = blob.split("\n");

        double packetLoss = 100.0;
        double avgLatency = -1.0;
        int transmitted = 0, received = 0;

        if (OsRuntime.isWindows()) {
            Matcher sm = WIN_PING_SENT.matcher(blob);
            if (sm.find()) {
                transmitted = Integer.parseInt(sm.group(1));
                received = Integer.parseInt(sm.group(2));
                int lost = Integer.parseInt(sm.group(3));
                int denom = Math.max(transmitted, 1);
                packetLoss = 100.0 * lost / denom;
            }
            Matcher lm = WIN_PING_LOSS.matcher(blob);
            if (lm.find()) {
                packetLoss = Double.parseDouble(lm.group(1));
            }
            Matcher am = WIN_PING_AVG.matcher(blob);
            if (am.find()) {
                avgLatency = Double.parseDouble(am.group(1));
            }
        } else {
            for (String line : lines) {
                Matcher pingMatcher = PING_LINE_PATTERN.matcher(line);
                if (pingMatcher.find()) {
                    transmitted = Integer.parseInt(pingMatcher.group(1));
                    received = Integer.parseInt(pingMatcher.group(2));
                    packetLoss = Double.parseDouble(pingMatcher.group(3));
                }

                Matcher timeMatcher = PING_TIME_PATTERN.matcher(line);
                if (timeMatcher.find()) {
                    avgLatency = Double.parseDouble(timeMatcher.group(2));
                }
            }
        }

        boolean pingHealthy = received > 0 && packetLoss < 100.0;
        boolean commandFailed = blob.isBlank() && !result.success();

        String dataJson = String.format(
            "{\"type\":\"ping\",\"target\":\"%s\",\"transmitted\":%d,\"received\":%d," +
            "\"packetLossPercent\":%.1f,\"avgLatencyMs\":%.2f,\"success\":%s}",
            target, transmitted, received, packetLoss, avgLatency, pingHealthy
        );
        return new PingResult(dataJson, commandFailed, pingHealthy);
    }

    private TracerouteResult runTraceroute(String target, int maxHops) {
        AbstractCommandExecutor.CommandResult result = minPrivilegeExecutor.executeSafely(
                OsRuntime.isWindows()
                        ? List.of("tracert", "-d", "-h", String.valueOf(maxHops), target)
                        : List.of("traceroute", "-m", String.valueOf(maxHops), "-w", "2", target)
        );

        List<String> hops = new ArrayList<>();
        String[] lines = result.output() != null ? result.output().split("\n") : new String[0];

        for (String line : lines) {
            Matcher matcher = TRACE_LINE_PATTERN.matcher(line);
            if (matcher.find()) {
                int hopNum = Integer.parseInt(matcher.group(1));
                String hop1 = matcher.group(2).equals("*") ? "timeout" : matcher.group(2);
                String hop2 = matcher.group(3).equals("*") ? "timeout" : matcher.group(3);
                String hop3 = matcher.group(4).equals("*") ? "timeout" : matcher.group(4);

                hops.add(String.format(
                        "{\"hop\":%d,\"rtt1\":\"%s\",\"rtt2\":\"%s\",\"rtt3\":\"%s\"}",
                        hopNum, hop1, hop2, hop3
                ));
            }
        }

        String output = result.output() != null ? result.output() : "";
        boolean commandFailed = output.isBlank() && !result.success();

        if (OsRuntime.isWindows() && hops.isEmpty() && !output.isBlank()) {
            String esc = output.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "").replace("\n", "\\n");
            String dataJson = String.format(
                    "{\"type\":\"traceroute\",\"target\":\"%s\",\"hopCount\":0,\"hops\":[],\"raw\":\"%s\"}",
                    target, esc.length() > 8000 ? esc.substring(0, 8000) + "...(truncated)" : esc
            );
            return new TracerouteResult(dataJson, commandFailed);
        }

        String dataJson = String.format(
                "{\"type\":\"traceroute\",\"target\":\"%s\",\"hopCount\":%d,\"hops\":[%s]}",
                target, hops.size(), String.join(",", hops)
        );
        return new TracerouteResult(dataJson, commandFailed);
    }
}
