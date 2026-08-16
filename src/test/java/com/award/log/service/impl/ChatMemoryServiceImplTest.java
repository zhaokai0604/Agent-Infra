package com.award.log.service.impl;

import com.award.log.service.ChatMemoryService.MemoryEntry;
import com.award.log.service.ChatMemoryService.MemorySummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class ChatMemoryServiceImplTest {

    private ChatMemoryServiceImpl service;
    private ChatSessionRetention sessionRetention;

    @BeforeEach
    void setUp() {
        service = new ChatMemoryServiceImpl();
        sessionRetention = new ChatSessionRetention();
        ReflectionTestUtils.setField(service, "sessionRetention", sessionRetention);
    }

    @Test
    void addAndGetRecentShouldPreserveOrderAndLimit() {
        for (int i = 0; i < 5; i++) {
            service.addToMemory("s1", new MemoryEntry("user", "msg-" + i));
        }

        List<MemoryEntry> recent = service.getRecentMemory("s1", 2);
        assertEquals(2, recent.size());
        assertEquals("msg-3", recent.get(0).getContent());
        assertEquals("msg-4", recent.get(1).getContent());
        assertEquals(5, service.getMemorySize("s1"));
    }

    @Test
    void searchMemoryShouldFilterByKeyword() {
        service.addToMemory("s2", new MemoryEntry("user", "disk usage high"));
        service.addToMemory("s2", new MemoryEntry("assistant", "network ok"));

        List<MemoryEntry> hits = service.searchMemory("s2", "disk");
        assertEquals(1, hits.size());
        assertEquals("disk usage high", hits.get(0).getContent());
    }

    @Test
    void clearMemoryShouldRemoveSessionData() {
        service.addToMemory("s3", new MemoryEntry("user", "hello"));
        service.clearMemory("s3");
        assertTrue(service.getRecentMemory("s3", 10).isEmpty());
    }

    @Test
    void getMemorySummaryShouldAggregateAnalysisAndTopic() {
        MemoryEntry analysis = new MemoryEntry("assistant", "one two three four five six seven");
        analysis.setAnalysis(true);
        analysis.setIntent("analyze_logs");
        analysis.setTimestamp(LocalDateTime.now());
        service.addToMemory("s4", new MemoryEntry("user", "check nginx"));
        service.addToMemory("s4", analysis);

        MemorySummary summary = service.getMemorySummary("s4");

        assertEquals(2, summary.getTotalEntries());
        assertEquals(1, summary.getAnalysisCount());
        assertEquals("analyze_logs", summary.getLastIntent());
        assertTrue(summary.getLastTopic().endsWith("..."));
    }
}
