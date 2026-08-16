package com.award.log.mcp;

/**
 * 统一 MCP 耗时字段：兼容误将 startTime 当作 durationMs 传入的历史写法。
 */
public final class McpDurationSupport {

    private static final long EARLIEST_EPOCH_MS = 946_684_800_000L; // 2000-01-01

    private McpDurationSupport() {
    }

    public static long normalize(long durationOrStartTime) {
        long value = Math.max(0L, durationOrStartTime);
        long now = System.currentTimeMillis();
        if (value >= EARLIEST_EPOCH_MS && value <= now + 60_000L) {
            return Math.max(0L, now - value);
        }
        return value;
    }

    public static long elapsedSince(long startTimeMillis) {
        return Math.max(0L, System.currentTimeMillis() - startTimeMillis);
    }
}
