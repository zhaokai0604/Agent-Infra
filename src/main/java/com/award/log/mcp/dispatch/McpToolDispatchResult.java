package com.award.log.mcp.dispatch;

public record McpToolDispatchResult(boolean success, String data, String errorMessage) {

    public static McpToolDispatchResult ok(String data) {
        return new McpToolDispatchResult(true, data, null);
    }

    public static McpToolDispatchResult fail(String error) {
        return new McpToolDispatchResult(false, null, error);
    }
}
