package com.award.log.controller;

import com.award.log.common.PageResult;
import com.award.log.dto.EnhancedLogParseResultEntity;
import com.award.log.security.RequestUserResolver;
import com.award.log.service.AiDiagnosisService;
import com.award.log.service.LogAnalysisService;
import com.award.log.task.AnalysisTaskManager;
import com.award.log.task.TaskInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class LogAnalysisControllerSmokeTest {

    @Mock
    private LogAnalysisService logAnalysisService;
    @Mock
    private AiDiagnosisService aiDiagnosisService;
    @Mock
    private AnalysisTaskManager taskManager;
    @Mock
    private RequestUserResolver requestUserResolver;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        LogAnalysisController controller = new LogAnalysisController();
        ReflectionTestUtils.setField(controller, "logAnalysisService", logAnalysisService);
        ReflectionTestUtils.setField(controller, "aiDiagnosisService", aiDiagnosisService);
        ReflectionTestUtils.setField(controller, "taskManager", taskManager);
        ReflectionTestUtils.setField(controller, "requestUserResolver", requestUserResolver);

        lenient().when(requestUserResolver.currentUserId(any())).thenReturn(1);
        lenient().when(requestUserResolver.isAdmin(any())).thenReturn(false);

        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void historyReturnsPagedTasks() throws Exception {
        PageResult<TaskInfo> page = new PageResult<>();
        page.setList(List.of());
        page.setTotal(0);
        when(taskManager.getTasksPageForUser(eq(1), eq(false), eq(1), eq(10), any(), any(), any(), any()))
                .thenReturn(page);

        mockMvc.perform(get("/log/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.total").value(0));
    }

    @Test
    void quickDiagnoseReturnsDiagnosis() throws Exception {
        when(aiDiagnosisService.diagnoseSingleLog(any(EnhancedLogParseResultEntity.class)))
                .thenReturn("ok");

        mockMvc.perform(post("/log/quick-diagnose")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"error line\",\"level\":\"ERROR\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("ok"));
    }

    @Test
    void getTaskStatusReturnsTask() throws Exception {
        TaskInfo task = new TaskInfo();
        task.setTaskId("t1");
        task.setStatus("RUNNING");
        when(taskManager.getTaskForUser(eq("t1"), eq(1), eq(false))).thenReturn(task);

        mockMvc.perform(get("/log/task/t1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.taskId").value("t1"));
    }

    @Test
    void getTaskReportReturnsCompletedTask() throws Exception {
        TaskInfo task = new TaskInfo();
        task.setTaskId("t2");
        task.setStatus("DONE");
        when(taskManager.getTaskForUser(eq("t2"), eq(1), eq(false))).thenReturn(task);

        mockMvc.perform(get("/log/report/t2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value("DONE"));
    }

    @Test
    void pauseTaskShouldDelegateToService() throws Exception {
        when(taskManager.canAccessTask(eq("t3"), eq(1), eq(false))).thenReturn(true);

        mockMvc.perform(post("/log/pause/t3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        verify(logAnalysisService).pauseAnalysis("t3");
    }

    @Test
    void cancelTaskShouldDelegateToService() throws Exception {
        when(taskManager.canAccessTask(eq("t4"), eq(1), eq(false))).thenReturn(true);

        mockMvc.perform(post("/log/cancel/t4"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        verify(logAnalysisService).cancelAnalysis("t4");
    }

    @Test
    void deleteTaskShouldReturnSuccessWhenAllowed() throws Exception {
        when(taskManager.deleteTaskForUser(eq("t5"), eq(1), eq(false))).thenReturn(true);

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/log/delete/t5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(true));
    }
}
