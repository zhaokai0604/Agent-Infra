package com.award.log.agent;

import com.award.log.util.TestTimeSource;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpsPerceptionCacheTest {

    @Test
    void returnsFreshValueBeforeTtlExpires() {
        TestTimeSource timeSource = new TestTimeSource(100_000L);
        OpsPerceptionCache cache = new OpsPerceptionCache(timeSource);
        ReflectionTestUtils.setField(cache, "ttlMs", 60_000L);

        cache.put(Map.of("ok", true));

        assertEquals(Map.of("ok", true), cache.getIfFresh());
        assertTrue(cache.isEnabled());
    }

    @Test
    void returnsNullAfterInvalidate() {
        OpsPerceptionCache cache = new OpsPerceptionCache(new TestTimeSource(100_000L));
        ReflectionTestUtils.setField(cache, "ttlMs", 60_000L);
        cache.put(Map.of("ok", true));

        cache.invalidate();

        assertNull(cache.getIfFresh());
    }

    @Test
    void returnsNullWhenExpired() {
        TestTimeSource timeSource = new TestTimeSource(100_000L);
        OpsPerceptionCache cache = new OpsPerceptionCache(timeSource);
        ReflectionTestUtils.setField(cache, "ttlMs", 10_000L);
        ReflectionTestUtils.setField(cache, "cachedView", Map.of("ok", true));
        ReflectionTestUtils.setField(cache, "cachedAtMs", 80_000L);

        assertNull(cache.getIfFresh());
    }

    @Test
    void disabledWhenTtlNonPositive() {
        OpsPerceptionCache cache = new OpsPerceptionCache(new TestTimeSource(100_000L));
        ReflectionTestUtils.setField(cache, "ttlMs", 0L);
        assertFalse(cache.isEnabled());
    }
}
