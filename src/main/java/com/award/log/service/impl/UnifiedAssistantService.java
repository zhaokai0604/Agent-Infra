package com.award.log.service.impl;

import com.award.log.agent.AssistantIntentCategory;
import com.award.log.agent.AssistantIntentSignals;
import com.award.log.agent.AssistantReplyMode;
import com.award.log.agent.AssistantReplyPlanner;
import com.award.log.agent.AssistantReplyPrompts;
import com.award.log.agent.AgentExecutionState;
import com.award.log.agent.AssistantStreamEvents;
import com.award.log.agent.AgentSkillPlan;
import com.award.log.agent.AgentToolPhase;
import com.award.log.agent.AssistantAuditRecorder;
import com.award.log.agent.MultiAgentContextBuilder;
import com.award.log.agent.OpsIntentRouter;
import com.award.log.agent.OpsRuntimeService;
import com.award.log.agent.OpsReportFormat;
import com.award.log.agent.awm.FailureInsightService;
import com.award.log.agent.awm.OpsWorkflow;
import com.award.log.agent.awm.WorkflowMemoryService;
import com.award.log.agent.awm.WorkflowRetriever;
import com.award.log.mcp.McpToolCatalog;
import com.award.log.mcp.McpToolRegistry;
import com.award.log.security.AgenticRiskScoreEngine;
import com.award.log.security.ChatToolExecutionTracker;
import com.award.log.security.HighRiskCommandDetector;
import com.award.log.security.IntentRiskFilter;
import com.award.log.security.McpInvocationSecurityGate;
import com.award.log.security.McpToolSurface;
import com.award.log.security.OpsSecurityContext;
import com.award.log.security.PromptInjectionGuard;
import com.award.log.security.ReadOnlySurfaceDenylist;
import com.award.log.security.RiskLevel;
import com.award.log.security.ToolSurfaceResolver;
import com.award.log.security.WriteExecutionCoordinator;
import com.award.log.service.AiLogAlarmService;
import com.award.log.service.AiModelRouter;
import com.award.log.service.KnowledgeBaseService;
import com.award.log.service.OpsOpenIncidentService;
import com.award.log.service.StatisticsService;
import com.award.log.util.AiChatResponseSupport;
import com.award.log.util.AiUsageSupport;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ChatModelCallAdvisor;
import org.springframework.ai.chat.client.advisor.ToolCallAdvisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
public class UnifiedAssistantService {

    public record ChatTurn(String role, String content) {
    }

    public static final String ASSISTANT_META_PREFIX = "ASSISTANT_META:";

    private record ToolAgentCompletion(
            String markdown,
            String traceId,
            String securityOutcome,
            List<String> toolsUsed,
            boolean writeConfirmed,
            boolean realWriteOk,
            boolean anyTool,
            Map<String, Object> executionState,
            Map<String, Object> contextUsage
    ) {
    }

    private static final int MAX_HISTORY_TURNS = 24;
    private static final int MAX_TURN_CHARS = 2800;

    @Autowired
    private ChatModel chatModel;

    @Autowired(required = false)
    private AiModelRouter aiModelRouter;

    @Autowired
    private StatisticsService statisticsService;

    @Autowired
    private AiLogAlarmService aiLogAlarmService;

    @Autowired
    private MultiAgentContextBuilder multiAgentContextBuilder;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private IntentRiskFilter intentRiskFilter;

    @Autowired
    private ToolSurfaceResolver toolSurfaceResolver;

    @Autowired
    private AgenticRiskScoreEngine agenticRiskScoreEngine;

    @Autowired(required = false)
    private ChatClient.Builder chatClientBuilder;

    @Autowired(required = false)
    private ToolCallingManager toolCallingManager;

    @Autowired
    private McpToolRegistry mcpToolRegistry;

    @Autowired
    private McpToolCatalog mcpToolCatalog;

    @Autowired
    private PromptInjectionGuard promptInjectionGuard;

    @Autowired
    private HighRiskCommandDetector highRiskCommandDetector;

    @Autowired
    private ReadOnlySurfaceDenylist readOnlySurfaceDenylist;

    @Autowired(required = false)
    private OpsRuntimeService opsRuntimeService;

    @Autowired(required = false)
    private FailureInsightService failureInsightService;

    @Autowired(required = false)
    private WorkflowRetriever workflowRetriever;

    @Autowired(required = false)
    private WorkflowMemoryService workflowMemoryService;

    @Autowired
    private OpsIntentRouter opsIntentRouter;

    @Autowired
    private AssistantReplyPlanner assistantReplyPlanner;

    @Autowired
    private AssistantAuditRecorder assistantAuditRecorder;

    @Autowired(required = false)
    private OpsOpenIncidentService opsOpenIncidentService;

    @Autowired(required = false)
    private KnowledgeBaseService knowledgeBaseService;

    @Value("${knowledge.search-top-k:5}")
    private int knowledgeSearchTopK;

    @Value("${agent.assistant.use-tool-agent-default:true}")
    private boolean defaultUseToolAgent;

    @Value("${agent.assistant.orchestrator.enabled:true}")
    private boolean orchestratorEnabled;

    @Value("${agent.assistant.tool-agent-stream-chunk-chars:900}")
    private int toolAgentStreamChunkChars;

    @Value("${agent.assistant.tool-catalog-prompt-max:28}")
    private int toolCatalogPromptMax;

    @Value("${spring.ai.openai.chat.options.model:unknown}")
    private String configuredModelName;

    @Value("${agent.ai.default-context-window:32768}")
    private int configuredContextWindow;

    public Map<String, Object> getAssistantContext() {
        return getAssistantContext(null);
    }

    public Map<String, Object> getAssistantContext(String userMessage) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("agentHostOs", buildAgentHostOsSummary());

        try {
            context.put("performance", statisticsService.getSystemPerformance(null));
        } catch (Exception e) {
            log.warn("assistant context performance unavailable: {}", e.getMessage());
            context.put("performance", Map.of("unavailable", true));
        }

        try {
            context.put("taskStats", statisticsService.getTaskStatusStatistics());
        } catch (Exception e) {
            log.warn("assistant context task stats unavailable: {}", e.getMessage());
            context.put("taskStats", Map.of("unavailable", true));
        }

        Map<String, Object> alarmStats = Map.of();
        try {
            alarmStats = aiLogAlarmService.getAlarmStatistics(1, null, null);
            if (alarmStats == null) {
                alarmStats = Map.of();
            }
            context.put("alarmStats", alarmStats);
        } catch (Exception e) {
            log.warn("assistant context alarm stats unavailable: {}", e.getMessage());
            context.put("alarmStats", Map.of("unavailable", true));
        }

