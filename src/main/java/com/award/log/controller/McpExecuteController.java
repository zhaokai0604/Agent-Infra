package com.award.log.controller;

import com.award.log.agent.awm.FailureInsightService;
import com.award.log.mcp.McpToolCatalog;
import com.award.log.mcp.WriteToolResultSupport;
import com.award.log.mcp.dispatch.McpToolParamReader;
import com.award.log.security.HttpAuditSubject;
import com.award.log.security.McpInvocationSecurityGate.GateDecision;
import com.award.log.security.effect.EffectFingerprint;
import com.award.log.security.effect.SessionRiskBudgetService;
import com.award.log.security.effect.ToolEffect;
import com.award.log.security.effect.ToolEffectResolver;
import com.award.log.security.effect.WriteCapabilityToken;
import com.award.log.service.mcp.McpAuditService;
import com.award.log.service.mcp.McpExecutionService;
import com.award.log.service.mcp.McpPendingConfirmationService;
import com.award.log.service.mcp.McpSecurityService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/mcp")
@RequiredArgsConstructor
public class McpExecuteController {

    private final McpToolCatalog mcpToolCatalog;
    private final McpSecurityService mcpSecurityService;
    private final McpExecutionService mcpExecutionService;
    private final McpAuditService mcpAuditService;
    private final FailureInsightService failureInsightService;
    private final McpPendingConfirmationService mcpPendingConfirmationService;
    private final HttpAuditSubject httpAuditSubject;
    private final ToolEffectResolver toolEffectResolver;
    private final SessionRiskBudgetService sessionRiskBudgetService;

