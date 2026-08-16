package com.award.log.controller;

import com.award.log.common.Result;
import com.award.log.model.TaskAlarmConfig;
import com.award.log.security.RequestUserResolver;
import com.award.log.service.AlarmConfigService;
import com.award.log.task.AnalysisTaskManager;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AlarmConfigControllerTest {

    @Test
    void nonAdminCannotSaveDefaultAlarmConfig() {
        AlarmConfigService service = mock(AlarmConfigService.class);
        AnalysisTaskManager taskManager = mock(AnalysisTaskManager.class);
        RequestUserResolver resolver = mock(RequestUserResolver.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(resolver.isAdmin(request)).thenReturn(false);
        AlarmConfigController controller = new AlarmConfigController(service, taskManager, resolver);

        Result<TaskAlarmConfig> result = controller.saveAlarmConfig(request, Map.of("enabled", false));

        assertEquals(403, result.getCode());
    }

    @Test
    void taskAlarmConfigRequiresOwnedTask() {
        AlarmConfigService service = mock(AlarmConfigService.class);
        AnalysisTaskManager taskManager = mock(AnalysisTaskManager.class);
        RequestUserResolver resolver = mock(RequestUserResolver.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(resolver.currentUserId(request)).thenReturn(2);
        when(resolver.isAdmin(request)).thenReturn(false);
        when(taskManager.canAccessTask("t1", 2, false)).thenReturn(false);
        AlarmConfigController controller = new AlarmConfigController(service, taskManager, resolver);

        Result<TaskAlarmConfig> result = controller.getTaskAlarmConfig(request, "t1");

        assertEquals(404, result.getCode());
    }
}
