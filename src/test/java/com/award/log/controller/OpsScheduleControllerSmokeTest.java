package com.award.log.controller;

import com.award.log.security.RequestUserResolver;
import com.award.log.service.OpsDeferredTaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class OpsScheduleControllerSmokeTest {

    @Mock private OpsDeferredTaskService opsDeferredTaskService;
    @Mock private RequestUserResolver requestUserResolver;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        OpsScheduleController controller = new OpsScheduleController(opsDeferredTaskService, requestUserResolver);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void listTasksReturnsPendingTasks() throws Exception {
        when(requestUserResolver.currentUserId(any())).thenReturn(1);
        when(requestUserResolver.isAdmin(any())).thenReturn(false);
        when(opsDeferredTaskService.listPending(anyString(), anyBoolean()))
                .thenReturn(List.of(Map.of("taskId", "d1")));

        mockMvc.perform(get("/api/ops-schedule/tasks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0].taskId").value("d1"));
    }
}
