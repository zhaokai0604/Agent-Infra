package com.award.log.service.impl;

import com.award.log.mapper.LogAlarmMapper;
import com.award.log.model.LogAlarm;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AlarmLifecycleServiceImplTest {

    @Mock
    private LogAlarmMapper logAlarmMapper;

    private AlarmLifecycleServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AlarmLifecycleServiceImpl(logAlarmMapper, new ObjectMapper());
    }

    @Test
    void acknowledgeShouldUpdateLifecycle() {
        when(logAlarmMapper.updateLifecycle("a1", "ACKNOWLEDGED", "ops")).thenReturn(1);
        assertTrue(service.acknowledge("a1", "ops"));
    }

    @Test
    void handleAndCloseShouldDelegateToMapper() {
        when(logAlarmMapper.updateLifecycle("a2", "HANDLED", "ops")).thenReturn(1);
        when(logAlarmMapper.updateLifecycle("a2", "CLOSED", "ops")).thenReturn(1);
        assertTrue(service.handle("a2", "ops"));
        assertTrue(service.close("a2", "ops"));
    }

    @Test
    void silenceWindowShouldStoreInterval() {
        Map<String, Object> window = service.silenceWindow(
                "2024-01-01T00:00:00", "2024-01-01T06:00:00");
        assertEquals(Boolean.TRUE, window.get("enabled"));
        assertEquals("2024-01-01T00:00:00", window.get("startTime"));
    }

    @Test
    void autoEscalateShouldIncreaseEscalationLevel() {
        LogAlarm alarm = new LogAlarm();
        alarm.setAlarmId("esc-1");
        alarm.setEscalationLevel(0);
        when(logAlarmMapper.selectNeedEscalation(15)).thenReturn(List.of(alarm));

        service.autoEscalate();

        verify(logAlarmMapper).increaseEscalation("esc-1");
    }
}
