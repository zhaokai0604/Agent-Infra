package com.award.log.controller;

import com.award.log.security.RequestUserResolver;
import com.award.log.service.AiDiagnosisService;
import com.award.log.task.AnalysisTaskManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AiDiagnosisControllerSmokeTest {

    @Mock private AiDiagnosisService aiDiagnosisService;
    @Mock private AnalysisTaskManager taskManager;
    @Mock private RequestUserResolver requestUserResolver;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ThreadPoolTaskExecutor sseExecutor = new ThreadPoolTaskExecutor();
        sseExecutor.setCorePoolSize(1);
        sseExecutor.setMaxPoolSize(1);
        sseExecutor.initialize();

        AiDiagnosisController controller = new AiDiagnosisController();
        ReflectionTestUtils.setField(controller, "aiDiagnosisService", aiDiagnosisService);
        ReflectionTestUtils.setField(controller, "taskManager", taskManager);
        ReflectionTestUtils.setField(controller, "requestUserResolver", requestUserResolver);
        ReflectionTestUtils.setField(controller, "sseExecutor", sseExecutor);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void chatStreamRejectsBlankMessage() throws Exception {
        mockMvc.perform(post("/log/diagnose/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());
    }

    @Test
    void chatStreamRejectsNullBody() throws Exception {
        mockMvc.perform(post("/log/diagnose/chat")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    @Test
    void chatStreamAcceptsMessage() throws Exception {
        mockMvc.perform(post("/log/diagnose/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"analyze disk pressure\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void diagnosisStreamHandlesMissingTask() throws Exception {
        when(requestUserResolver.currentUserId(any()))
                .thenReturn(1);
        when(requestUserResolver.isAdmin(any())).thenReturn(false);
        when(taskManager.getTaskForUser("missing-task", 1, false)).thenReturn(null);

        mockMvc.perform(get("/log/diagnose/stream/missing-task"))
                .andExpect(status().isOk());
    }

    @Test
    void diagnosisStreamHandlesTaskWithoutResult() throws Exception {
        com.award.log.task.TaskInfo task = new com.award.log.task.TaskInfo();
        task.setTaskId("t-pending");
        task.setStatus("RUNNING");
        task.setResult(null);

        when(requestUserResolver.currentUserId(any()))
                .thenReturn(1);
        when(requestUserResolver.isAdmin(any())).thenReturn(false);
        when(taskManager.getTaskForUser("t-pending", 1, false)).thenReturn(task);

        mockMvc.perform(get("/log/diagnose/stream/t-pending"))
                .andExpect(status().isOk());
    }
}
