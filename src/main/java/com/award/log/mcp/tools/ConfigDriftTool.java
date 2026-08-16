package com.award.log.mcp.tools;

import com.award.log.mcp.McpToolResponses;
import com.award.log.security.OpsPathPolicy;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 配置文件漂移探测（只读）：对比当前 SHA-256 与进程内上次快照。
 */
@Slf4j
@Component
public class ConfigDriftTool {

    private final OpsPathPolicy opsPathPolicy;
    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<String, String> baselineHashes = new ConcurrentHashMap<>();

    public ConfigDriftTool(OpsPathPolicy opsPathPolicy, ObjectMapper objectMapper) {
        this.opsPathPolicy = opsPathPolicy;
        this.objectMapper = objectMapper;
    }

    @Tool(name = "checkConfigDrift",
            description = "检测白名单内配置文件相对上次快照是否发生变化（只读 checksum，不写配置）")
    public String checkConfigDrift(
            @ToolParam(description = "配置文件绝对路径，须在 config 白名单内", required = true) String configPath
    ) throws JsonProcessingException {
        long start = System.currentTimeMillis();
        if (configPath == null || configPath.isBlank()) {
            return McpToolResponses.error(objectMapper, "configPath 不能为空", start);
        }
        String path = configPath.trim();
        if (!opsPathPolicy.isAllowedConfigCheckPath(path)) {
            return McpToolResponses.error(objectMapper, opsPathPolicy.rejectReason("配置文件路径"), start);
        }
        Path p = Path.of(path);
        if (!Files.isRegularFile(p)) {
            return McpToolResponses.error(objectMapper, "文件不存在或不可读: " + path, start);
        }

        try {
            String current = sha256Hex(Files.readAllBytes(p));
            String previous = baselineHashes.put(path, current);
            boolean drifted = previous != null && !previous.equals(current);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("configPath", path);
            body.put("currentSha256", current);
            body.put("previousSha256", previous);
            body.put("drifted", drifted);
            body.put("firstObservation", previous == null);
            body.put("message", drifted ? "检测到配置内容相对上次快照已变化" : (previous == null
                    ? "已建立基线快照，下次调用可检测漂移" : "与上次快照一致"));

            long duration = System.currentTimeMillis() - start;
            String dataJson = objectMapper.writeValueAsString(body);
            return drifted
                    ? McpToolResponses.warn(objectMapper, dataJson, duration)
                    : McpToolResponses.success(objectMapper, dataJson, duration);
        } catch (Exception e) {
            return McpToolResponses.error(objectMapper, "漂移检测失败: " + e.getMessage(), start);
        }
    }

    private static String sha256Hex(byte[] bytes) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(md.digest(bytes));
    }
}
