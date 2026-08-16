package com.award.log.agent;

import com.award.log.service.impl.UnifiedAssistantService;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 统一助手回复规划器（按意图优先级路由回复模式）。
 *
 * <pre>
 * P0  空消息           → CHITCHAT / EMPTY
 * P1  确认写操作       → TOOL_AGENT（上游 confirmRemediation）
 * P2  取消 / 纠错      → CONVERSATION
 * P3  拒绝工具         → CONVERSATION
 * P4  寒暄 / 能力 / 用法 → CHITCHAT 或 CONVERSATION
 * P5  澄清（? + 历史）  → CONVERSATION
 * P6  追问 / 解释 / 总结 → CONVERSATION（优先于运维关键词）
 * P7  仅预览           → OPS_ANALYSIS（不真执行）
 * P8  巡检编排         → ORCHESTRATE
 * P9  只读指标查询     → OPS_ANALYSIS
 * P10 强运维 + 工具开  → TOOL_AGENT
 * P11 弱运维关键词     → OPS_ANALYSIS
 * P12 默认             → CONVERSATION
 * </pre>
 */
@Component
public class AssistantReplyPlanner {

    public record ReplyPlan(
            AssistantReplyMode mode,
            AssistantIntentCategory category,
            boolean useToolAgentPath,
            String statusHintZh
    ) {
        public boolean injectMetrics() {
            return mode == AssistantReplyMode.OPS_ANALYSIS
                    || mode == AssistantReplyMode.TOOL_AGENT
                    || mode == AssistantReplyMode.ORCHESTRATE;
        }

        public boolean injectFullContext() {
            return mode == AssistantReplyMode.TOOL_AGENT || mode == AssistantReplyMode.ORCHESTRATE;
        }
    }

    public ReplyPlan plan(String userMessage,
                          List<UnifiedAssistantService.ChatTurn> history,
                          boolean useToolAgentRequested,
                          boolean confirmRemediation,
                          boolean orchestratorEnabled,
                          OpsRuntimeService opsRuntimeService) {
        String msg = userMessage == null ? "" : userMessage.trim();

        // P0
        if (AssistantIntentSignals.isBlank(msg)) {
            return plan(AssistantReplyMode.CHITCHAT, AssistantIntentCategory.EMPTY, false, "");
        }

        // P1
        if (confirmRemediation || AssistantIntentSignals.CONFIRM_WRITE.matcher(msg).find()) {
            return plan(AssistantReplyMode.TOOL_AGENT, AssistantIntentCategory.CONFIRM_WRITE, true,
                    "正在按您的确认执行处置，请稍候…");
        }

        // P1.5 续办 / 直接扫描：有上下文则立刻调工具，不再纯文字追问
        if (useToolAgentRequested && AssistantIntentSignals.isOpsProceed(msg, history)) {
            if (orchestratorEnabled && opsRuntimeService != null
                    && opsRuntimeService.shouldOrchestrateFromContext(msg, history)) {
                return plan(AssistantReplyMode.ORCHESTRATE, AssistantIntentCategory.OPS_DIAGNOSIS, true,
                        AssistantReplyPrompts.orchestrateScanHint());
            }
            return plan(AssistantReplyMode.TOOL_AGENT, AssistantIntentCategory.OPS_DIAGNOSIS, true,
                    AssistantReplyPrompts.toolAgentAutonomousHint());
        }

        // P2
        if (AssistantIntentSignals.CANCEL.matcher(msg).find()) {
            return plan(AssistantReplyMode.CONVERSATION, AssistantIntentCategory.CANCEL, false, "");
        }
        if (AssistantIntentSignals.CORRECTION.matcher(msg).find()) {
            return plan(AssistantReplyMode.CONVERSATION, AssistantIntentCategory.CORRECTION, false, "");
        }

        // P3
        if (AssistantIntentSignals.DECLINE_TOOLS.matcher(msg).find()) {
            return plan(AssistantReplyMode.CONVERSATION, AssistantIntentCategory.DECLINE_TOOLS, false, "");
        }

        // P4 社交 / 元信息
        AssistantIntentCategory social = AssistantIntentSignals.classifySocial(msg);
        if (social != null) {
            return plan(chitchatMode(social), social, false, "");
        }
        if (AssistantIntentSignals.USAGE_HELP.matcher(msg).find()) {
            return plan(AssistantReplyMode.CONVERSATION, AssistantIntentCategory.USAGE_HELP, false, "");
        }

        // P5 澄清
        if (AssistantIntentSignals.CLARIFICATION.matcher(msg).matches()
                || (AssistantIntentSignals.PUNCT_ONLY.matcher(msg).matches()
                && AssistantIntentSignals.hasHistory(history))) {
            return plan(AssistantReplyMode.CONVERSATION, AssistantIntentCategory.CLARIFICATION, false, "");
        }
        if (AssistantIntentSignals.PUNCT_ONLY.matcher(msg).matches()) {
            return plan(AssistantReplyMode.CHITCHAT, AssistantIntentCategory.CLARIFICATION, false, "");
        }

        // P6 对话类（优先于运维词）
        if (AssistantIntentSignals.isConversationalIntent(msg, history)) {
            AssistantIntentCategory cat = conversationCategory(msg);
            return plan(AssistantReplyMode.CONVERSATION, cat, false, "");
        }

        // P7 仅预览
        if (AssistantIntentSignals.isPreviewOnly(msg)) {
            return plan(AssistantReplyMode.OPS_ANALYSIS, AssistantIntentCategory.PREVIEW_ONLY, false, "");
        }

        // P8 编排
        if (AssistantIntentSignals.PATROL_CONTINUATION.matcher(msg).find()) {
            if (orchestratorEnabled && opsRuntimeService != null) {
                return plan(AssistantReplyMode.ORCHESTRATE, AssistantIntentCategory.PATROL_CONTINUATION, true,
                        AssistantReplyPrompts.patrolContinuationHint());
            }
        }
        if (AssistantIntentSignals.PATROL_ORCHESTRATE.matcher(msg).find()
                && !AssistantIntentSignals.isBroadMetricsQuery(msg)) {
            if (orchestratorEnabled && opsRuntimeService != null
                    && opsRuntimeService.shouldOrchestrate(msg)) {
                return plan(AssistantReplyMode.ORCHESTRATE, AssistantIntentCategory.PATROL_ORCHESTRATE, true,
                        AssistantReplyPrompts.orchestrateStatusHint());
            }
        }

        // P9 多指标事实查询交给真正的工具代理，避免固定巡检剧本替用户预设处置方案
        if (useToolAgentRequested && AssistantIntentSignals.isBroadMetricsQuery(msg)) {
            return plan(AssistantReplyMode.TOOL_AGENT, AssistantIntentCategory.METRICS_QUERY, true,
                    AssistantReplyPrompts.toolAgentStatusHint());
        }

        // P9 只读指标
        if (AssistantIntentSignals.isMetricsQuery(msg)) {
            return plan(AssistantReplyMode.OPS_ANALYSIS, AssistantIntentCategory.METRICS_QUERY, false, "");
        }

        // P10 强运维 + 工具
        if (useToolAgentRequested
                && AssistantIntentSignals.STRONG_OPS_ACTION.matcher(msg).find()) {
            return plan(AssistantReplyMode.TOOL_AGENT, AssistantIntentCategory.OPS_DIAGNOSIS, true,
                    AssistantReplyPrompts.toolAgentStatusHint());
        }
        if (useToolAgentRequested && AssistantIntentSignals.OPS_KEYWORDS.matcher(msg).find()) {
            return plan(AssistantReplyMode.TOOL_AGENT, AssistantIntentCategory.OPS_DIAGNOSIS, true,
                    AssistantReplyPrompts.toolAgentStatusHint());
        }

        // P11 弱运维
        if (AssistantIntentSignals.OPS_KEYWORDS.matcher(msg).find()) {
            return plan(AssistantReplyMode.OPS_ANALYSIS, AssistantIntentCategory.OPS_DIAGNOSIS, false, "");
        }

        // P12 运维管家默认：未命中上文规则但仍是本机管理诉求 → 调工具而非纯聊天
        if (useToolAgentRequested && AssistantIntentSignals.isComputerManagementIntent(msg, history)) {
            if (orchestratorEnabled && opsRuntimeService != null
                    && opsRuntimeService.shouldOrchestrateFromContext(msg, history)) {
                return plan(AssistantReplyMode.ORCHESTRATE, AssistantIntentCategory.OPS_DIAGNOSIS, true,
                        AssistantReplyPrompts.opsManagerOrchestrateHint());
            }
            return plan(AssistantReplyMode.TOOL_AGENT, AssistantIntentCategory.OPS_DIAGNOSIS, true,
                    AssistantReplyPrompts.opsManagerToolHint());
        }

        return plan(AssistantReplyMode.CONVERSATION, AssistantIntentCategory.GENERAL, false, "");
    }

