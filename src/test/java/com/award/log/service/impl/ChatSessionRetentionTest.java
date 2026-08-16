package com.award.log.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class ChatSessionRetentionTest {

    private ChatSessionRetention retention;

    @BeforeEach
    void setUp() {
        retention = new ChatSessionRetention();
    }

    @Test
    void touchShouldIgnoreBlankSessionId() {
        retention.touch(" ");
        retention.evictExpired(null);
        assertDoesNotThrow(() -> retention.forget(null));
    }

    @Test
    void forgetShouldRemoveSession() {
        retention.touch("s1");
        retention.forget("s1");
        List<String> evicted = new ArrayList<>();
        retention.evictExpired(evicted::add);
        assertTrue(evicted.isEmpty());
    }

    @Test
    void evictExpiredShouldInvokeCallbackForStaleSessions() {
        List<String> evicted = new ArrayList<>();
        retention.touch("fresh");
        retention.evictExpired(evicted::add);
        assertFalse(evicted.contains("fresh"));
    }

    @Test
    void touchShouldTrimWhenExceedingMaxSessions() {
        for (int i = 0; i < ChatSessionRetention.MAX_SESSIONS + 5; i++) {
            retention.touch("session-" + i);
        }
        assertDoesNotThrow(() -> retention.touch("session-final"));
    }
}
