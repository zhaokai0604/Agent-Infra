package com.award.log.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class KafkaMonitorServiceImplTest {

    private KafkaMonitorServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new KafkaMonitorServiceImpl();
        ReflectionTestUtils.setField(service, "bootstrapServers", "127.0.0.1:65535");
    }

    @Test
    void snapshotShouldReturnStructuredPayloadEvenWhenOffline() {
        Map<String, Object> snapshot = service.snapshot();
        assertNotNull(snapshot);
        assertEquals("127.0.0.1:65535", snapshot.get("bootstrapServers"));
        assertTrue(snapshot.containsKey("online"));
        assertTrue(snapshot.containsKey("topics"));
        assertTrue(snapshot.containsKey("consumerGroups"));
    }

    @Test
    void snapshotShouldIncludeCounters() {
        Map<String, Object> snapshot = service.snapshot();
        assertTrue(snapshot.get("topicCount") instanceof Number);
        assertTrue(snapshot.get("groupCount") instanceof Number);
        assertTrue(snapshot.get("totalLag") instanceof Number);
    }
}
