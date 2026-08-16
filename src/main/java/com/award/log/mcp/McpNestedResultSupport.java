package com.award.log.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 解包嵌套 {@link ToolResult} JSON 字符串（常见于组合工具聚合子工具返回值）。
 */
@Slf4j
public final class McpNestedResultSupport {

    private McpNestedResultSupport() {
    }

    public static Object unwrap(ObjectMapper objectMapper, String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            Object parsed = objectMapper.readValue(raw, Object.class);
            if (!(parsed instanceof Map<?, ?> map)) {
                return parsed;
            }
            Object success = map.get("success");
            if (!(success instanceof Boolean ok)) {
                return parsed;
            }
            if (!ok) {
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("success", false);
                Object errMsg = map.get("error");
                err.put("error", errMsg != null ? String.valueOf(errMsg) : "子工具执行失败");
                if (map.containsKey("durationMs")) {
                    err.put("durationMs", map.get("durationMs"));
                }
                return err;
            }
            Object data = map.get("data");
            if (!(data instanceof String str)) {
                return data;
            }
            try {
                return objectMapper.readValue(str, Object.class);
            } catch (Exception ignored) {
                return str;
            }
        } catch (Exception e) {
            log.warn("解析嵌套 ToolResult 失败: {}", e.getMessage());
            return raw;
        }
    }
}