    public boolean isChitchat(String userMessage) {
        if (AssistantIntentSignals.isBlank(userMessage)) {
            return false;
        }
        String msg = userMessage.trim();
        if (AssistantIntentSignals.classifySocial(msg) != null) {
            return true;
        }
        return AssistantIntentSignals.PUNCT_ONLY.matcher(msg).matches();
    }

    private static AssistantReplyMode chitchatMode(AssistantIntentCategory social) {
        return switch (social) {
            case GREETING, FAREWELL, GRATITUDE, ACKNOWLEDGMENT, CAPABILITY_INQUIRY -> AssistantReplyMode.CHITCHAT;
            default -> AssistantReplyMode.CONVERSATION;
        };
    }

    private static AssistantIntentCategory conversationCategory(String msg) {
        if (AssistantIntentSignals.FOLLOW_UP.matcher(msg).find()) {
            return AssistantIntentCategory.FOLLOW_UP;
        }
        if (AssistantIntentSignals.SUMMARIZATION.matcher(msg).find()) {
            return AssistantIntentCategory.SUMMARIZATION;
        }
        if (AssistantIntentSignals.COMPARISON.matcher(msg).find()) {
            return AssistantIntentCategory.COMPARISON;
        }
        if (AssistantIntentSignals.EXPLANATION.matcher(msg).find()) {
            return AssistantIntentCategory.EXPLANATION;
        }
        if (AssistantIntentSignals.DECLINE_TOOLS.matcher(msg).find()) {
            return AssistantIntentCategory.DECLINE_TOOLS;
        }
        return AssistantIntentCategory.GENERAL;
    }

    private static ReplyPlan plan(AssistantReplyMode mode,
                                  AssistantIntentCategory category,
                                  boolean toolPath,
                                  String hint) {
        return new ReplyPlan(mode, category, toolPath, hint == null ? "" : hint);
    }
}
