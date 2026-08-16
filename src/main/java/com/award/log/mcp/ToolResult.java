package com.award.log.mcp;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ToolResult<T> {
    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_WARN = "WARN";
    public static final String STATUS_ERROR = "ERROR";

    private boolean success;
    private T data;
    private String error;
    private Long durationMs;
    private Boolean cacheHit;
    /** SUCCESS / WARN / ERROR；缺省时由 success 推导 */
    private String status;

    public ToolResult(boolean success, T data, String error, Long durationMs, Boolean cacheHit) {
        this(success, data, error, durationMs, cacheHit, success ? STATUS_SUCCESS : STATUS_ERROR);
    }

    public ToolResult(boolean success, T data, String error, Long durationMs, Boolean cacheHit, String status) {
        this.success = success;
        this.data = data;
        this.error = error;
        this.durationMs = durationMs;
        this.cacheHit = cacheHit;
        this.status = status;
    }

    public static <T> ToolResult<T> success(T data, long durationMs, boolean cacheHit) {
        return new ToolResult<>(true, data, null, durationMs, cacheHit, STATUS_SUCCESS);
    }

    public static <T> ToolResult<T> success(T data, long durationMs) {
        return new ToolResult<>(true, data, null, durationMs, false, STATUS_SUCCESS);
    }

    public static <T> ToolResult<T> warn(T data, long durationMs) {
        return new ToolResult<>(true, data, null, durationMs, false, STATUS_WARN);
    }

    public static <T> ToolResult<T> error(String errorMsg, long durationMs) {
        return new ToolResult<>(false, null, errorMsg, McpDurationSupport.normalize(durationMs), false, STATUS_ERROR);
    }

    /** 从 startTime 计算真实耗时（推荐用于 catch / 早退错误路径）。 */
    public static <T> ToolResult<T> errorSince(long startTimeMillis, String errorMsg) {
        return error(errorMsg, McpDurationSupport.elapsedSince(startTimeMillis));
    }
}
