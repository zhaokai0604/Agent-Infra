package com.award.log.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

/**
 * 统一解析 MCP 工具 JSON：支持 {@link ToolResult} 信封与组合工具裸 JSON（如 DiskAnalyzeTool）。
 */
@Slf4j
public final class McpToolPayloadParser {

    private McpToolPayloadParser() {
    }

    public static JsonNode parseRoot(ObjectMapper mapper, String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return mapper.readTree(raw);
        } catch (Exception e) {
            log.debug("parse tool payload root failed: {}", e.getMessage());
            return null;
        }
    }

    /** 返回业务 data 节点；若无信封则返回根对象。 */
    public static JsonNode parsePayload(ObjectMapper mapper, String raw) {
        JsonNode root = parseRoot(mapper, raw);
        if (root == null) {
            return null;
        }
        if (root.isObject() && root.has("success")) {
            if (!root.path("success").asBoolean(false)) {
                return null;
            }
            JsonNode data = root.get("data");
            if (data == null) {
                return null;
            }
            if (data.isTextual()) {
                try {
                    return mapper.readTree(data.asText());
                } catch (Exception e) {
                    return data;
                }
            }
            return data;
        }
        return root;
    }

    public static boolean isSuccessful(ObjectMapper mapper, String raw) {
        JsonNode root = parseRoot(mapper, raw);
        if (root == null) {
            return false;
        }
        if (root.isObject() && root.has("success")) {
            return root.path("success").asBoolean(false);
        }
        return true;
    }

    public static String errorMessage(ObjectMapper mapper, String raw) {
        JsonNode root = parseRoot(mapper, raw);
        if (root == null) {
            return "工具返回为空";
        }
        if (root.has("error") && !root.path("error").asText("").isBlank()) {
            return root.path("error").asText();
        }
        if (root.has("message") && !root.path("message").asText("").isBlank()) {
            return root.path("message").asText();
        }
        return "工具执行失败";
    }

    /** 读取信封 status（SUCCESS/WARN/ERROR）；缺省按 success 推导。 */
    public static String statusOf(ObjectMapper mapper, String raw) {
        JsonNode root = parseRoot(mapper, raw);
        if (root == null) {
            return ToolResult.STATUS_ERROR;
        }
        String status = root.path("status").asText("").trim();
        if (!status.isEmpty()) {
            return status.toUpperCase();
        }
        if (root.isObject() && root.has("success")) {
            return root.path("success").asBoolean(false) ? ToolResult.STATUS_SUCCESS : ToolResult.STATUS_ERROR;
        }
        return ToolResult.STATUS_SUCCESS;
    }
}
