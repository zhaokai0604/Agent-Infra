package com.award.log.service.impl;

import com.award.log.mapper.LogAlarmMapper;
import com.award.log.model.LogAlarm;
import com.award.log.model.TaskAlarmConfig;
import com.award.log.service.AlarmConfigService;
import com.award.log.task.AnalysisTaskManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AiLogAlarmServiceImplTest {

    @Mock
    private LogAlarmMapper logAlarmMapper;
    @Mock
    private AlarmConfigService alarmConfigService;
    @Mock
    private AnalysisTaskManager analysisTaskManager;

    private AiLogAlarmServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AiLogAlarmServiceImpl();
        ReflectionTestUtils.setField(service, "logAlarmMapper", logAlarmMapper);
        ReflectionTestUtils.setField(service, "alarmConfigService", alarmConfigService);
        ReflectionTestUtils.setField(service, "analysisTaskManager", analysisTaskManager);
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.initialize();
        ReflectionTestUtils.setField(service, "logAnalysisExecutor", executor);
        ReflectionTestUtils.setField(service, "aggregationEnabled", false);
    }

    @Test
    void getAlarmHistoryShouldPageResults() {
        when(logAlarmMapper.selectPage(anyInt(), anyInt(), anyInt(), isNull(), isNull()))
                .thenReturn(List.of(new LogAlarm()));
        when(logAlarmMapper.count(isNull(), isNull())).thenReturn(1L);

        Map<String, Object> page = service.getAlarmHistory(1, 10, null, null);
        assertEquals(1L, page.get("total"));
        assertEquals(1, ((List<?>) page.get("list")).size());
    }

    @Test
    void getAlarmStatisticsShouldComputeSuccessRate() {
        when(logAlarmMapper.selectAlarmStatistics(anyInt(), isNull(), isNull()))
                .thenReturn(Map.of("total_alarms", 4, "success_alarms", 3, "failed_alarms", 1));
        when(logAlarmMapper.selectAlarmTrend(anyInt(), isNull(), isNull())).thenReturn(List.of());
        when(logAlarmMapper.selectAlarmLevelDistribution(anyInt(), isNull(), isNull())).thenReturn(List.of());
        when(logAlarmMapper.selectAlarmRootCauseStatistics(anyInt(), isNull(), isNull())).thenReturn(List.of());

        Map<String, Object> stats = service.getAlarmStatistics(7, null, null);
        assertEquals(4L, stats.get("totalAlarms"));
        assertEquals(75.0, stats.get("successRate"));
    }

    @Test
    void pushAlarmShouldRecordWhenEnabled() {
        LogAlarm alarm = new LogAlarm();
        alarm.setAlarmId("a-1");
        alarm.setTaskId("task-1");
        alarm.setLevel("ERROR");
        TaskAlarmConfig config = new TaskAlarmConfig();
        config.setEnabled(true);
        config.setAlarmLevel("ERROR");
        when(alarmConfigService.getEffectiveConfig("task-1")).thenReturn(config);

        assertTrue(service.pushAlarm(alarm));
        assertEquals("RECORDED", alarm.getPushStatus());
    }

    @Test
    void processAlarmsByTaskIdShouldRejectUnauthorizedAccess() {
        when(analysisTaskManager.canAccessTask("task-x", 9, false)).thenReturn(false);
        assertThrows(IllegalArgumentException.class,
                () -> service.processAlarmsByTaskIdForUser("task-x", 9, false));
    }
}
