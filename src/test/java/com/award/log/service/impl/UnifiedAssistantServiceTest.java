package com.award.log.service.impl;

import com.award.log.agent.OpsIntentRouter;
import com.award.log.agent.OpsRunResult;
import com.award.log.agent.OpsRuntimeService;
import com.award.log.security.HighRiskCommandDetector;
import com.award.log.security.IntentRiskFilter;
import com.award.log.security.McpToolSurface;
import com.award.log.security.OpsSecurityContext;
import com.award.log.security.PromptInjectionGuard;
import com.award.log.security.RiskLevel;
import com.award.log.security.ToolSurfaceResolver;
import com.award.log.agent.AssistantReplyPlanner;
import com.award.log.agent.MultiAgentContextBuilder;
import com.award.log.service.AiLogAlarmService;
import com.award.log.service.StatisticsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UnifiedAssistantServiceTest {

    @Mock
    private StatisticsService statisticsService;
    @Mock
    private AiLogAlarmService aiLogAlarmService;
    @Mock
    private MultiAgentContextBuilder multiAgentContextBuilder;
    @Mock
    private PromptInjectionGuard promptInjectionGuard;
    @Mock
    private HighRiskCommandDetector highRiskCommandDetector;
    @Mock
    private IntentRiskFilter intentRiskFilter;
    @Mock
    private ToolSurfaceResolver toolSurfaceResolver;
    @Mock
    private OpsIntentRouter opsIntentRouter;
    @Mock
    private OpsRuntimeService opsRuntimeService;

    private UnifiedAssistantService service;

    @BeforeEach
    void setUp() {
        service = new UnifiedAssistantService();
        ReflectionTestUtils.setField(service, "statisticsService", statisticsService);
        ReflectionTestUtils.setField(service, "aiLogAlarmService", aiLogAlarmService);
        ReflectionTestUtils.setField(service, "multiAgentContextBuilder", multiAgentContextBuilder);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(service, "promptInjectionGuard", promptInjectionGuard);
        ReflectionTestUtils.setField(service, "highRiskCommandDetector", highRiskCommandDetector);
        ReflectionTestUtils.setField(service, "intentRiskFilter", intentRiskFilter);
        ReflectionTestUtils.setField(service, "toolSurfaceResolver", toolSurfaceResolver);
        ReflectionTestUtils.setField(service, "opsIntentRouter", opsIntentRouter);
        ReflectionTestUtils.setField(service, "opsRuntimeService", opsRuntimeService);
        ReflectionTestUtils.setField(service, "assistantReplyPlanner", new AssistantReplyPlanner());
        ReflectionTestUtils.setField(service, "knowledgeSearchTopK", 5);
        ReflectionTestUtils.setField(service, "defaultUseToolAgent", true);
        ReflectionTestUtils.setField(service, "orchestratorEnabled", true);
        ReflectionTestUtils.setField(service, "toolAgentStreamChunkChars", 900);
    }

    @Test
    void getAssistantContextShouldAggregateSections() {
        when(statisticsService.getSystemPerformance(null)).thenReturn(Map.of("cpuUsage", 12.0));
        when(statisticsService.getTaskStatusStatistics()).thenReturn(Map.of("statusCount", Map.of("DONE", 1)));
        when(aiLogAlarmService.getAlarmStatistics(1, null, null)).thenReturn(Map.of("totalAlarms", 0L));
        when(aiLogAlarmService.getAlarmHistory(1, 5, null, null)).thenReturn(Map.of("list", List.of()));
        when(multiAgentContextBuilder.buildForUser(null)).thenReturn(Map.of("diagnosisAgent", Map.of()));

        Map<String, Object> context = service.getAssistantContext();
        assertTrue(context.containsKey("agentHostOs"));
        assertTrue(context.containsKey("performance"));
        assertTrue(context.containsKey("taskStats"));
        assertTrue(context.containsKey("alarmStats"));
    }

    @Test
    void isChitchatMessageShouldRecognizeGreetingsAndPunctuation() {
        assertTrue(service.isChitchatMessage("你好呀"));
        assertTrue(service.isChitchatMessage("您好！"));
        assertTrue(service.isChitchatMessage("?"));
        assertTrue(service.isChitchatMessage("？"));
        assertTrue(service.isChitchatMessage("嗨"));
        assertFalse(service.isChitchatMessage("磁盘满了"));
        assertFalse(service.isChitchatMessage("帮我检查一下系统"));
    }

    @Test
    void shouldRouteToToolAgentShouldSkipChitchat() {
        assertFalse(service.shouldRouteToToolAgent("你好呀", true));
        assertFalse(service.shouldRouteToToolAgent("?", true));
        assertTrue(service.shouldRouteToToolAgent("磁盘满了", true));
    }

    @Test
    void getAssistantContextShouldTolerateDependencyFailures() {
        when(statisticsService.getSystemPerformance(null)).thenThrow(new RuntimeException("down"));
        when(statisticsService.getTaskStatusStatistics()).thenThrow(new RuntimeException("down"));
        when(aiLogAlarmService.getAlarmStatistics(1, null, null)).thenThrow(new RuntimeException("down"));
        when(aiLogAlarmService.getAlarmHistory(1, 5, null, null)).thenThrow(new RuntimeException("down"));
        when(multiAgentContextBuilder.buildForUser("check disk")).thenThrow(new RuntimeException("down"));

        Map<String, Object> context = service.getAssistantContext("check disk");
        assertEquals(Boolean.TRUE, ((Map<?, ?>) context.get("performance")).get("unavailable"));
        assertEquals(List.of(), context.get("recentAlarms"));
    }

    @Test
    void previewAgentStateShouldExposeExecutionContract() {
        when(opsIntentRouter.forceRemediate(anyString())).thenReturn(false);

        Map<String, Object> preview = service.previewAgentState("check disk", List.of(), true, false);

        assertTrue(preview.containsKey("executionState"));
        assertTrue(preview.containsKey("plannedTools"));
        assertTrue(preview.containsKey("observeTools"));
        assertTrue(preview.containsKey("pendingWriteTools"));
        assertEquals(Boolean.FALSE, preview.get("writeConfirmed"));
    }

    @Test
    void orchestratedStreamKeepsSecurityContextInExecutionThread() {
        when(promptInjectionGuard.isInjection(anyString())).thenReturn(false);
        when(highRiskCommandDetector.isHighRiskCommand(anyString())).thenReturn(false);
        when(intentRiskFilter.evaluate(anyString())).thenReturn(RiskLevel.LOW);
        when(opsIntentRouter.forceRemediate(anyString())).thenReturn(false);
        when(opsRuntimeService.shouldOrchestrate(anyString())).thenReturn(true);
        when(opsIntentRouter.resolveFromContext(anyString(), anyList()))
                .thenReturn(OpsIntentRouter.Playbook.PATROL_AUTOMATION);
        when(toolSurfaceResolver.resolve(anyString(), eq(RiskLevel.LOW))).thenReturn(McpToolSurface.FULL);
        when(opsRuntimeService.runPlaybook(
                eq(OpsIntentRouter.Playbook.PATROL_AUTOMATION),
                anyString(),
                eq(McpToolSurface.FULL),
                eq(RiskLevel.LOW)))
                .thenAnswer(invocation -> {
                    OpsSecurityContext.Ctx ctx = OpsSecurityContext.get();
                    assertNotNull(ctx);
                    assertTrue(ctx.isChatAgentPath());
                    assertEquals(McpToolSurface.FULL, ctx.getToolSurface());
                    assertFalse(ctx.isUserConfirmedWrite());
                    return new OpsRunResult("## 全面巡检\n\n- 发现项：**0**\n", "trace-test",
                            Map.of("type", "orchestrate-result", "traceId", "trace-test"));
                });

        String joined = String.join("", service.chatStream("帮我检查电脑", List.of(), true, false)
                .collectList()
                .block());

        assertTrue(joined.contains("ASSISTANT_META:"));
        assertTrue(joined.contains("## 全面巡检"));
        assertNull(OpsSecurityContext.get());
    }
}