    @GetMapping("/tools")
    public Map<String, Object> getMcpTools() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("data", mcpToolCatalog.listToolsForApi());
        response.put("contractVersion", 2);
        return response;
    }

    @PostMapping("/execute")
    public Map<String, Object> executeTool(@RequestBody Map<String, Object> request) {
        if (request == null) {
            return errorResponse("请求体不能为空");
        }

        String toolName = stringField(request, "toolName");
        Map<String, Object> parameters = extractParameters(request);
        String userMessage = stringField(request, "userMessage");

        long startTime = System.currentTimeMillis();
        String traceId = UUID.randomUUID().toString();
        log.info("收到 MCP 工具执行请求: toolName={}", toolName);

        if (toolName.isBlank() || !mcpToolCatalog.isRegistered(toolName)) {
            return errorResponse("未找到可执行工具: " + toolName, traceId, startTime, toolName, 404);
        }
        Map<String, Object> platformSupport = mcpToolCatalog.describePlatformSupport(toolName);
        if (!Boolean.TRUE.equals(platformSupport.get("available"))) {
            return unsupportedResponse(toolName, traceId, startTime, platformSupport);
        }

        GateDecision decision = mcpSecurityService.evaluateInitial(toolName, parameters, userMessage);
        String userInstruction = mcpSecurityService.buildAuditInstruction(userMessage, toolName, parameters);
        ToolEffect effect = toolEffectResolver.resolve(toolName, parameters);
        String effectFingerprint = EffectFingerprint.of(toolName, parameters, effect);
        String requester = currentRequester();

        if (decision.getType() == GateDecision.Type.BLOCK) {
            log.warn("MCP 安全门拦截: tool={} code={}", toolName, decision.getCode());
            mcpAuditService.persistGateReject(traceId, userInstruction, toolName, decision, startTime, parameters);
            failureInsightService.captureReject(userMessage, decision.getCode(), toolName, decision.getMessage(), traceId);
            Map<String, Object> blocked = mcpSecurityService.buildBlockResponse(traceId, startTime, decision);
            attachEffectMeta(blocked, effect, effectFingerprint, null);
            return decorateEnvelope(blocked, toolName, traceId, 403);
        }

        if (decision.getType() == GateDecision.Type.NEED_CONFIRM) {
            log.info("MCP 中风险待确认: tool={} score={} effect={}",
                    toolName, decision.getAgenticRiskScore(), effect.action());
            failureInsightService.captureReject(
                    userMessage, decision.getCode(), toolName, decision.getMessage(), traceId);
            WriteCapabilityToken token = WriteCapabilityToken.issue(
                    effectFingerprint, toolName, requester, pendingTtlHint());
            McpPendingConfirmationService.PendingConfirmation pending = mcpPendingConfirmationService.register(
                    traceId,
                    toolName,
                    parameters,
                    userMessage,
                    userInstruction,
                    requester,
                    effect,
                    effectFingerprint,
                    token);
            mcpAuditService.persistNeedConfirm(
                    traceId,
                    userInstruction,
                    toolName,
                    decision,
                    startTime,
                    parameters,
                    pending.confirmationId());
            Map<String, Object> response =
                    mcpSecurityService.buildNeedConfirmResponse(traceId, startTime, decision, toolName, parameters);
            response.put("confirmationId", pending.confirmationId());
            response.put("expiresAtMs", pending.expiresAtMs());
            response.put("capabilityToken", token.tokenId());
            attachEffectMeta(response, effect, effectFingerprint, sessionRiskBudgetService.summary(requester));
            return decorateEnvelope(response, toolName, traceId, 202);
        }

        SessionRiskBudgetService.BudgetDecision budget = sessionRiskBudgetService.check(requester, effect);
        if (!budget.allowed()) {
            Map<String, Object> budgetBlock = new LinkedHashMap<>();
            budgetBlock.put("success", false);
            budgetBlock.put("error", budget.message());
            budgetBlock.put("securityCode", budget.code());
            budgetBlock.put("traceId", traceId);
            budgetBlock.put("duration", System.currentTimeMillis() - startTime);
            attachEffectMeta(budgetBlock, effect, effectFingerprint, sessionRiskBudgetService.summary(requester));
            mcpAuditService.persistGateReject(
                    traceId,
                    userInstruction,
                    toolName,
                    GateDecision.block(budget.code(), budget.message()),
                    startTime,
                    parameters);
            return decorateEnvelope(budgetBlock, toolName, traceId, 429);
        }

        Map<String, Object> execResponse = mcpExecutionService.execute(
                toolName, parameters, startTime, traceId, userInstruction);
        execResponse.put("traceId", traceId);
        attachEffectMeta(execResponse, effect, effectFingerprint, sessionRiskBudgetService.summary(requester));
        if (Boolean.TRUE.equals(execResponse.get("success"))
                && WriteToolResultSupport.requestedRealWrite(parameters)
                && !Boolean.TRUE.equals(execResponse.get("writeMismatch"))) {
            sessionRiskBudgetService.consume(requester, effect);
            execResponse.put("riskBudget", sessionRiskBudgetService.summary(requester));
        }
        mcpAuditService.persistSuccess(traceId, userInstruction, decision, toolName, execResponse, startTime, parameters);
        return decorateEnvelope(execResponse, toolName, traceId, Boolean.TRUE.equals(execResponse.get("success")) ? 200 : 500);
    }

    @PostMapping("/confirmExecute")
    public Map<String, Object> confirmExecuteTool(@RequestBody Map<String, Object> request) {
        long startTime = System.currentTimeMillis();
        String traceId = UUID.randomUUID().toString();

        if (request == null) {
            return errorResponse("请求体不能为空", traceId, startTime, "", 400);
        }

        String confirmCode = stringField(request, "confirmCode");
        String confirmationId = stringField(request, "confirmationId");
        String capabilityToken = stringField(request, "capabilityToken");
        if (confirmationId.isBlank()) {
            confirmationId = stringField(request, "traceId");
        }

        log.info("收到 MCP 确认执行请求: confirmationId={}, confirmCode={}", confirmationId, confirmCode);

        if (!"确认执行".equals(confirmCode)) {
            return errorResponse("确认码不正确，请输入 '确认执行'", traceId, startTime, "", 400);
        }

        String requester = currentRequester();
        McpPendingConfirmationService.TakeResult takeResult =
                mcpPendingConfirmationService.take(confirmationId, requester, capabilityToken);
        if (takeResult.status() != McpPendingConfirmationService.TakeStatus.OK || takeResult.pending() == null) {
            return switch (takeResult.status()) {
                case REQUESTER_MISMATCH -> errorResponse(
                        "该待确认请求不属于当前用户",
                        confirmationIdOr(traceId, confirmationId),
                        startTime,
                        "",
                        403);
                case TOKEN_MISMATCH -> errorResponse(
                        "能力凭证无效或与效果指纹不匹配，请重新预览确认",
                        confirmationIdOr(traceId, confirmationId),
                        startTime,
                        "",
                        403);
                case EXPIRED -> errorResponse(
                        "待确认请求已过期，请重新发起预览",
                        confirmationIdOr(traceId, confirmationId),
                        startTime,
                        "",
                        410);
                default -> errorResponse(
                        "未找到对应的待确认请求，请重新预览",
                        confirmationIdOr(traceId, confirmationId),
                        startTime,
                        "",
                        404);
            };
        }

        McpPendingConfirmationService.PendingConfirmation pending = takeResult.pending();
        traceId = pending.confirmationId();
        String toolName = pending.toolName();
        Map<String, Object> platformSupport = mcpToolCatalog.describePlatformSupport(toolName);
        if (!Boolean.TRUE.equals(platformSupport.get("available"))) {
            return unsupportedResponse(toolName, traceId, startTime, platformSupport);
        }

        Map<String, Object> parameters = new HashMap<>(pending.parameters());
        String userMessage = pending.userMessage();
        String userInstruction = pending.userInstruction();
        ToolEffect effect = pending.effect() != null
                ? pending.effect()
                : toolEffectResolver.resolve(toolName, parameters);
        String effectFingerprint = pending.effectFingerprint() == null || pending.effectFingerprint().isBlank()
                ? EffectFingerprint.of(toolName, parameters, effect)
                : pending.effectFingerprint();

        // 确认后仍复核效果指纹，防止快照被异常篡改
        String recomputed = EffectFingerprint.of(toolName, parameters, effect);
        if (!recomputed.equals(effectFingerprint)) {
            return errorResponse(
                    "效果指纹校验失败，请重新预览确认",
                    traceId,
                    startTime,
                    toolName,
                    409);
        }

        SessionRiskBudgetService.BudgetDecision budget = sessionRiskBudgetService.check(requester, effect);
        if (!budget.allowed()) {
            Map<String, Object> budgetBlock = new LinkedHashMap<>();
            budgetBlock.put("success", false);
            budgetBlock.put("error", budget.message());
            budgetBlock.put("securityCode", budget.code());
            budgetBlock.put("traceId", traceId);
            budgetBlock.put("duration", System.currentTimeMillis() - startTime);
            attachEffectMeta(budgetBlock, effect, effectFingerprint, sessionRiskBudgetService.summary(requester));
            return decorateEnvelope(budgetBlock, toolName, traceId, 429);
        }

        McpToolParamReader.forceConfirmedWriteToolParams(toolName, parameters);
        GateDecision decision = mcpSecurityService.evaluatePostConfirm(toolName, parameters, userMessage);
        if (decision.getType() == GateDecision.Type.BLOCK) {
            log.warn("确认执行路径仍被安全门拦截: tool={} code={}", toolName, decision.getCode());
            mcpAuditService.persistGateReject(traceId, userInstruction, toolName, decision, startTime, parameters);
            failureInsightService.captureReject(userMessage, decision.getCode(), toolName, decision.getMessage(), traceId);
            Map<String, Object> blocked = mcpSecurityService.buildBlockResponse(traceId, startTime, decision);
            attachEffectMeta(blocked, effect, effectFingerprint, sessionRiskBudgetService.summary(requester));
            return decorateEnvelope(blocked, toolName, traceId, 403);
        }

        log.info("用户已确认，执行工具: {} effect={}", toolName, effect.action());
        Map<String, Object> execResponse = mcpExecutionService.execute(
                toolName, parameters, startTime, traceId, userInstruction, true);
        execResponse.put("traceId", traceId);
        attachEffectMeta(execResponse, effect, effectFingerprint, sessionRiskBudgetService.summary(requester));
        if (Boolean.TRUE.equals(execResponse.get("success"))
                && !Boolean.TRUE.equals(execResponse.get("writeMismatch"))
                && !Boolean.TRUE.equals(execResponse.get("evidenceIncomplete"))) {
            sessionRiskBudgetService.consume(requester, effect);
            execResponse.put("riskBudget", sessionRiskBudgetService.summary(requester));
        }
        mcpAuditService.persistSuccess(traceId, userInstruction, decision, toolName, execResponse, startTime, parameters);
        return decorateEnvelope(execResponse, toolName, traceId, Boolean.TRUE.equals(execResponse.get("success")) ? 200 : 500);
    }

    public Map<String, Object> executeDeferredScheduledTool(String toolName, Map<String, Object> parameters) {
        long startTime = System.currentTimeMillis();
        String traceId = UUID.randomUUID().toString();

        if (toolName == null || toolName.isBlank()) {
            return errorResponse("工具名称不能为空", traceId, startTime, "", 400);
        }
        if (parameters == null) {
            parameters = new HashMap<>();
        }
        Map<String, Object> platformSupport = mcpToolCatalog.describePlatformSupport(toolName);
        if (!Boolean.TRUE.equals(platformSupport.get("available"))) {
            return unsupportedResponse(toolName, traceId, startTime, platformSupport);
        }

        String userInstruction = mcpSecurityService.buildInstruction(toolName, parameters);
        GateDecision decision = mcpSecurityService.evaluateDeferred(toolName, parameters);
        if (decision.getType() == GateDecision.Type.BLOCK) {
            log.warn("延时任务被安全门拦截: tool={} code={}", toolName, decision.getCode());
            Map<String, Object> reject = new LinkedHashMap<>();
            reject.put("success", false);
            reject.put("error", decision.getMessage());
            reject.put("securityCode", decision.getCode());
            reject.put("traceId", traceId);
            reject.put("duration", System.currentTimeMillis() - startTime);
            return decorateEnvelope(reject, toolName, traceId, 403);
        }

        String deferredInstruction = "[DEFERRED_SCHEDULE] " + userInstruction;
        if (WriteToolResultSupport.requestedRealWrite(parameters)) {
            McpToolParamReader.forceConfirmedWriteToolParams(toolName, parameters);
        }
        boolean confirmedWrite = WriteToolResultSupport.requestedRealWrite(parameters);
        Map<String, Object> execResponse = mcpExecutionService.execute(
                toolName, parameters, startTime, traceId, deferredInstruction, confirmedWrite);
        execResponse.put("traceId", traceId);
        mcpAuditService.persistSuccess(traceId, deferredInstruction, decision, toolName, execResponse, startTime, parameters);
        return decorateEnvelope(execResponse, toolName, traceId, Boolean.TRUE.equals(execResponse.get("success")) ? 200 : 500);
    }

    private String currentRequester() {
        String requester = httpAuditSubject.currentOperatorId();
        return requester == null || requester.isBlank() ? "anonymous" : requester;
    }

    private Map<String, Object> decorateEnvelope(Map<String, Object> raw,
                                                 String toolName,
                                                 String traceId,
                                                 int statusCode) {
        Map<String, Object> response = new LinkedHashMap<>();
        if (raw != null) {
            response.putAll(raw);
        }
        Map<String, Object> platformSupport = mcpToolCatalog.describePlatformSupport(toolName);
        response.put("toolName", toolName);
        response.put("traceId", response.getOrDefault("traceId", traceId));
        response.put("statusCode", statusCode);
        response.putIfAbsent("success", false);
        response.putIfAbsent("needConfirm", false);
        response.putIfAbsent("confirmationId", "");
        response.putIfAbsent("expiresAtMs", null);
        response.putIfAbsent("capabilityToken", "");
        response.put("platformSupport", platformSupport);
        response.put("warnings", buildWarnings(response, platformSupport));
        return response;
    }

    private static void attachEffectMeta(
            Map<String, Object> response,
            ToolEffect effect,
            String effectFingerprint,
            Map<String, Object> riskBudget) {
        if (response == null) {
            return;
        }
        if (effect != null) {
            response.put("toolEffect", effect.toMap());
        }
        if (effectFingerprint != null && !effectFingerprint.isBlank()) {
            response.put("effectFingerprint", effectFingerprint);
        }
        if (riskBudget != null) {
            response.put("riskBudget", riskBudget);
        }
    }

    private long pendingTtlHint() {
        return 900_000L;
    }

    private Map<String, Object> unsupportedResponse(String toolName,
                                                    String traceId,
                                                    long startTime,
                                                    Map<String, Object> platformSupport) {
        String reason = String.valueOf(platformSupport.getOrDefault("reason", "当前平台暂不支持该工具"));
        Map<String, Object> response = errorResponse(reason, traceId, startTime, toolName, 409);
        response.put("platformSupport", platformSupport);
        response.put("warnings", List.of(reason));
        return response;
    }

    private List<String> buildWarnings(Map<String, Object> response, Map<String, Object> platformSupport) {
        List<String> warnings = new ArrayList<>();
        if (platformSupport != null && !Boolean.TRUE.equals(platformSupport.get("available"))) {
            warnings.add(String.valueOf(platformSupport.getOrDefault("reason", "当前平台不可用")));
        }
        Object writeMismatchMessage = response.get("writeMismatchMessage");
        if (writeMismatchMessage instanceof String message && !message.isBlank()) {
            warnings.add(message.trim());
        }
        Object evidenceMessage = response.get("evidenceMessage");
        if (evidenceMessage instanceof String message && !message.isBlank()) {
            warnings.add(message.trim());
        }
        return warnings;
    }

    private static String confirmationIdOr(String fallbackTraceId, String confirmationId) {
        return confirmationId == null || confirmationId.isBlank() ? fallbackTraceId : confirmationId;
    }

    private static String stringField(Map<String, Object> request, String key) {
        if (request == null || key == null) {
            return "";
        }
        Object value = request.get(key);
        if (value == null) {
            return "";
        }
        return String.valueOf(value).trim();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> extractParameters(Map<String, Object> request) {
        if (request == null) {
            return new HashMap<>();
        }
        Object raw = request.get("parameters");
        if (raw instanceof Map<?, ?> map) {
            return new HashMap<>((Map<String, Object>) map);
        }
        return new HashMap<>();
    }

    private Map<String, Object> errorResponse(String message) {
        return errorResponse(message, UUID.randomUUID().toString(), System.currentTimeMillis(), "", 500);
    }

    private Map<String, Object> errorResponse(String message,
                                              String traceId,
                                              long startTime,
                                              String toolName,
                                              int statusCode) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", false);
        response.put("error", message);
        response.put("traceId", traceId);
        response.put("duration", System.currentTimeMillis() - startTime);
        return decorateEnvelope(response, toolName, traceId, statusCode);
    }
}
