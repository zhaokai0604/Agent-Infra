package com.award.log.service.impl;

import com.award.log.service.ChatMemoryService;
import com.award.log.service.IntentRecognitionService;
import com.award.log.service.ToolRegistryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChatAnalysisServiceImplTest {

    @Mock
    private IntentRecognitionService intentRecognitionService;
    @Mock
    private ToolRegistryService toolRegistry;
    @Mock
    private ChatMemoryService memoryService;
    @Mock
    private ChatSessionRetention sessionRetention;

    private ChatAnalysisServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ChatAnalysisServiceImpl();
        ReflectionTestUtils.setField(service, "intentRecognitionService", intentRecognitionService);
        ReflectionTestUtils.setField(service, "toolRegistry", toolRegistry);
        ReflectionTestUtils.setField(service, "memoryService", memoryService);
        ReflectionTestUtils.setField(service, "sessionRetention", sessionRetention);
    }

    @Test
    void chatShouldRejectBlankMessage() {
        String reply = service.chat("session-1", "   ").blockFirst();
        assertEquals("请输入有效问题。", reply);
    }

    @Test
    void getConversationHistoryShouldRequireSessionId() {
        assertThrows(IllegalArgumentException.class, () -> service.getConversationHistory(" "));
    }

    @Test
    void clearHistoryShouldRemoveSessionData() {
        service.clearHistory("session-2");
        verify(memoryService).clearMemory("session-2");
        assertTrue(service.getConversationHistory("session-2").isEmpty());
    }

    @Test
    void analyzeShouldReturnDeprecatedNotice() {
        assertTrue(service.analyze("hello").getSummary().contains("废弃"));
    }
}