        try {
            Map<String, Object> recent = aiLogAlarmService.getAlarmHistory(1, 5, null, null);
            context.put("recentAlarms", recent != null ? recent.getOrDefault("list", List.of()) : List.of());
        } catch (Exception e) {
            log.warn("assistant context recent alarms unavailable: {}", e.getMessage());
            context.put("recentAlarms", List.of());
        }

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> performance = (Map<String, Object>) context.getOrDefault("performance", Map.of());
            context.put("runbooks", buildRunbookSuggestions(performance, alarmStats));
        } catch (Exception e) {
            log.warn("assistant context runbook suggestions unavailable: {}", e.getMessage());
            context.put("runbooks", List.of());
        }

        try {
            context.put("agentMultiTeam", multiAgentContextBuilder.buildForUser(userMessage));
        } catch (Exception e) {
            log.warn("assistant context multi-agent summary unavailable: {}", e.getMessage());
            context.put("agentMultiTeam", Map.of("unavailable", true));
        }

        if (userMessage != null && !userMessage.isBlank()) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> multiTeam = (Map<String, Object>) context.get("agentMultiTeam");
                if (multiTeam != null && multiTeam.get("diagnosisAgent") instanceof Map<?, ?> diagnosis) {
                    Object hits = diagnosis.get("similarHistoricalCases");
                    if (hits instanceof List<?>) {
                        context.put("knowledgeRagHits", hits);
                    }
                }
            } catch (Exception ignored) {
                // optional mirror
            }
        }

        try {
            if (opsOpenIncidentService != null) {
                context.put("openIncident", opsOpenIncidentService.buildOpenIncidentContext());
            }
        } catch (Exception e) {
            log.warn("assistant context open incident unavailable: {}", e.getMessage());
            context.put("openIncident", Map.of("hasOpenIncident", false));
        }

        return context;
    }

    public Flux<String> chatStream(String userMessage) {
        return chatStream(userMessage, List.of());
    }

    public Flux<String> chatStream(String userMessage, List<ChatTurn> history) {
        return chatStream(userMessage, history, defaultUseToolAgent);
    }

    public Flux<String> chatStream(String userMessage, List<ChatTurn> history, boolean useToolAgent) {
        return chatStream(userMessage, history, useToolAgent, false);
    }

    public Flux<String> chatStream(String userMessage,
                                   List<ChatTurn> history,
                                   boolean useToolAgent,
                                   boolean confirmRemediation) {
        return chatStream(userMessage, history, useToolAgent, confirmRemediation, null);
    }

    public Flux<String> chatStream(String userMessage,
                                   List<ChatTurn> history,
                                   boolean useToolAgent,
                                   boolean confirmRemediation,
                                   String requestedModelProfile) {
        String safe = McpInvocationSecurityGate.enforceChatMessageLimit(
                userMessage == null ? "" : userMessage.trim(), 32_000);
        if (safe.isBlank()) {
            return Flux.just("请输入您的问题或需求。");
        }
        AssistantReplyPlanner.ReplyPlan plan = assistantReplyPlanner.plan(
                safe, history, useToolAgent, confirmRemediation, orchestratorEnabled, opsRuntimeService);
        if (plan.useToolAgentPath()) {
            return chatStreamWithToolAgent(safe, history, confirmRemediation, plan, requestedModelProfile);
        }
        return chatStreamPlain(safe, history, plan, requestedModelProfile);
    }

    public Map<String, Object> previewAgentState(String userMessage,
                                                 List<ChatTurn> history,
                                                 boolean useToolAgent,
                                                 boolean confirmRemediation) {
        String safe = McpInvocationSecurityGate.enforceChatMessageLimit(
                userMessage == null ? "" : userMessage.trim(), 32_000);
        AssistantReplyPlanner.ReplyPlan plan = assistantReplyPlanner.plan(
                safe, history, useToolAgent, confirmRemediation, orchestratorEnabled, opsRuntimeService);
        boolean allowWrite = confirmRemediation || (opsIntentRouter != null && opsIntentRouter.forceRemediate(safe));
        String conversationContext = AssistantIntentSignals.recentConversationText(safe, history, 8);
        OpsWorkflow awmHit = resolveAwmHit(safe, conversationContext);
        List<String> plannedTools = AgentSkillPlan.resolveTools(safe, conversationContext, awmHit, allowWrite);
        String planPhase = AgentSkillPlan.planPhase(allowWrite, plannedTools);
        boolean awaitingConfirm = !allowWrite && AgentSkillPlan.hasWriteTools(plannedTools);

        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("message", safe);
        preview.put("replyMode", plan.mode().name());
        preview.put("intentCategory", plan.category().name());
        preview.put("useToolAgentPath", plan.useToolAgentPath());
        preview.put("planPhase", planPhase);
        preview.put("writeConfirmed", allowWrite);
        preview.put("awaitingConfirm", awaitingConfirm);
        preview.put("plannedTools", plannedTools);
        preview.put("observeTools", AgentSkillPlan.observeTools(plannedTools));
        preview.put("pendingWriteTools", AgentSkillPlan.pendingWriteTools(plannedTools));
        preview.put("executionState", AgentExecutionState.build(
                plan.mode().name(),
                allowWrite,
                allowWrite,
                plannedTools,
                planPhase,
                null,
                null,
                awmHit));
        if (awmHit != null) {
            preview.put("awmWorkflowId", awmHit.workflowId());
            preview.put("awmWorkflowTitle", awmHit.title());
        }
        return preview;
    }

    Map<String, Object> buildChatMeta(String userMessage, AssistantReplyPlanner.ReplyPlan plan) {
        Map<String, Object> extras = new LinkedHashMap<>();
        if (plan != null) {
            extras.put("replyMode", plan.mode().name());
            extras.put("intentCategory", plan.category().name());
        }
        return buildChatMeta(userMessage, extras);
    }

    Map<String, Object> buildChatMeta(String userMessage, Map<String, Object> extras) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("type", "assistant-meta");
        meta.put("ragHits", List.of());
        meta.put("writeConfirmed", false);
        if (knowledgeBaseService != null && userMessage != null && !userMessage.isBlank()) {
            try {
                meta.put("ragHits", knowledgeBaseService.search(userMessage, knowledgeSearchTopK));
            } catch (Exception e) {
                log.debug("knowledge lookup skipped in assistant meta: {}", e.getMessage());
            }
        }
        if (extras != null && !extras.isEmpty()) {
            meta.putAll(extras);
        }
        return meta;
    }

    private Flux<String> chatStreamPlain(String userMessage,
                                         List<ChatTurn> history,
                                         AssistantReplyPlanner.ReplyPlan plan,
                                         String requestedModelProfile) {
        AssistantReplyMode mode = plan.mode();
        Map<String, Object> context = switch (mode) {
            case CHITCHAT -> Map.of();
            case CONVERSATION -> buildLightContext(userMessage);
            case OPS_ANALYSIS -> buildOpsSummaryContext(userMessage);
            default -> getAssistantContext(userMessage);
        };
        if (mode != AssistantReplyMode.CHITCHAT) {
            augmentContextWithSessionSecurity(userMessage, context);
        }
        String systemPrompt = buildSystemPromptForPlan(plan, context);
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(systemPrompt));
        for (ChatTurn turn : trimHistory(history)) {
            String content = turn.content() == null ? "" : turn.content().trim();
            String role = turn.role() == null ? "" : turn.role().trim();
            if (content.isEmpty()) {
                continue;
            }
            if ("user".equalsIgnoreCase(role)) {
                messages.add(new UserMessage(content));
            } else if ("assistant".equalsIgnoreCase(role)) {
                messages.add(new AssistantMessage(content));
            }
        }
        messages.add(new UserMessage(userMessage));

        Prompt prompt = new Prompt(messages);
        AiModelRouter.ResolvedModel resolvedModel = resolveModel(
                userMessage, AiUsageSupport.estimatePromptTokens(messages), false, requestedModelProfile);
        Map<String, Object> routeMeta = routingMeta(resolvedModel);
        AtomicReference<String> streamAcc = new AtomicReference<>("");
        AtomicReference<ChatResponse> lastResponse = new AtomicReference<>();
        boolean sanitizeOps = mode == AssistantReplyMode.OPS_ANALYSIS;
        Flux<String> body = resolvedModel.chatModel().stream(prompt)
                .doOnNext(lastResponse::set)
                .map(AiChatResponseSupport::textFrom)
                .map(piece -> toStreamingDelta(streamAcc, piece))
                .filter(piece -> !piece.isEmpty())
                .timeout(Duration.ofSeconds(90))
                .retry(1)
                .onErrorResume(e -> {
                    log.error("assistant plain stream failed", e);
                    return Flux.just("助手暂时不可用：" + e.getMessage());
                });
        if (sanitizeOps) {
            body = body.collectList()
                    .map(parts -> {
                        String full = String.join("", parts);
                        String cleaned = WriteExecutionCoordinator.sanitizeAssistantText(full, false);
                        return appendChatAntiHallucinationFooter(cleaned, false, false);
                    })
                    .flatMapMany(this::chunkTextForSse);
        }
        body = Flux.concat(body, usageEvent(
                resolvedModel,
                lastResponse,
                AiUsageSupport.estimatePromptTokens(messages),
                streamAcc));
        routeMeta.put("contextUsagePending", true);
        return prependAssistantMeta(body, userMessage, routeMeta, plan);
    }

    Map<String, Object> buildChatMeta(String userMessage) {
        return buildChatMeta(userMessage, Map.of());
    }

    private Flux<String> chatStreamWithToolAgent(String userMessage,
                                                 List<ChatTurn> history,
                                                 boolean confirmRemediation,
                                                 AssistantReplyPlanner.ReplyPlan plan,
                                                 String requestedModelProfile) {
        if (promptInjectionGuard != null && promptInjectionGuard.isInjection(userMessage)) {
            captureStreamReject(userMessage, "INJECTION", null, "prompt injection detected");
            return Flux.just("检测到疑似注入内容，本轮已拦截，请换一种说法描述您的运维需求。");
        }
        if (highRiskCommandDetector != null && highRiskCommandDetector.isHighRiskCommand(userMessage)) {
            captureStreamReject(userMessage, "HIGH_RISK_COMMAND", null, "high-risk command pattern");
            return Flux.just("检测到高危命令模式，已阻止自动执行。如需排查，请说明具体场景（例如「磁盘占用高」）。");
        }

        RiskLevel intentRisk = intentRiskFilter.evaluate(userMessage);
        if (intentRisk == RiskLevel.HIGH) {
            captureStreamReject(userMessage, "HIGH_INTENT", null, "high-risk intent");
            return Flux.just("该请求被判定为高风险意图，对话内自动工具执行已拦截。");
        }

        boolean allowWrite = confirmRemediation || opsIntentRouter.forceRemediate(userMessage);
        // writeConfirmed 仅表示用户意图确认；是否落地由结束时 securityOutcome / Tracker 再定
        Map<String, Object> metaExtras = new LinkedHashMap<>();
        metaExtras.put("userIntentConfirmed", allowWrite);
        metaExtras.put("writeConfirmed", false);
        metaExtras.putAll(routingMeta(resolveModel(
                userMessage, AiUsageSupport.estimateTokens(userMessage), true, requestedModelProfile)));

        String conversationContext = AssistantIntentSignals.recentConversationText(userMessage, history, 8);
        OpsWorkflow awmHit = resolveAwmHit(userMessage, conversationContext);
        recordAwmPlanningHit(awmHit);
        List<String> plannedTools = AgentSkillPlan.resolveTools(
                userMessage, conversationContext, awmHit, false);
        String planPhase = AgentSkillPlan.planPhase(allowWrite, plannedTools);
        boolean awaitingConfirm = !allowWrite && AgentSkillPlan.hasWriteTools(plannedTools);
        metaExtras.put("plannedTools", plannedTools);
        metaExtras.put("planThenAct", true);
        metaExtras.put("planPhase", planPhase);
        metaExtras.put("awaitingConfirm", awaitingConfirm);
        metaExtras.put("observeTools", AgentSkillPlan.observeTools(plannedTools));
        metaExtras.put("pendingWriteTools", AgentSkillPlan.pendingWriteTools(plannedTools));
        metaExtras.put("writeToolsMounted", false);
        metaExtras.put("executionState", AgentExecutionState.build(
                plan.mode().name(),
                false,
                false,
                plannedTools,
                planPhase,
                null,
                null,
                awmHit));
        if (awmHit != null) {
            metaExtras.put("awmWorkflowId", awmHit.workflowId());
            metaExtras.put("awmWorkflowTitle", awmHit.title());
        }

        // Planner 已选择 Tool Agent 时，不能再被关键词命中的固定剧本截胡；
        // 只有明确的巡检编排计划才进入 runPlaybook，其余运维任务交给模型选择工具。
        if (plan.mode() == AssistantReplyMode.ORCHESTRATE
                && orchestratorEnabled && opsRuntimeService != null
                && !AssistantIntentSignals.isBroadMetricsQuery(userMessage)) {
            OpsIntentRouter.Playbook playbook = opsIntentRouter.resolveFromContext(userMessage, history);
            if (playbook != null && playbook != OpsIntentRouter.Playbook.NONE) {
            McpToolSurface surface = toolSurfaceResolver.resolve(userMessage, intentRisk);
            String traceId = UUID.randomUUID().toString();
            String hint = plan.statusHintZh().isBlank()
                    ? AssistantReplyPrompts.orchestrateStatusHint()
                    : plan.statusHintZh();
            AssistantReplyPlanner.ReplyPlan orchestratePlan = new AssistantReplyPlanner.ReplyPlan(
                    AssistantReplyMode.ORCHESTRATE, plan.category(), true, hint);
            List<String> orchTools = AgentSkillPlan.resolveTools(
                    userMessage, conversationContext, awmHit, true);
            String orchPhase = AgentSkillPlan.planPhase(allowWrite, orchTools);
            metaExtras.put("plannedTools", orchTools);
            metaExtras.put("planPhase", orchPhase);
            metaExtras.put("awaitingConfirm", !allowWrite && AgentSkillPlan.hasWriteTools(orchTools));
            metaExtras.put("observeTools", AgentSkillPlan.observeTools(orchTools));
            metaExtras.put("pendingWriteTools", AgentSkillPlan.pendingWriteTools(orchTools));
            // 编排路径仍走 orchestrator 内置 Dry-Run；写真实落地仍需确认
            metaExtras.put("writeToolsMounted", allowWrite);
            metaExtras.put("executionState", AgentExecutionState.build(
                    orchestratePlan.mode().name(),
                    allowWrite,
                    allowWrite,
                    orchTools,
                    orchPhase,
                    traceId,
                    null,
                    awmHit));
            metaExtras.put("playbook", playbook.name());
            String prepareHint = allowWrite
                    ? "用户已确认，按计划执行观测与处置编排…"
                    : hint;
            Flux<String> body = Flux.concat(
                    progressEventFlux("route", "已识别为巡检编排任务"),
                    progressEventFlux("prepare", prepareHint),
                    toolPlanEventFlux(orchTools, "ORCHESTRATE", allowWrite, orchPhase,
                            AgentExecutionState.build(
                                    orchestratePlan.mode().name(),
                                    allowWrite,
                                    allowWrite,
                                    orchTools,
                                    orchPhase,
                                    traceId,
                                    null,
                                    awmHit)),
                    progressEventFlux("run", allowWrite
                            ? "正在按确认计划执行本机观测与处置编排…"
                            : "正在按计划执行本机观测与处置编排，请稍候…"),
                    Mono.fromCallable(() -> {
                                OpsSecurityContext.openChatAgent(traceId, userMessage, surface, allowWrite);
                                try {
                                    return opsRuntimeService.runPlaybook(
                                            playbook, userMessage, surface, intentRisk);
                                } finally {
                                    OpsSecurityContext.clear();
                                }
                            })
                            .subscribeOn(Schedulers.boundedElastic())
                            .timeout(Duration.ofMinutes(5))
                            .flatMapMany(result -> {
                                if (result != null && result.softFallback()) {
                                    log.info("playbook soft-fallback to tool-agent reason={}",
                                            result.report() != null
                                                    ? result.report().get("softFallbackReason")
                                                    : "PLAYBOOK_NONE");
                                    return Flux.concat(
                                            progressEventFlux("route", "未命中固定剧本，改走工具增强诊断"),
                                            streamToolAgentBody(
                                                    userMessage, history, confirmRemediation, plan,
                                                    allowWrite, metaExtras, plannedTools, planPhase,
                                                    awaitingConfirm, awmHit, requestedModelProfile));
                                }
                                Flux<String> event = Flux.empty();
                                String encoded = AssistantStreamEvents.encode(result.report());
                                if (encoded != null) {
                                    event = Flux.just(encoded);
                                }
                                Object outcome = result.report() != null
                                        ? result.report().get("securityOutcome") : null;
                                boolean orchLanded = "EXECUTED".equals(String.valueOf(outcome));
                                recordAwmOrchestratedFeedback(
                                        awmHit,
                                        result.traceId(),
                                        String.valueOf(outcome),
                                        result.markdown());
                                return Flux.concat(
                                        progressEventFlux("synthesize", "编排完成，正在整理答复…"),
                                        event,
                                        chunkTextForSse(appendChatAntiHallucinationFooter(
                                                result.markdown(), allowWrite, orchLanded)));
                    })
            ).onErrorResume(e -> {
                log.error("assistant orchestrated run failed", e);
                return Flux.just("巡检编排执行失败：" + e.getMessage());
            });
            return prependAssistantMeta(body, userMessage, metaExtras, orchestratePlan);
            }
        }

        return prependAssistantMeta(
                streamToolAgentBody(
                        userMessage, history, confirmRemediation, plan,
                        allowWrite, metaExtras, plannedTools, planPhase,
                        awaitingConfirm, awmHit, requestedModelProfile),
                userMessage, metaExtras, plan);
    }

    private Flux<String> streamToolAgentBody(
            String userMessage,
            List<ChatTurn> history,
            boolean confirmRemediation,
            AssistantReplyPlanner.ReplyPlan plan,
            boolean allowWrite,
            Map<String, Object> metaExtras,
            List<String> plannedTools,
            String planPhase,
            boolean awaitingConfirm,
            OpsWorkflow awmHit,
            String requestedModelProfile) {
        boolean writeMounted = allowWrite;
        metaExtras.put("writeToolsMounted", writeMounted);
        String toolHint = plan.statusHintZh().isBlank()
                ? (allowWrite
                    ? "用户已确认，正在按计划调用本机运维工具落地…"
                    : AgentToolPhase.phaseHintZh(false, awaitingConfirm))
                : plan.statusHintZh();
        String runHint = allowWrite
                ? "正在按确认计划执行（写工具已挂载）…"
                : (awaitingConfirm
                    ? "正在仅用观测工具诊断；写工具待确认后挂载…"
                    : "正在按计划调用本机观测工具采集数据…");
        List<String> toolsForRun = plannedTools;
        OpsWorkflow awmForRun = awmHit;
        return Flux.concat(
                    progressEventFlux("route", allowWrite ? "已识别为确认执行" : "已识别为工具增强诊断"),
                    progressEventFlux("prepare", toolHint),
                toolPlanEventFlux(toolsForRun, "TOOL_AGENT", allowWrite, planPhase,
                        AgentExecutionState.build(
                                plan.mode().name(),
                                allowWrite,
                                writeMounted,
                                toolsForRun,
                                planPhase,
                                null,
                                null,
                                awmForRun)),
                progressEventFlux("run", runHint),
                Mono.fromCallable(() -> runBlockingToolAugmentedCompletion(
                                userMessage, history, confirmRemediation, plan,
                                toolsForRun, planPhase, awmForRun, requestedModelProfile))
                        .subscribeOn(Schedulers.boundedElastic())
                        .timeout(Duration.ofMinutes(4))
                        .flatMapMany(completion -> Flux.concat(
                                progressEventFlux("synthesize", "采集完成，正在生成说明…"),
                                toolAgentResultEventFlux(completion),
                                chunkTextForSse(completion.markdown()),
                                usageEvent(completion.contextUsage())))
        ).onErrorResume(e -> {
            log.error("assistant tool-agent flow failed", e);
            return Flux.just("工具增强助手暂时不可用：" + e.getMessage());
        });
    }

    private OpsWorkflow resolveAwmHit(String userMessage, String conversationContext) {
        if (workflowRetriever == null) {
            return null;
        }
        String seed = AgentSkillPlan.planningSeed(userMessage, conversationContext);
        try {
            return workflowRetriever.bestMatch(AgentSkillPlan.guessDomain(seed), List.of(), seed);
        } catch (Exception e) {
            log.debug("AWM bestMatch skipped: {}", e.getMessage());
            return null;
        }
    }

    private ToolAgentCompletion runBlockingToolAugmentedCompletion(String userMessage,
                                                                   List<ChatTurn> history,
                                                                   boolean confirmRemediation,
                                                                   AssistantReplyPlanner.ReplyPlan plan,
                                                                   List<String> plannedTools,
                                                                   String planPhase,
                                                                   OpsWorkflow awmHit,
                                                                   String requestedModelProfile) {
        Map<String, Object> context = getAssistantContext(userMessage);
        augmentContextWithSessionSecurity(userMessage, context);
        context.put("assistantToolLoop", Boolean.TRUE);

        boolean allowWrite = confirmRemediation || opsIntentRouter.forceRemediate(userMessage);
        context.put("userConfirmedWrite", allowWrite);
        String effectivePlanPhase = planPhase == null || planPhase.isBlank()
                ? AgentSkillPlan.planPhase(allowWrite, plannedTools)
                : planPhase;
        context.put("planPhase", effectivePlanPhase);
        if (plannedTools != null && !plannedTools.isEmpty()) {
            context.put("plannedTools", plannedTools);
            context.put("observeTools", AgentSkillPlan.observeTools(plannedTools));
            context.put("pendingWriteTools", AgentSkillPlan.pendingWriteTools(plannedTools));
        }

        if (awmHit != null) {
            String seq = AgentSkillPlan.workflowStepSummary(awmHit);
            if (!seq.isBlank()) {
                context.put("awmPreferredSequence", seq);
                context.put("awmWorkflowId", awmHit.workflowId());
                context.put("awmWorkflowTitle", awmHit.title());
            }
        }

        McpToolSurface requestedSurface = parseToolSurface(context);
        McpToolSurface surface = AgentToolPhase.effectiveSurface(requestedSurface, allowWrite);
        boolean writeMounted = AgentToolPhase.writeToolsMounted(allowWrite, surface);
        context.put("toolSurfaceEffective", surface.name());
        context.put("writeToolsMounted", writeMounted);
        ToolCallback[] toolCallbacks = mcpToolRegistry.getToolCallbacksForChatAgent(
                requestedSurface, readOnlySurfaceDenylist, allowWrite);

        String traceId = UUID.randomUUID().toString();
        long startedAt = System.currentTimeMillis();
        ChatToolExecutionTracker.clear();
        OpsSecurityContext.openChatAgent(traceId, userMessage, surface, allowWrite);
        try {
            AiModelRouter.ResolvedModel resolvedModel = resolveModel(
                    userMessage, AiUsageSupport.estimateTokens(contextToJson(context)), true,
                    requestedModelProfile);
            int estimatedPromptTokens = AiUsageSupport.estimateTokens(
                    contextToJson(context) + bundleHistoryAndUser(history, userMessage));
            ChatResponse response = null;
            String content;
            if (chatClientBuilder == null) {
                content = runFallbackPromptOnly(userMessage, history, context, resolvedModel.chatModel());
            } else {
                ChatClient.Builder dynamicClientBuilder = ChatClient.builder(resolvedModel.chatModel())
                        .defaultAdvisors(ChatModelCallAdvisor.builder()
                                .chatModel(resolvedModel.chatModel())
                                .build());
                if (toolCallbacks.length > 0 && toolCallingManager != null) {
                    dynamicClientBuilder.defaultAdvisors(ToolCallAdvisor.builder()
                            .toolCallingManager(toolCallingManager)
                            .build());
                }
                ChatClient client = dynamicClientBuilder.build();
                String ctxJson = contextToJson(context);
                String systemPrompt = AssistantReplyPrompts.toolAgentSystemPrompt(
                        ctxJson,
                        toolAugmentedSection(context, surface),
                        allowWrite,
                        plannedTools == null ? List.of() : plannedTools,
                        writeMounted);
                String bundledUser = bundleHistoryAndUser(history, userMessage);
                var promptSpec = client.prompt()
                        .system(systemPrompt)
                        .user(bundledUser);
                if (toolCallbacks.length > 0) {
                    promptSpec = promptSpec.toolCallbacks(toolCallbacks);
                }
                var responseSpec = promptSpec.call();
                content = responseSpec.content();
                response = responseSpec.chatResponse();
            }
            Map<String, Object> contextUsage = AiUsageSupport.usage(
                    response,
                    resolvedModel.profile(),
                    resolvedModel.model(),
                    resolvedModel.contextWindow(),
                    estimatedPromptTokens,
                    AiUsageSupport.estimateTokens(content));

            List<Map<String, Object>> steps = assistantAuditRecorder.newSteps();
            assistantAuditRecorder.addCot(steps, 1, "receive", userMessage);
            assistantAuditRecorder.addCot(steps, 2, "perceive", "assistant context injected");
            assistantAuditRecorder.addCot(steps, 3, "reason",
                    writeMounted ? "tool-augmented execute phase" : "tool-augmented diagnose phase");
            LinkedHashSet<String> toolsUsed = new LinkedHashSet<>();
            for (ChatToolExecutionTracker.ToolInvocation inv : ChatToolExecutionTracker.snapshot()) {
                Map<String, Object> toolStep = new LinkedHashMap<>();
                toolStep.put("toolName", inv.toolName());
                toolStep.put("mode", inv.mode() != null ? inv.mode() : "READ");
                toolStep.put("success", inv.success());
                toolsUsed.add(inv.toolName());
                String phase = inv.success() && inv.mode() != null
                        && inv.mode().toUpperCase(Locale.ROOT).contains("DELETE")
                        ? "execute" : "preview";
                assistantAuditRecorder.addStructuredStep(steps, phase, toolStep);
            }
            assistantAuditRecorder.addCot(steps, 4, "verify",
                    "tool surface=" + surface.name() + ", writeMounted=" + writeMounted);
            assistantAuditRecorder.addStep(steps, "result", truncateForAudit(content, 600));
            String toolAgentOutcome;
            if (surface == McpToolSurface.READ_ONLY) {
                toolAgentOutcome = allowWrite ? "READ_ONLY_SURFACE" : "DIAGNOSE_READONLY_MOUNT";
            } else if (ChatToolExecutionTracker.hasSuccessfulRealWrite()) {
                toolAgentOutcome = "EXECUTED";
            } else if (ChatToolExecutionTracker.hasAnyToolInvocation()) {
                toolAgentOutcome = allowWrite ? "PREVIEW_OR_WRITE_PENDING" : "DIAGNOSED";
            } else {
                toolAgentOutcome = "NO_TOOL";
            }
            boolean auditOk = !"NO_TOOL".equals(toolAgentOutcome) || content != null;
            assistantAuditRecorder.record(
                    traceId,
                    userMessage,
                    parseIntentRisk(context).name(),
                    toolAgentOutcome,
                    "ChatClient.tools",
                    auditOk,
                    truncateForAudit(content, 400),
                    steps,
                    System.currentTimeMillis() - startedAt
            );

            String cleaned = content == null ? "" : content;
            cleaned = cleaned.replaceAll("(?m)^>\\s*\\*\\*traceId:\\*\\*\\s*`[^`]+`\\s*\\n\\n?", "");
            cleaned = polishAssistantResponse(cleaned, new AssistantReplyPlanner.ReplyPlan(
                    AssistantReplyMode.TOOL_AGENT, AssistantIntentCategory.OPS_DIAGNOSIS, true, ""));
            cleaned = WriteExecutionCoordinator.sanitizeAssistantText(cleaned, allowWrite);
            boolean realWriteOk = ChatToolExecutionTracker.hasSuccessfulRealWrite();
            List<String> planRef = plannedTools != null && !plannedTools.isEmpty()
                    ? plannedTools
                    : AgentSkillPlan.forToolAgent(userMessage);
            boolean shouldAppendPlan = !allowWrite && !realWriteOk
                    && (ChatToolExecutionTracker.hasAnyToolInvocation()
                    || AgentSkillPlan.hasWriteTools(planRef));
            if (shouldAppendPlan && !cleaned.contains("## 处置计划")) {
                cleaned = cleaned + OpsReportFormat.remediationPlanMarkdown(
                        AgentSkillPlan.remediationItems(planRef, false));
            }
            Map<String, Object> executionState = AgentExecutionState.build(
                    plan.mode().name(),
                    allowWrite,
                    writeMounted,
                    planRef,
                    effectivePlanPhase,
                    traceId,
                    toolAgentOutcome,
                    awmHit);
            executionState.put("toolsUsed", List.copyOf(toolsUsed));
            executionState.put("realWriteOk", realWriteOk);
            boolean anyTool = ChatToolExecutionTracker.hasAnyToolInvocation();
            executionState.put("anyTool", anyTool);
            executionState.put("memoryApplied", awmHit != null);
            boolean feedbackRecorded = recordAwmToolAgentFeedback(
                    awmHit,
                    traceId,
                    toolAgentOutcome,
                    List.copyOf(toolsUsed),
                    anyTool,
                    realWriteOk,
                    cleaned);
            executionState.put("feedbackRecorded", feedbackRecorded);
            return new ToolAgentCompletion(
                    appendChatAntiHallucinationFooter(cleaned, allowWrite),
                    traceId,
                    toolAgentOutcome,
                    List.copyOf(toolsUsed),
                    allowWrite,
                    realWriteOk,
                    anyTool,
                    executionState,
                    contextUsage);
        } finally {
            ChatToolExecutionTracker.clear();
            OpsSecurityContext.clear();
        }
    }

    private void recordAwmPlanningHit(OpsWorkflow awmHit) {
        if (awmHit == null || workflowRetriever == null) {
            return;
        }
        try {
            workflowRetriever.recordHit(awmHit);
        } catch (Exception e) {
            log.debug("AWM planning hit record skipped workflowId={}: {}",
                    awmHit.workflowId(), e.getMessage());
        }
    }

    private boolean recordAwmToolAgentFeedback(
            OpsWorkflow awmHit,
            String traceId,
            String outcome,
            List<String> toolsUsed,
            boolean anyTool,
            boolean realWriteOk,
            String summary
    ) {
        if (awmHit == null || workflowMemoryService == null) {
            return false;
        }
        boolean success = isAwmSuccessfulOutcome(outcome, anyTool, realWriteOk);
        int toolCount = toolsUsed == null ? 0 : toolsUsed.size();
        int stepsTotal = awmHit.steps() == null || awmHit.steps().isEmpty()
                ? Math.max(1, toolCount)
                : awmHit.steps().size();
        int stepsOk = success ? Math.min(stepsTotal, Math.max(1, toolCount)) : 0;
        try {
            workflowMemoryService.recordRun(
                    awmHit.workflowId(),
                    traceId,
                    success,
                    stepsOk,
                    stepsTotal,
                    "tool-agent outcome=" + outcome
                            + ", tools=" + (toolsUsed == null ? List.of() : toolsUsed)
                            + ", summary=" + truncateForAudit(summary, 240));
            if (success) {
                workflowMemoryService.recordSuccess(awmHit.workflowId());
            }
            return true;
        } catch (Exception e) {
            log.debug("AWM tool-agent feedback skipped workflowId={}: {}",
                    awmHit.workflowId(), e.getMessage());
            return false;
        }
    }

    private boolean recordAwmOrchestratedFeedback(
            OpsWorkflow awmHit,
            String traceId,
            String outcome,
            String summary
    ) {
        if (awmHit == null || workflowMemoryService == null) {
            return false;
        }
        boolean success = isAwmSuccessfulOutcome(
                outcome,
                true,
                "EXECUTED".equalsIgnoreCase(String.valueOf(outcome)));
        int stepsTotal = awmHit.steps() == null || awmHit.steps().isEmpty() ? 1 : awmHit.steps().size();
        try {
            workflowMemoryService.recordRun(
                    awmHit.workflowId(),
                    traceId,
                    success,
                    success ? stepsTotal : 0,
                    stepsTotal,
                    "orchestrator outcome=" + outcome
                            + ", summary=" + truncateForAudit(summary, 240));
            if (success) {
                workflowMemoryService.recordSuccess(awmHit.workflowId());
            }
            return true;
        } catch (Exception e) {
            log.debug("AWM orchestrator feedback skipped workflowId={}: {}",
                    awmHit.workflowId(), e.getMessage());
            return false;
        }
    }

    private boolean isAwmSuccessfulOutcome(String outcome, boolean anyTool, boolean realWriteOk) {
        String normalized = outcome == null ? "" : outcome.trim().toUpperCase(Locale.ROOT);
        if (realWriteOk || "EXECUTED".equals(normalized) || "REMEDIATED".equals(normalized)) {
            return true;
        }
        return anyTool && ("DIAGNOSED".equals(normalized)
                || "PREVIEW".equals(normalized)
                || "PREVIEW_OR_WRITE_PENDING".equals(normalized)
                || "HEALTHY".equals(normalized));
    }

    private String runFallbackPromptOnly(String userMessage,
                                         List<ChatTurn> history,
                                         Map<String, Object> context,
                                         ChatModel model) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(buildSystemPrompt(context)));
        for (ChatTurn turn : trimHistory(history)) {
            String content = turn.content() == null ? "" : turn.content().trim();
            String role = turn.role() == null ? "" : turn.role().trim();
            if (content.isEmpty()) {
                continue;
            }
            if ("user".equalsIgnoreCase(role)) {
                messages.add(new UserMessage(content));
            } else if ("assistant".equalsIgnoreCase(role)) {
                messages.add(new AssistantMessage(content));
            }
        }
        messages.add(new UserMessage(userMessage));
        return AiChatResponseSupport.textFrom(model.call(new Prompt(messages)));
    }

    boolean isChitchatMessage(String userMessage) {
        return assistantReplyPlanner.isChitchat(userMessage);
    }

    boolean shouldRouteToToolAgent(String userMessage, boolean useToolAgentRequested) {
        AssistantReplyPlanner.ReplyPlan plan = assistantReplyPlanner.plan(
                userMessage, List.of(), useToolAgentRequested, false, orchestratorEnabled, opsRuntimeService);
        return plan.useToolAgentPath();
    }

    private String buildSystemPromptForPlan(AssistantReplyPlanner.ReplyPlan plan, Map<String, Object> context) {
        String ctxJson = (plan.injectMetrics() || plan.injectFullContext())
                ? contextToJson(context)
                : "{}";
        return AssistantReplyPrompts.systemPrompt(plan.mode(), plan.category(), ctxJson);
    }

    private String buildSystemPromptForMode(AssistantReplyMode mode, Map<String, Object> context) {
        return buildSystemPromptForPlan(
                new AssistantReplyPlanner.ReplyPlan(mode, AssistantIntentCategory.GENERAL, false, ""),
                context);
    }

    private Map<String, Object> buildLightContext(String userMessage) {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("agentHostOs", buildAgentHostOsSummary());
        if (knowledgeBaseService != null && userMessage != null && !userMessage.isBlank()) {
            try {
                ctx.put("knowledgeHints", knowledgeBaseService.search(userMessage, Math.min(3, knowledgeSearchTopK)));
            } catch (Exception e) {
                log.debug("light context knowledge skipped: {}", e.getMessage());
            }
        }
        return ctx;
    }

    private Map<String, Object> buildOpsSummaryContext(String userMessage) {
        Map<String, Object> full = getAssistantContext(userMessage);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("agentHostOs", full.get("agentHostOs"));

        Object perf = full.get("performance");
        if (perf instanceof Map<?, ?> p) {
            summary.put("performanceSummary", Map.of(
                    "cpuUsagePercent", asDouble(p.get("cpuUsage")),
                    "memoryUsagePercent", asDouble(p.get("memoryUsage")),
                    "diskUsagePercent", asDouble(p.get("diskUsage")),
                    "networkUsagePercent", asDouble(p.get("networkUsage"))
            ));
        }

        Object alarmStats = full.get("alarmStats");
        if (alarmStats instanceof Map<?, ?> a) {
            Map<String, Object> alarmSummary = new LinkedHashMap<>();
            alarmSummary.put("totalAlarms", a.get("totalAlarms"));
            alarmSummary.put("criticalCount", a.get("criticalCount"));
            summary.put("alarmSummary", alarmSummary);
        }

        Object taskStats = full.get("taskStats");
        if (taskStats != null) {
            summary.put("taskStats", taskStats);
        }

        summary.put("runbooks", full.getOrDefault("runbooks", List.of()));
        return summary;
    }

    private String contextToJson(Map<String, Object> context) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(context);
        } catch (JsonProcessingException e) {
            log.warn("assistant context json serialization failed, fallback to string", e);
            return String.valueOf(context);
        }
    }

    /**
     * 表格/报告体开头缺少自然语言时补一句引导语。
     */
    private String polishAssistantResponse(String content, AssistantReplyPlanner.ReplyPlan plan) {
        if (content == null || content.isBlank() || plan == null) {
            return content;
        }
        AssistantReplyMode mode = plan.mode();
        AssistantIntentCategory category = plan.category();
        String trimmed = content.trim();
        if (mode == AssistantReplyMode.CHITCHAT
                || mode == AssistantReplyMode.CONVERSATION
                || category == AssistantIntentCategory.GREETING
                || category == AssistantIntentCategory.CLARIFICATION
                || category == AssistantIntentCategory.CAPABILITY_INQUIRY) {
            if (startsWithTableOrOpsReport(trimmed)) {
                return """
                        抱歉，刚才的回复不够贴切。

                        我是 **ThreshCore 运维助手**，可以帮你做日志分析、巡检诊断、磁盘清理、告警排查等。请告诉我具体想处理什么问题？
                        """;
            }
            return content;
        }
        if (startsWithTableOrOpsReport(trimmed) && !trimmed.contains("\n\n")) {
            return "根据当前环境数据，整理如下：\n\n" + trimmed;
        }
        if (trimmed.matches("(?s)^\\|[^\\n]+\\|[^\\n]*\\n\\|[-: |]+\\|.*")
                && !trimmed.matches("(?s)^[^|#\\n]{8,}.*\\|.*")) {
            return "根据当前环境数据，整理如下：\n\n" + trimmed;
        }
        return content;
    }

    private static boolean startsWithTableOrOpsReport(String trimmed) {
        return trimmed.startsWith("| 指标 |")
                || trimmed.startsWith("| 项目 |")
                || trimmed.matches("(?s)^\\|[^|]+\\|.*")
                || trimmed.startsWith("## 系统状态");
    }

    private AiModelRouter.ResolvedModel resolveModel(String userMessage,
                                                     int estimatedPromptTokens,
                                                     boolean toolAgent) {
        return resolveModel(userMessage, estimatedPromptTokens, toolAgent, null);
    }

    private AiModelRouter.ResolvedModel resolveModel(String userMessage,
                                                     int estimatedPromptTokens,
                                                     boolean toolAgent,
                                                     String requestedModelProfile) {
        if (aiModelRouter != null) {
            return aiModelRouter.resolve(userMessage, estimatedPromptTokens, toolAgent, requestedModelProfile);
        }
        return new AiModelRouter.ResolvedModel(
                chatModel,
                "default",
                configuredModelName,
                Math.max(1, configuredContextWindow),
                "DEFAULT");
    }

    private Map<String, Object> routingMeta(AiModelRouter.ResolvedModel resolvedModel) {
        Map<String, Object> meta = new LinkedHashMap<>();
        if (resolvedModel == null) return meta;
        meta.put("modelProfile", resolvedModel.profile());
        meta.put("model", resolvedModel.model());
        meta.put("modelRoutingMode", resolvedModel.routingMode());
        meta.put("contextWindow", resolvedModel.contextWindow());
        return meta;
    }

    private Flux<String> usageEvent(AiModelRouter.ResolvedModel resolvedModel,
                                    AtomicReference<ChatResponse> response,
                                    int estimatedPromptTokens,
                                    AtomicReference<String> output) {
        if (resolvedModel == null) return Flux.empty();
        return Flux.defer(() -> usageEvent(AiUsageSupport.usage(
                response.get(),
                resolvedModel.profile(),
                resolvedModel.model(),
                resolvedModel.contextWindow(),
                estimatedPromptTokens,
                AiUsageSupport.estimateTokens(output.get()))));
    }

    private Flux<String> usageEvent(Map<String, Object> usage) {
        if (usage == null || usage.isEmpty()) return Flux.empty();
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", "context-usage");
        event.put("contextUsage", usage);
        String encoded = AssistantStreamEvents.encode(event);
        return encoded == null ? Flux.empty() : Flux.just(encoded);
    }

    private Flux<String> prependAssistantMeta(Flux<String> body, String userMessage) {
        return prependAssistantMeta(body, userMessage, Map.of(), null);
    }

    private Flux<String> prependAssistantMeta(Flux<String> body,
                                              String userMessage,
                                              AssistantReplyPlanner.ReplyPlan plan) {
        return prependAssistantMeta(body, userMessage, Map.of(), plan);
    }

    private Flux<String> prependAssistantMeta(Flux<String> body,
                                              String userMessage,
                                              Map<String, Object> extras,
                                              AssistantReplyPlanner.ReplyPlan plan) {
        try {
            Map<String, Object> merged = new LinkedHashMap<>(extras != null ? extras : Map.of());
            if (plan != null) {
                merged.put("replyMode", plan.mode().name());
                merged.put("intentCategory", plan.category().name());
            }
            String json = objectMapper.writeValueAsString(buildChatMeta(userMessage, merged));
            return Flux.concat(Flux.just(ASSISTANT_META_PREFIX + json), body);
        } catch (JsonProcessingException e) {
            log.debug("assistant meta serialization failed: {}", e.getMessage());
            return body;
        }
    }

    private Map<String, String> buildAgentHostOsSummary() {
        Map<String, String> out = new HashMap<>();
        String osName = System.getProperty("os.name", "");
        out.put("osName", osName);
        out.put("osVersion", System.getProperty("os.version", ""));
        out.put("osArch", System.getProperty("os.arch", ""));
        String lower = osName.toLowerCase();
        if (lower.contains("win")) {
            out.put("osFamily", "windows");
        } else if (lower.contains("linux") || lower.contains("nix")) {
            out.put("osFamily", "linux");
        } else if (lower.contains("mac")) {
            out.put("osFamily", "macos");
        } else {
            out.put("osFamily", "unknown");
        }
        return out;
    }

    private void augmentContextWithSessionSecurity(String userMessage, Map<String, Object> context) {
        try {
            RiskLevel riskLevel = intentRiskFilter.evaluate(userMessage);
            McpToolSurface surface = toolSurfaceResolver.resolve(userMessage, riskLevel);
            AgenticRiskScoreEngine.ScoreResult utterScore =
                    agenticRiskScoreEngine.scoreNaturalLanguageUtterance(userMessage);

            Map<String, Object> sessionSecurity = new LinkedHashMap<>();
            sessionSecurity.put("intentRisk", riskLevel.name());
            sessionSecurity.put("toolSurface", surface.name());
            sessionSecurity.put("utteranceRiskScore", Map.of(
                    "total", utterScore.total(),
                    "dimensions", utterScore.dimensions(),
                    "explanation", utterScore.explanation()
            ));
            context.put("sessionSecurityPolicy", sessionSecurity);
        } catch (Exception e) {
            log.debug("assistant session security context skipped: {}", e.getMessage());
        }
    }

    private String buildSystemPrompt(Map<String, Object> context) {
        return AssistantReplyPrompts.toolAgentSystemPrompt(contextToJson(context), toolAugmentedSection(context));
    }

    private List<Map<String, String>> buildRunbookSuggestions(Map<String, Object> performance,
                                                              Map<String, Object> alarmStats) {
        List<Map<String, String>> runbooks = new ArrayList<>();
        double cpu = asDouble(performance.get("cpuUsage"));
        double memory = asDouble(performance.get("memoryUsage"));
        double disk = asDouble(performance.get("diskUsage"));
        double critical = asDouble(alarmStats.get("criticalCount"));

        if (disk >= 80) {
            runbooks.add(runbook(
                    "Disk pressure",
                    "Inspect hotspots and preview cleanup",
                    "Use `DiskAnalyzeTool` first, then preview `CleanTempTool` or `LogCleanupTool`."
            ));
        }
        if (cpu >= 80) {
            runbooks.add(runbook(
                    "CPU pressure",
                    "Locate hot processes and verify load trend",
                    "Use `SystemLoadTool` and `ProcessTool` to identify the hottest processes."
            ));
        }
        if (memory >= 85) {
            runbooks.add(runbook(
                    "Memory pressure",
                    "Inspect top memory consumers",
                    "Use `ProcessTool` and verify whether a restart candidate is allowlisted."
            ));
        }
        if (critical > 0) {
            runbooks.add(runbook(
                    "Critical alarms",
                    "Triage the most critical service impact first",
                    "Check recent alarms, then use patrol automation for correlated diagnosis."
            ));
        }
        if (runbooks.isEmpty()) {
            runbooks.add(runbook(
                    "Routine patrol",
                    "Current metrics do not indicate an urgent issue",
                    "Continue observation and run patrol automation when a broader host check is needed."
            ));
        }
        return runbooks;
    }

    private String toolAugmentedSection(Map<String, Object> context) {
        return toolAugmentedSection(context, McpToolSurface.FULL);
    }

    private String toolAugmentedSection(Map<String, Object> context, McpToolSurface surface) {
        if (!Boolean.TRUE.equals(context.get("assistantToolLoop"))) {
            return "";
        }
        String catalog = mcpToolCatalog != null
                ? mcpToolCatalog.summarizeToolsForPrompt(toolCatalogPromptMax)
                : "";
        return AssistantReplyPrompts.toolAugmentedSection()
                + AssistantReplyPrompts.availableToolsSection(catalog)
                + """

                ## 工具增强本轮
                - 先通过工具采集事实，再组织中文答复。
                - 写操作默认预览；用户未明确确认前不得描述为已执行。
                - 工具失败或未调用时，说明实时数据不可用，不要编造指标。
                - 当前工具面：**"""
                + (surface == null ? "FULL" : surface.name())
                + "**；可多步连续调用直至任务完成。\n";
    }

    private String appendChatAntiHallucinationFooter(String content, boolean writeConfirmed) {
        return appendChatAntiHallucinationFooter(content, writeConfirmed, false);
    }

    private String appendChatAntiHallucinationFooter(String content, boolean writeConfirmed, boolean forceRealWrite) {
        if (content == null || content.isBlank() || content.contains("Data Basis") || content.contains("数据依据")) {
            return content;
        }
        boolean anyTool = ChatToolExecutionTracker.hasAnyToolInvocation() || forceRealWrite;
        boolean realWrite = ChatToolExecutionTracker.hasSuccessfulRealWrite() || forceRealWrite;
        return content + OpsReportFormat.dataBasisFooter(writeConfirmed, anyTool, realWrite);
    }

    private String appendChatAntiHallucinationFooter(String content) {
        return appendChatAntiHallucinationFooter(content, false);
    }

    private void captureStreamReject(String userMessage, String code, String toolName, String detail) {
        if (failureInsightService != null) {
            failureInsightService.captureReject(userMessage, code, toolName, detail, null);
        }
    }

    private McpToolSurface parseToolSurface(Map<String, Object> context) {
        Object raw = context.get("sessionSecurityPolicy");
        if (!(raw instanceof Map<?, ?> map)) {
            return McpToolSurface.FULL;
        }
        Object surface = map.get("toolSurface");
        if (surface == null) {
            return McpToolSurface.FULL;
        }
        try {
            return McpToolSurface.valueOf(String.valueOf(surface).trim());
        } catch (Exception e) {
            return McpToolSurface.FULL;
        }
    }

    private RiskLevel parseIntentRisk(Map<String, Object> context) {
        Object raw = context.get("sessionSecurityPolicy");
        if (!(raw instanceof Map<?, ?> map)) {
            return RiskLevel.MEDIUM;
        }
        Object risk = map.get("intentRisk");
        if (risk == null) {
            return RiskLevel.MEDIUM;
        }
        try {
            return RiskLevel.valueOf(String.valueOf(risk).trim());
        } catch (Exception e) {
            return RiskLevel.MEDIUM;
        }
    }

    private String bundleHistoryAndUser(List<ChatTurn> history, String userMessage) {
        StringBuilder sb = new StringBuilder();
        for (ChatTurn turn : trimHistory(history)) {
            String role = turn.role() == null ? "" : turn.role().trim();
            String content = turn.content() == null ? "" : turn.content().trim();
            if (content.isEmpty()) {
                continue;
            }
            sb.append("[").append(role).append("]\n").append(content).append("\n\n");
        }
        sb.append("[user]\n").append(userMessage);
        return sb.toString();
    }

    private List<ChatTurn> trimHistory(List<ChatTurn> history) {
        if (history == null || history.isEmpty()) {
            return List.of();
        }
        int start = Math.max(0, history.size() - MAX_HISTORY_TURNS);
        List<ChatTurn> slice = history.subList(start, history.size());
        List<ChatTurn> out = new ArrayList<>(slice.size());
        for (ChatTurn turn : slice) {
            String content = turn.content() == null ? "" : turn.content();
            if (content.length() > MAX_TURN_CHARS) {
                content = content.substring(0, MAX_TURN_CHARS) + "\n...[truncated]";
            }
            out.add(new ChatTurn(turn.role(), content));
        }
        return out;
    }

    private Flux<String> chunkTextForSse(String text) {
        if (text == null || text.isEmpty()) {
            return Flux.empty();
        }
        int step = Math.max(200, toolAgentStreamChunkChars);
        if (text.length() <= step) {
            return Flux.just(text);
        }
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < text.length(); i += step) {
            parts.add(text.substring(i, Math.min(text.length(), i + step)));
        }
        return Flux.fromIterable(parts);
    }

    /** 结构化进度事件：前端展示「处理进度」，不污染正文 Markdown。 */
    private static Flux<String> progressEventFlux(String phase, String title) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", "progress");
        event.put("phase", phase);
        event.put("title", title == null ? "" : title);
        String encoded = AssistantStreamEvents.encode(event);
        return encoded == null ? Flux.empty() : Flux.just(encoded);
    }

    /** Plan-then-Act：执行前推送计划工具列表，供前端展示。 */
    private static Flux<String> toolPlanEventFlux(List<String> plannedTools,
                                                  String mode,
                                                  boolean writeConfirmed,
                                                  String planPhase,
                                                  Map<String, Object> executionState) {
        if (plannedTools == null || plannedTools.isEmpty()) {
            return Flux.empty();
        }
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", "tool-plan");
        event.put("phase", "plan");
        event.put("mode", mode == null ? "" : mode);
        event.put("planPhase", planPhase == null ? "" : planPhase);
        event.put("tools", plannedTools);
        event.put("observeTools", AgentSkillPlan.observeTools(plannedTools));
        event.put("pendingWriteTools", AgentSkillPlan.pendingWriteTools(plannedTools));
        event.put("items", AgentSkillPlan.remediationItems(plannedTools, writeConfirmed));
        event.put("planKind", AgentSkillPlan.hasWriteTools(plannedTools) ? "REMEDIATION" : "DIAGNOSIS");
        String phaseLabel = "EXECUTE".equals(planPhase) ? "确认执行计划" : "诊断计划";
        event.put("title", writeConfirmed ? phaseLabel + "（已确认）" : phaseLabel + "（待执行）");
        event.put("writeConfirmed", writeConfirmed);
        event.put("awaitingConfirm", !writeConfirmed && AgentSkillPlan.hasWriteTools(plannedTools));
        event.put("writeToolsMounted", writeConfirmed);
        if (executionState != null && !executionState.isEmpty()) {
            event.put("executionState", executionState);
        }
        String encoded = AssistantStreamEvents.encode(event);
        return encoded == null ? Flux.empty() : Flux.just(encoded);
    }

    private static Flux<String> toolAgentResultEventFlux(ToolAgentCompletion completion) {
        if (completion == null) {
            return Flux.empty();
        }
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", "tool-agent-result");
        event.put("phase", "verify");
        event.put("replyMode", "TOOL_AGENT");
        event.put("traceId", completion.traceId());
        event.put("securityOutcome", completion.securityOutcome());
        event.put("toolsUsed", completion.toolsUsed());
        event.put("writeConfirmed", completion.writeConfirmed());
        event.put("writeToolsMounted", completion.writeConfirmed());
        event.put("realWriteOk", completion.realWriteOk());
        event.put("anyTool", completion.anyTool());
        if (completion.executionState() != null && !completion.executionState().isEmpty()) {
            event.put("executionState", completion.executionState());
            Object awmWorkflowId = completion.executionState().get("awmWorkflowId");
            Object awmWorkflowTitle = completion.executionState().get("awmWorkflowTitle");
            if (awmWorkflowId != null) {
                event.put("awmWorkflowId", awmWorkflowId);
            }
            if (awmWorkflowTitle != null) {
                event.put("awmWorkflowTitle", awmWorkflowTitle);
            }
        }
        String encoded = AssistantStreamEvents.encode(event);
        return encoded == null ? Flux.empty() : Flux.just(encoded);
    }

    private static String toStreamingDelta(AtomicReference<String> accumulatedFull, String piece) {
        if (piece == null || piece.isEmpty()) {
            return "";
        }
        String prev = accumulatedFull.get();
        if (prev == null) {
            prev = "";
        }
        if (piece.startsWith(prev)) {
            String delta = piece.substring(prev.length());
            accumulatedFull.set(piece);
            return delta;
        }
        accumulatedFull.set(prev + piece);
        return piece;
    }

    private static String truncateForAudit(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max) + "...";
    }

    private Map<String, String> runbook(String title, String action, String command) {
        Map<String, String> item = new LinkedHashMap<>();
        item.put("title", title);
        item.put("action", action);
        item.put("command", command);
        return item;
    }

    private static double asDouble(Object value) {
        return value instanceof Number n ? n.doubleValue() : 0.0;
    }
}
