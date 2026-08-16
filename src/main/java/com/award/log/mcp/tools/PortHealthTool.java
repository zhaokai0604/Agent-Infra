package com.award.log.mcp.tools;

import com.award.log.mcp.McpToolResponses;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * TCP 端口连通性探测（纯 Java Socket，无 shell 依赖）。
 */
@Slf4j
@Component
public class PortHealthTool {

    private static final Pattern HOST_PATTERN = Pattern.compile(
            "^[a-zA-Z0-9]([a-zA-Z0-9\\-]{0,61}[a-zA-Z0-9])?(\\.[a-zA-Z0-9]([a-zA-Z0-9\\-]{0,61}[a-zA-Z0-9])?)*$|^(\\d{1,3}\\.){3}\\d{1,3}$|^localhost$"
    );

    private final ObjectMapper objectMapper;

    public PortHealthTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Tool(name = "checkPortConnectivity",
            description = "检测目标主机 TCP 端口是否可达，用于服务健康检查与故障定位（只读，不发送应用层数据）")
    public String checkPortConnectivity(
            @ToolParam(description = "目标主机名或 IP", required = true) String host,
            @ToolParam(description = "TCP 端口，1-65535", required = true) Integer port,
            @ToolParam(description = "连接超时毫秒，默认 3000，最大 15000", required = false) Integer timeoutMs
    ) throws JsonProcessingException {
        long start = System.currentTimeMillis();
        if (host == null || host.isBlank()) {
            return buildError("主机不能为空", start);
        }
        if (port == null || port < 1 || port > 65535) {
            return buildError("端口须在 1-65535 之间", start);
        }
        String targetHost = host.trim();
        if (!HOST_PATTERN.matcher(targetHost).matches() && !targetHost.contains(":")) {
            return buildError("主机名格式不合法", start);
        }
        int timeout = timeoutMs != null ? Math.min(Math.max(timeoutMs, 500), 15_000) : 3000;

        boolean reachable = false;
        String error = null;
        long connectMs = -1;

        long connectStart = System.nanoTime();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(targetHost, port), timeout);
            reachable = socket.isConnected();
            connectMs = (System.nanoTime() - connectStart) / 1_000_000;
        } catch (IOException e) {
            error = e.getMessage();
            connectMs = (System.nanoTime() - connectStart) / 1_000_000;
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("host", targetHost);
        data.put("port", port);
        data.put("reachable", reachable);
        data.put("connectMs", connectMs);
        data.put("timeoutMs", timeout);
        if (error != null) {
            data.put("error", error);
        }

        long duration = System.currentTimeMillis() - start;
        log.info("端口探测 {}:{} reachable={} 耗时={}ms", targetHost, port, reachable, duration);
        String dataJson = objectMapper.writeValueAsString(data);
        return McpToolResponses.successOrWarn(objectMapper, dataJson, duration, reachable);
    }

    private String buildError(String msg, long start) throws JsonProcessingException {
        return McpToolResponses.error(objectMapper, msg, start);
    }
}
