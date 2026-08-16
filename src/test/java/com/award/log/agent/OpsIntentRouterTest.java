package com.award.log.agent;

import com.award.log.service.impl.UnifiedAssistantService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpsIntentRouterTest {

    private final OpsIntentRouter router = new OpsIntentRouter();

    @Test
    void demoDiskPhrases() {
        assertEquals(OpsIntentRouter.Playbook.DISK_CLEANUP, router.resolve("磁盘满了"));
        assertEquals(OpsIntentRouter.Playbook.DISK_CLEANUP, router.resolve("帮我预览清理临时文件"));
        assertEquals(OpsIntentRouter.Playbook.DISK_CLEANUP, router.resolve("根分区使用率告警"));
    }

    @Test
    void demoCpuPhrases() {
        assertEquals(OpsIntentRouter.Playbook.CPU_PRESSURE, router.resolve("CPU占用过高怎么办"));
        assertEquals(OpsIntentRouter.Playbook.CPU_PRESSURE, router.resolve("系统负载过高"));
    }

    @Test
    void diskTakesPriorityOverAutonomousIntent() {
        assertEquals(OpsIntentRouter.Playbook.DISK_CLEANUP, router.resolve("自动修复磁盘空间"));
        assertEquals(OpsIntentRouter.Playbook.DISK_CLEANUP, router.resolve("一键自动清理临时文件"));
    }

    @Test
    void demoServiceRoutesAutonomous() {
        assertEquals(OpsIntentRouter.Playbook.PATROL_AUTOMATION, router.resolve("有 systemd 服务失败了"));
        assertEquals(OpsIntentRouter.Playbook.PATROL_AUTOMATION, router.resolve("服务挂了"));
    }

    @Test
    void generalHealthCheckRoutesAutonomous() {
        assertEquals(OpsIntentRouter.Playbook.PATROL_AUTOMATION, router.resolve("帮我全面检查系统状态"));
        assertEquals(OpsIntentRouter.Playbook.PATROL_AUTOMATION, router.resolve("检查一下本机环境是否正常"));
        assertEquals(OpsIntentRouter.Playbook.PATROL_AUTOMATION, router.resolve("帮我检查电脑"));
        assertEquals(OpsIntentRouter.Playbook.PATROL_AUTOMATION, router.resolve("电脑体检一下"));
        assertEquals(OpsIntentRouter.Playbook.PATROL_AUTOMATION, router.resolve("看看电脑状态是否正常"));
        assertTrue(router.shouldOrchestrate("健康检查"));
    }

    @Test
    void contextualResolveUsesHistoryOnlyForOpsFollowup() {
        var history = List.of(
                new UnifiedAssistantService.ChatTurn("user", "帮我全面检查系统状态"),
                new UnifiedAssistantService.ChatTurn("assistant", "巡检完成")
        );
        assertEquals(OpsIntentRouter.Playbook.PATROL_AUTOMATION, router.resolveFromContext("可以的", history));
        assertEquals(OpsIntentRouter.Playbook.NONE, router.resolveFromContext("总结一下", history));
        assertFalse(router.shouldOrchestrateFromContext("总结一下", history));
    }

    @Test
    void diskTakesPriorityOverCpuWhenBothMentioned() {
        assertEquals(OpsIntentRouter.Playbook.DISK_CLEANUP, router.resolve("CPU高但主要是磁盘满了"));
    }

    @Test
    void shouldOrchestrateForDemoPaths() {
        assertTrue(router.shouldOrchestrate("磁盘满了"));
        assertTrue(router.shouldOrchestrate("一键巡检"));
    }

    @Test
    void softCleanupPhrasesRouteDiskCleanup() {
        assertEquals(OpsIntentRouter.Playbook.DISK_CLEANUP, router.resolve("帮我清理"));
        assertEquals(OpsIntentRouter.Playbook.DISK_CLEANUP, router.resolve("清理一下"));
        assertFalse(router.forceRemediate("帮我清理"));
    }

    @Test
    void patrolContinuationBeatsBareConfirmExecute() {
        assertEquals(OpsIntentRouter.Playbook.PATROL_CONTINUATION,
                router.resolve("继续处理巡检待办，确认执行"));
        assertTrue(router.forceRemediate("继续处理巡检待办，确认执行"));
    }

    @Test
    void forceRemediateOnDirectCleanupPhrases() {
        assertTrue(router.forceRemediate("确认执行"));
        assertTrue(router.forceRemediate("直接删除"));
        assertTrue(router.forceRemediate("删除 C:\\Users\\Administrator\\AppData\\Local\\Temp\\foo"));
        assertTrue(router.forceRemediate("立即清理"));
        // 「帮我清理」仅表意图，须二次确认后才真写
        assertFalse(router.forceRemediate("清理垃圾"));
        assertFalse(router.forceRemediate("帮我清理磁盘临时文件"));
        assertFalse(router.forceRemediate("磁盘满了清理临时"));
    }

    @Test
    void forceRemediateFalseWhenPreviewOnly() {
        assertFalse(router.forceRemediate("仅预览清理临时文件"));
        assertFalse(router.forceRemediate("帮我看看磁盘怎么样"));
    }

    @Test
    void rulesNoneKeepsPureRulesWhenNoLlm() {
        assertEquals(OpsIntentRouter.Playbook.NONE, router.resolveByRules("今天天气怎么样"));
        assertEquals(OpsIntentRouter.Playbook.NONE, router.resolve("今天天气怎么样"));
    }

    @Test
    void llmFallbackUsedOnlyWhenRulesMiss() {
        OpsIntentRouter wired = new OpsIntentRouter();
        LlmPlaybookClassifier fake = new LlmPlaybookClassifier(new com.fasterxml.jackson.databind.ObjectMapper()) {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public java.util.Optional<OpsIntentRouter.Playbook> classify(String userMessage, String contextHint) {
                return java.util.Optional.of(OpsIntentRouter.Playbook.DISK_CLEANUP);
            }
        };
        wired.setLlmPlaybookClassifier(fake);
        // 规则已命中 → 仍走规则，不因 LLM 改写
        assertEquals(OpsIntentRouter.Playbook.CPU_PRESSURE, wired.resolve("CPU占用过高怎么办"));
        // 规则未命中 → 语义兜底
        assertEquals(OpsIntentRouter.Playbook.DISK_CLEANUP, wired.resolve("盘子装不下东西了赶紧腾空"));
    }
}
