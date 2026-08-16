package com.award.log.service.impl;

import com.award.log.dto.EnhancedLogParseResultEntity;
import com.award.log.model.LogSeverityLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.test.util.ReflectionTestUtils;

import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiDiagnosisServiceImplTest {

    @Mock
    private ChatModel chatModel;

    private AiDiagnosisServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AiDiagnosisServiceImpl();
        ReflectionTestUtils.setField(service, "chatModel", chatModel);
    }

    @Test
    void generateDiagnosisShouldReturnHealthyMessageForEmptyInput() {
        assertTrue(service.generateDiagnosis(List.of()).contains("未在日志中发现明显异常"));
    }

    @Test
    void generateDiagnosisShouldCallChatModel() {
        EnhancedLogParseResultEntity row = new EnhancedLogParseResultEntity("Connection refused");
        row.setSeverity(LogSeverityLevel.ERROR_LEVEL);
        row.setLogTime("2024-01-01 10:00:00");
        row.getAnomalyReasons().add("network");

        when(chatModel.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(
                new Generation(new AssistantMessage("root cause: port blocked")))));

        String diagnosis = service.generateDiagnosis(List.of(row));
        assertEquals("root cause: port blocked", diagnosis);
    }

    @Test
    void diagnoseSingleLogShouldHandleValidEntry() {
        EnhancedLogParseResultEntity row = new EnhancedLogParseResultEntity("timeout connecting");
        row.setSeverity(LogSeverityLevel.ERROR_LEVEL);
        row.setLogTime("2024-01-01 10:00:00");

        when(chatModel.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(
                new Generation(new AssistantMessage("network issue")))));

        assertEquals("network issue", service.diagnoseSingleLog(row));
    }

    @Test
    void diagnoseSingleLogShouldRejectNullEntry() {
        assertEquals("无效的日志数据。", service.diagnoseSingleLog(null));
    }

    @Test
    void generateDiagnosisShouldFallbackWhenModelReturnsEmptyText() {
        EnhancedLogParseResultEntity row = new EnhancedLogParseResultEntity("Connection refused");
        row.setSeverity(LogSeverityLevel.ERROR_LEVEL);
        row.setLogTime("2024-01-01 10:00:00");
        row.getAnomalyReasons().add("network");

        when(chatModel.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of()));

        String diagnosis = service.generateDiagnosis(List.of(row));
        assertTrue(diagnosis.contains("AI 未返回有效内容"));
    }

    @Test
    void chatStreamShouldRejectBlankUserMessage() {
        String text = service.chatStream("   ").blockFirst();
        assertTrue(text.contains("请输入有效"));
    }

    @Test
    void generateDiagnosisStreamShouldEmitFallbackForEmptyInput() {
        String text = service.generateDiagnosisStream(List.of()).blockFirst();
        assertTrue(text.contains("未在日志中发现明显异常"));
    }
}
