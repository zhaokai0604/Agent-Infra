package com.award.log.controller;

import com.award.log.security.RequestUserResolver;
import com.award.log.service.OpsAutoRemediationService;
import com.award.log.service.OpsPatrolService;
import com.award.log.service.PatrolHistoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class OpsPatrolControllerSmokeTest {

    @Mock
    private OpsPatrolService opsPatrolService;
    @Mock
    private OpsAutoRemediationService opsAutoRemediationService;
    @Mock
    private PatrolHistoryService patrolHistoryService;
    @Mock
    private RequestUserResolver requestUserResolver;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        OpsPatrolController controller = new OpsPatrolController();
        ReflectionTestUtils.setField(controller, "opsPatrolService", opsPatrolService);
        ReflectionTestUtils.setField(controller, "opsAutoRemediationService", opsAutoRemediationService);
        ReflectionTestUtils.setField(controller, "patrolHistoryService", patrolHistoryService);
        ReflectionTestUtils.setField(controller, "requestUserResolver", requestUserResolver);

        lenient().when(opsPatrolService.getLastCorrelationSnapshot()).thenReturn(Map.of("diskUsagePct", 50.0));
        lenient().when(opsPatrolService.getLastFindingsSnapshot()).thenReturn(List.of());
        lenient().when(opsPatrolService.getRecentAlerts(anyInt())).thenReturn(List.of());
        lenient().when(opsAutoRemediationService.getLastSummary()).thenReturn(Map.of("traceId", "t1"));
        lenient().when(opsAutoRemediationService.getPendingProposalView(any())).thenReturn(Map.of("hasPending", false));
        lenient().when(patrolHistoryService.listHistory(anyInt(), anyInt())).thenReturn(List.of());

        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void latestCorrelationReturnsSnapshot() throws Exception {
        mockMvc.perform(get("/api/ops/patrol/correlation/latest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.diskUsagePct").value(50.0));
    }

    @Test
    void runPatrolRequiresLoginAndExecutesCycle() throws Exception {
        when(requestUserResolver.currentUserId(any())).thenReturn(1001);

        mockMvc.perform(post("/api/ops/patrol/run"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.correlation").exists());

        verify(opsPatrolService).runPatrolCycle();
    }

    @Test
    void patrolHistoryReturnsRecords() throws Exception {
        mockMvc.perform(get("/api/ops/patrol/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray());
    }
}
