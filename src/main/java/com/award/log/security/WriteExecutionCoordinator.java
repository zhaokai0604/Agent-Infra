package com.award.log.security;

import com.award.log.mcp.WriteToolResultSupport;
import com.award.log.mcp.dispatch.McpToolParamReader;
import com.award.log.security.effect.EvidenceContractValidator;
import com.award.log.security.effect.ToolEffect;
import com.award.log.security.effect.ToolEffectResolver;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * 写操作全链路统一入口（对话 / HTTP MCP / Playbook 共用口径）。
 * <p>
 * 链路：用户话术 → {@link OpsIntentRouter#forceRemediate} / 前端 confirmRemediation
 * → {@link OpsSecurityContext#isUserConfirmedWrite()} → {@link ChatWriteExecutionPolicy}
 * → 工具 Bean → {@link com.award.log.mcp.MinPrivilegeExecutor} → {@link WriteToolResultSupport}
 * → {@link ChatToolExecutionTracker} 防幻觉。
 */
public final class WriteExecutionCoordinator {

    private static final Pattern DELETE_UTTERANCE = Pattern.compile(
            "删除|删掉|移除|清除|清理|释放|删了",
            Pattern.CASE_INSENSITIVE);

    private static final ToolEffectResolver EFFECT_RESOLVER = new ToolEffectResolver();

    private WriteExecutionCoordinator() {
    }

    /** HTTP「确认执行」或对话 confirmRemediation 后，强制写类工具参数。 */
    public static void applyForcedWriteParams(String toolName, Map<String, Object> parameters) {
        McpToolParamReader.forceConfirmedWriteToolParams(toolName, parameters);
    }

    /** 从用户自然语言推断是否应走真实写（Playbook / 对话元数据）。 */
    public static boolean userUtteranceRequestsWrite(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return false;
        }
        return McpToolParamReader.shouldEscalateToRealWrite(Map.of(), userMessage);
    }

    public static boolean utteranceLooksLikeDelete(String userMessage) {
        return userMessage != null && DELETE_UTTERANCE.matcher(userMessage).find();
    }

    /**
     * 执行后校验：请求了真实写但工具仍返回 DRY-RUN 时，在 HTTP 响应中附加警告字段；
     * 并通过证据契约检查可机读效果字段是否齐全。
     */
    public static void attachWriteMismatchIfNeeded(
            String toolName,
            Map<String, Object> parameters,
            String toolResultJson,
            Map<String, Object> httpResponse) {
        if (!WriteToolResultSupport.requestedRealWrite(parameters)) {
            return;
        }
        ToolEffect effect = EFFECT_RESOLVER.resolve(toolName, parameters);
        if (httpResponse != null) {
            httpResponse.put("toolEffect", effect.toMap());
        }
        if (WriteToolResultSupport.isConfirmedRealWrite(toolResultJson)) {
            EvidenceContractValidator.ValidationResult evidence =
                    EvidenceContractValidator.validateRequestedWrite(effect, true, toolResultJson);
            if (!evidence.complete() && httpResponse != null) {
                httpResponse.put("evidenceIncomplete", true);
                httpResponse.put("evidenceContractId", evidence.contractId());
                httpResponse.put("evidenceMissingFields", evidence.missingFields());
                httpResponse.put("evidenceMessage", evidence.message());
            }
            ChatToolExecutionTracker.record(toolName, toolResultJson);
            return;
        }
        String mode = WriteToolResultSupport.extractMode(toolResultJson);
        String warn = WriteToolResultSupport.mismatchWarning(toolName, mode.isBlank() ? "UNKNOWN" : mode);
        httpResponse.put("writeMismatch", true);
        httpResponse.put("writeMismatchMessage", warn);
        ChatToolExecutionTracker.record(toolName, toolResultJson);
    }

    public static String sanitizeAssistantText(String content, boolean allowWrite) {
        return ChatToolExecutionTracker.sanitizeUnverifiedExecutionClaims(content, allowWrite);
    }
}
