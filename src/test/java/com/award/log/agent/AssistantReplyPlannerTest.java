package com.award.log.agent;

import com.award.log.service.impl.UnifiedAssistantService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.*;

class AssistantReplyPlannerTest {

    private AssistantReplyPlanner planner;

    @BeforeEach
    void setUp() {
        planner = new AssistantReplyPlanner();
    }

    @ParameterizedTest
    @CsvSource({
            "你好呀, CHITCHAT, GREETING, false",
            "谢谢, CHITCHAT, GRATITUDE, false",
            "再见, CHITCHAT, FAREWELL, false",
            "你能做什么, CHITCHAT, CAPABILITY_INQUIRY, false",
            "取消, CONVERSATION, CANCEL, false",
            "不对你理解错了, CONVERSATION, CORRECTION, false",
            "为什么 CPU 这么高, TOOL_AGENT, OPS_DIAGNOSIS, true",
            "总结一下, CONVERSATION, SUMMARIZATION, false",
            "查一下 CPU 使用率, OPS_ANALYSIS, METRICS_QUERY, false",
            "仅预览清理临时文件, OPS_ANALYSIS, PREVIEW_ONLY, false",
            "磁盘满了, TOOL_AGENT, OPS_DIAGNOSIS, true",
            "确认执行, TOOL_AGENT, CONFIRM_WRITE, true"
    })
    void planShouldMatchExpectedMode(String message, String mode, String category, boolean toolPath) {
        var plan = planner.plan(message, null, true, false, true, null);
        assertEquals(AssistantReplyMode.valueOf(mode), plan.mode(), "message=" + message);
        assertEquals(AssistantIntentCategory.valueOf(category), plan.category());
        assertEquals(toolPath, plan.useToolAgentPath());
    }

    @Test
    void questionMarkAfterHistoryShouldBeConversation() {
        var plan = planner.plan("?", List.of(
                new UnifiedAssistantService.ChatTurn("user", "你好"),
                new UnifiedAssistantService.ChatTurn("assistant", "你好！")
        ), true, false, true, null);
        assertEquals(AssistantReplyMode.CONVERSATION, plan.mode());
        assertEquals(AssistantIntentCategory.CLARIFICATION, plan.category());
        assertFalse(plan.useToolAgentPath());
    }

    @Test
    void declineToolsShouldForceConversation() {
        var plan = planner.plan("帮我看看磁盘，不要调用工具", null, true, false, true, null);
        assertEquals(AssistantReplyMode.CONVERSATION, plan.mode());
        assertEquals(AssistantIntentCategory.DECLINE_TOOLS, plan.category());
    }

    @Test
    void confirmRemediationFlagShouldRouteToToolAgent() {
        var plan = planner.plan("好的", null, true, true, true, null);
        assertEquals(AssistantIntentCategory.CONFIRM_WRITE, plan.category());
        assertTrue(plan.useToolAgentPath());
    }

    @Test
    void chitchatShouldNotInjectMetrics() {
        var plan = planner.plan("你好呀", null, true, false, true, null);
        assertFalse(plan.injectMetrics());
        assertFalse(plan.injectFullContext());
    }

    @Test
    void opsAnalysisShouldInjectMetricsSummaryOnly() {
        var plan = planner.plan("查一下内存占用", null, false, false, true, null);
        assertTrue(plan.injectMetrics());
        assertFalse(plan.injectFullContext());
    }

    @Test
    void directScanShouldRouteToToolAgent() {
        var plan = planner.plan("直接扫描", null, true, false, true, null);
        assertEquals(AssistantReplyMode.TOOL_AGENT, plan.mode());
        assertTrue(plan.useToolAgentPath());
    }

    @Test
    void ackWithDiskContextShouldRouteToToolAgent() {
        var history = List.of(
                new UnifiedAssistantService.ChatTurn("user",
                        "C盘空间不够，合照要保留，帮我扫描分析一下"),
                new UnifiedAssistantService.ChatTurn("assistant", "好的，我可以先扫描…")
        );
        var plan = planner.plan("可以的", history, true, false, true, null);
        assertEquals(AssistantReplyMode.TOOL_AGENT, plan.mode());
        assertTrue(plan.useToolAgentPath());
    }

    @Test
    void ackWithPatrolContextShouldRouteToOrchestrateWhenEnabled() {
        var history = List.of(
                new UnifiedAssistantService.ChatTurn("user", "帮我检查电脑"),
                new UnifiedAssistantService.ChatTurn("assistant", "可以，我会先做全面巡检。")
        );
        OpsRuntimeService runtime = mock(OpsRuntimeService.class);
        when(runtime.shouldOrchestrateFromContext(anyString(), any())).thenReturn(true);

        var plan = planner.plan("可以的", history, true, false, true, runtime);

        assertEquals(AssistantReplyMode.ORCHESTRATE, plan.mode());
        assertTrue(plan.useToolAgentPath());
    }

    @Test
    void bareAckWithoutOpsContextStaysChitchatOrConversation() {
        var history = List.of(
                new UnifiedAssistantService.ChatTurn("user", "你好"),
                new UnifiedAssistantService.ChatTurn("assistant", "你好！")
        );
        var plan = planner.plan("可以的", history, true, false, true, null);
        assertNotEquals(AssistantReplyMode.TOOL_AGENT, plan.mode());
    }

    @Test
    void computerManageIntentShouldRouteToToolAgent() {
        var plan = planner.plan("帮我运维管家，扫描磁盘和热点", null, true, false, true, null);
        assertEquals(AssistantReplyMode.TOOL_AGENT, plan.mode());
        assertTrue(plan.useToolAgentPath());
    }

    @Test
    void longPhotoTaskShouldNotFallToConversation() {
        String msg = "C盘空间不够了，我有很多合照和微信图片要保留，"
                + "能不能帮我扫描一下哪些目录占用最大，并给出整理方案，不要误删重要照片";
        var plan = planner.plan(msg, null, true, false, true, null);
        assertEquals(AssistantReplyMode.TOOL_AGENT, plan.mode());
        assertTrue(plan.useToolAgentPath());
    }

    @Test
    void manageComputerWithPatrolKeywordsRoutesToToolAgent() {
        var plan = planner.plan("帮我运维管家，全面检查本机", null, true, false, true, null);
        assertEquals(AssistantReplyMode.TOOL_AGENT, plan.mode());
        assertTrue(plan.useToolAgentPath());
    }

    @Test
    void directComputerHealthCheckShouldRouteToOrchestrateWhenEnabled() {
        OpsRuntimeService runtime = mock(OpsRuntimeService.class);
        when(runtime.shouldOrchestrate(anyString())).thenReturn(true);

        var plan = planner.plan("帮我检查电脑", null, true, false, true, runtime);

        assertEquals(AssistantReplyMode.ORCHESTRATE, plan.mode());
        assertTrue(plan.useToolAgentPath());
    }

    @Test
    void broadServerMetricsUseToolAgentInsteadOfFixedPatrol() {
        String message = "请检查当前服务器的整体运行状态，包括 CPU、内存、磁盘、进程和网络情况。";

        var plan = planner.plan(message, null, true, false, true, null);

        assertEquals(AssistantReplyMode.TOOL_AGENT, plan.mode());
        assertEquals(AssistantIntentCategory.METRICS_QUERY, plan.category());
        assertTrue(plan.useToolAgentPath());
    }
}
