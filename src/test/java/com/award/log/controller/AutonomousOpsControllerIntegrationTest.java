package com.award.log.controller;

import com.award.log.agent.AssistantOrchestrator;
import com.award.log.agent.AutonomousOpsOrchestrator;
import com.award.log.agent.OpsIntentRouter;
import com.award.log.agent.OpsPatrolAutomationService;
import com.award.log.agent.OpsRuntimeService;
import com.award.log.security.RequestUserResolver;
import com.award.log.service.OpsAutoRemediationService;
import com.award.log.service.OpsPatrolService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AutonomousOpsControllerIntegrationTest {

    @Mock
    private AssistantOrchestrator assistantOrchestrator;
    @Mock
    private OpsPatrolService opsPatrolService;
    @Mock
    private OpsAutoRemediationService opsAutoRemediationService;
    @Mock
    private RequestUserResolver requestUserResolver;
    @Mock
    private ObjectProvider<AutonomousOpsOrchestrator> autonomousOpsOrchestrator;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        OpsIntentRouter opsIntentRouter = new OpsIntentRouter();
        OpsPatrolAutomationService patrolAutomationService = new OpsPatrolAutomationService(
                opsPatrolService,
                opsAutoRemediationService);
        OpsRuntimeService opsRuntimeService = new OpsRuntimeService(
                opsIntentRouter,
                assistantOrchestrator,
                patrolAutomationService,
                autonomousOpsOrchestrator);
        AutonomousOpsController controller = new AutonomousOpsController(
                opsRuntimeService,
                opsIntentRouter,
                requestUserResolver);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void runEndpointKeepsPatrolAutomationContractForLegacyAutonomousPath() throws Exception {
        when(requestUserResolver.currentUserId(any())).thenReturn(1001);
        when(opsPatrolService.getLastFindingsSnapshot()).thenReturn(List.of(
                Map.of("type", "DISK_PRESSURE", "severity", "WARN")));
        when(opsPatrolService.getLastCorrelationSnapshot()).thenReturn(Map.of(
                "diskUsagePct", 91.0,
                "alarmTotal24h", 3));
        when(opsAutoRemediationService.getLastSummary()).thenReturn(new LinkedHashMap<>(Map.of(
                "traceId", "trace-ops-1",
                "status", "EXECUTED_WITH_PENDING",
                "successCount", 1,
                "pendingCount", 1,
                "actions", List.of(Map.of("toolName", "CleanTempTool", "success", true)))));
        when(opsAutoRemediationService.getPendingProposalView("system")).thenReturn(new LinkedHashMap<>(Map.of(
                "hasPending", true,
                "steps", List.of(Map.of("kind", "RESTART_SERVICE")),
                "summary", "restart nginx after review")));
        when(opsAutoRemediationService.getRemediationCoverage()).thenReturn(List.of(Map.of(
                "code", "disk",
                "remediation", Map.of("lane", "MIXED"))));

        mockMvc.perform(post("/api/ops/autonomous/run")
                        .param("readOnly", "true")
                        .param("forceRemediate", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.mode").value("PATROL_AUTOMATION"))
                .andExpect(jsonPath("$.data.traceId").value("trace-ops-1"))
                .andExpect(jsonPath("$.data.report.findings[0].type").value("DISK_PRESSURE"))
                .andExpect(jsonPath("$.data.report.pending.hasPending").value(true))
                .andExpect(jsonPath("$.data.report.coverage[0].remediation.lane").value("MIXED"))
                .andExpect(jsonPath("$.data.markdown", containsString("已自动执行，仍有待确认")))
                .andExpect(jsonPath("$.data.markdown", containsString("策略车道")))
                .andExpect(jsonPath("$.data.markdown", containsString("存在待确认的后续处置")))
                .andExpect(jsonPath("$.data.markdown", not(containsString("trace-ops-1"))));

        verify(opsPatrolService).runPatrolCycle();
        verifyNoInteractions(assistantOrchestrator);
    }

    @Test
    void supportsEndpointMapsServiceFailureIntentToPatrolAutomation() throws Exception {
        mockMvc.perform(get("/api/ops/autonomous/supports")
                        .param("message", "服务挂了，帮我自动处理"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.supports").value(true))
                .andExpect(jsonPath("$.data.mode").value("PATROL_AUTOMATION"));

        mockMvc.perform(get("/api/ops/autonomous/supports")
                        .param("message", "随便聊聊今天的工作"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.supports").value(false))
                .andExpect(jsonPath("$.data.mode").value("PATROL_AUTOMATION"));
    }
}
