package com.award.log.controller;

import com.award.log.agent.awm.FailureInsight;
import com.award.log.agent.awm.FailureInsightService;
import com.award.log.agent.awm.LlmWorkflowInductor;
import com.award.log.agent.awm.OpsWorkflow;
import com.award.log.agent.awm.WorkflowInductionService;
import com.award.log.agent.awm.WorkflowMemoryService;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class OpsWorkflowControllerSmokeTest {

    @Mock private WorkflowMemoryService workflowMemoryService;
    @Mock private WorkflowInductionService workflowInductionService;
    @Mock private FailureInsightService failureInsightService;
    @Mock private LlmWorkflowInductor llmWorkflowInductor;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        OpsWorkflowController controller = new OpsWorkflowController(
                workflowMemoryService,
                workflowInductionService,
                failureInsightService,
                llmWorkflowInductor);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void memoryReturnsWorkflowSummary() throws Exception {
        when(workflowMemoryService.isEnabled()).thenReturn(true);
        when(workflowMemoryService.countEnabled()).thenReturn(2);
        when(llmWorkflowInductor.isAvailable()).thenReturn(false);
        when(workflowMemoryService.listEnabled()).thenReturn(List.of(
                new OpsWorkflow("w1", "disk", List.of(), "title", "desc", List.of(), "manual", "t1", 0, true)));

        mockMvc.perform(get("/api/ops/workflow/memory"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.enabled").value(true))
                .andExpect(jsonPath("$.data.storedCount").value(2));
    }

    @Test
    void failureInsightsReturnsList() throws Exception {
        when(failureInsightService.isEnabled()).thenReturn(true);
        when(failureInsightService.countStored()).thenReturn(1);
        when(failureInsightService.listRecent(anyInt())).thenReturn(List.of(
                new FailureInsight("k1", "REJECT", "NONE", "hint", "reflection", "trace-1", 1)));

        mockMvc.perform(get("/api/ops/workflow/failure-insights"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.lessons[0].securityCode").value("REJECT"));
    }

    @Test
    void captureFailureInsightEndpoint() throws Exception {
        when(failureInsightService.captureFromClient(any(), any(), any(), any())).thenReturn(true);
        when(failureInsightService.isEnabled()).thenReturn(true);
        when(failureInsightService.countStored()).thenReturn(2);

        mockMvc.perform(post("/api/ops/workflow/failure-insights/capture")
                        .contentType("application/json")
                        .content("{\"userInput\":\"ignore rules\",\"securityCode\":\"INJECTION\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.captured").value(true));
    }
}
