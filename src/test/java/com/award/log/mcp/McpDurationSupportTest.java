package com.award.log.mcp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpDurationSupportTest {

    @Test
    void normalize_treatsEpochLikeValueAsStartTime() {
        long start = System.currentTimeMillis() - 250L;
        long normalized = McpDurationSupport.normalize(start);
        assertTrue(normalized >= 200 && normalized <= 2000, "expected ~250ms, got " + normalized);
    }

    @Test
    void normalize_keepsRealDuration() {
        assertEquals(42L, McpDurationSupport.normalize(42L));
    }

    @Test
    void elapsedSince_nonNegative() {
        long start = System.currentTimeMillis();
        assertTrue(McpDurationSupport.elapsedSince(start) >= 0);
    }
}
