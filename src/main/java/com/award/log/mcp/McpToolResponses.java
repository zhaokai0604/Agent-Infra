package com.award.log.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 各 MCP 工具统一信封：SUCCESS / WARN / ERROR，避免业务失败仍外层 success=true。
 */
public final class McpToolResponses {

    private McpToolResponses() {
    }

    public static String success(ObjectMapper mapper, String dataJson, long durationMs)
            throws JsonProcessingException {
        return mapper.writeValueAsString(ToolResult.success(dataJson, durationMs, false));
    }

    public static String warn(ObjectMapper mapper, String dataJson, long durationMs)
            throws JsonProcessingException {
        return mapper.writeValueAsString(ToolResult.warn(dataJson, durationMs));
    }

    public static String error(ObjectMapper mapper, String message, long durationOrStart)
            throws JsonProcessingException {
        return mapper.writeValueAsString(ToolResult.error(message, durationOrStart));
    }

    /** 探测类：业务未通过时用 WARN（调用本身完成），通过用 SUCCESS。 */
    public static String successOrWarn(ObjectMapper mapper, String dataJson, long durationMs, boolean healthy)
            throws JsonProcessingException {
        return healthy ? success(mapper, dataJson, durationMs) : warn(mapper, dataJson, durationMs);
    }
}
