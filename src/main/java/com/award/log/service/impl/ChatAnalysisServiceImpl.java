package com.award.log.service.impl;

import com.award.log.agent.OpsReportFormat;
import com.award.log.agent.awm.FailureInsightService;
import com.award.log.security.IntentRiskFilter;
import com.award.log.security.McpInvocationSecurityGate;
import com.award.log.security.PromptInjectionGuard;
import com.award.log.security.RiskLevel;
import com.award.log.service.*;
import com.award.log.util.AiChatResponseSupport;
import com.award.log.service.ChatMemoryService.MemoryEntry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class ChatAnalysisServiceImpl implements ChatAnalysisService {

    @Autowired
    private IntentRecognitionService intentRecognitionService;

    @Autowired
    private ToolRegistryService toolRegistry;

    @Autowired
    private ChatMemoryService memoryService;

    @Autowired
    private ChatModel chatModel;

    @Autowired(required = false)
    private FailureInsightService failureInsightService;

    @Autowired(required = false)
    private PromptInjectionGuard promptInjectionGuard;

    @Autowired(required = false)
    private IntentRiskFilter intentRiskFilter;

    @Autowired
    private ChatSessionRetention sessionRetention;

    private final Map<String, List<ChatMessage>> conversationHistories = new ConcurrentHashMap<>();
    private static final int MAX_HISTORY_SIZE = 50;

    private static final Pattern TOOL_CALL_PATTERN = Pattern.compile(
            "【([a-z_]+)】\\s*\\{\\s*([^}]*?)\\s*\\}",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );

    static String requireSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId required");
        }
        return sessionId.trim();
    }

    @Override
    public Flux<String> chat(String sessionId, String userMessage) {
        final String sid = requireSessionId(sessionId);
        sessionRetention.touch(sid);
        log.info("收到聊天消息 session={}: [{}]", sid, userMessage);
        String safe = McpInvocationSecurityGate.enforceChatMessageLimit(
                userMessage == null ? "" : userMessage.trim(), 16_000);
        if (safe.isBlank()) {
            return Flux.just("请输入有效问题。");
        }
        if (promptInjectionGuard != null && promptInjectionGuard.isInjection(safe)) {
            if (failureInsightService != null) {
                failureInsightService.captureReject(safe, "INJECTION", null, "chat analysis inject", null);
            }
            return Flux.just("检测到提示注入特征，已拦截。请直接描述日志分析需求。");
        }
        if (intentRiskFilter != null && intentRiskFilter.evaluate(safe) == RiskLevel.HIGH) {
            if (failureInsightService != null) {
                failureInsightService.captureReject(safe, "HIGH_INTENT", null, "chat analysis high intent", null);
            }
            return Flux.just("检测到高风险意图，日志分析助手无法执行系统变更类操作。");
        }

        try {
            IntentRecognitionService.RecognitionResult intent = intentRecognitionService.recognize(safe);

            String memoryContext = buildMemoryContext(sid, userMessage);
            String analysisContext;

            if (shouldCallTool(intent, safe)) {
                String toolResult = executeToolWithMemory(sid, intent, safe);
                if (toolResult != null) {
                    analysisContext = "\n\n工具执行结果：\n" + toolResult;
                } else {
                    analysisContext = "\n\n【无工具结果】请基于用户问题给出分析框架，禁止编造具体日志行或监控数值。";
                }
            } else {
                analysisContext = "\n\n【未调用工具】请勿陈述未经验证的具体日志内容或指标。";
            }

            String aiResponse = generateAiResponse(sid, safe, intent, analysisContext, memoryContext);

            addToHistory(sid, new ChatMessage("user", userMessage, intent.getIntent().name()));
            addToHistory(sid, new ChatMessage("assistant", aiResponse));

            memoryService.addToMemory(sid, new MemoryEntry("user", userMessage));
            memoryService.addToMemory(sid, new MemoryEntry("assistant", aiResponse));

            return Flux.just(aiResponse);

        } catch (Exception e) {
            log.error("处理聊天消息失败 session={}", sid, e);
            return Flux.just("抱歉，处理您的请求时出现了错误：" + e.getMessage());
        }
    }

    private String buildMemoryContext(String sessionId, String userMessage) {
        StringBuilder context = new StringBuilder();
        List<MemoryEntry> recentMemory = memoryService.getRecentMemory(sessionId, 6);
        if (!recentMemory.isEmpty()) {
            context.append("\n\n【对话记忆摘要】\n");

            ChatMemoryService.MemorySummary summary = memoryService.getMemorySummary(sessionId);
            if (summary.getLastIntent() != null) {
                context.append(String.format("- 最近意图：%s%n", summary.getLastIntent()));
            }
            if (summary.getAnalysisCount() > 0) {
                context.append(String.format("- 已完成%d次分析%n", summary.getAnalysisCount()));
            }

            context.append("\n最近对话：\n");
            for (int i = Math.max(0, recentMemory.size() - 4); i < recentMemory.size(); i++) {
                MemoryEntry entry = recentMemory.get(i);
                String preview = entry.getContent().length() > 100
                        ? entry.getContent().substring(0, 100) + "..."
                        : entry.getContent();
                context.append(String.format("- %s: %s%n", entry.getRole(), preview));
            }
        }
        if (failureInsightService != null) {
            context.append(failureInsightService.buildPromptSection(userMessage));
        }
        return context.toString();
    }

    private boolean shouldCallTool(IntentRecognitionService.RecognitionResult intent, String userMessage) {
        IntentRecognitionService.Intent intentType = intent.getIntent();

        if (intentType == IntentRecognitionService.Intent.QUERY_ANOMALIES
                || intentType == IntentRecognitionService.Intent.QUERY_ERRORS
                || intentType == IntentRecognitionService.Intent.QUERY_BY_TIME
                || intentType == IntentRecognitionService.Intent.DIAGNOSE_ISSUE
                || intentType == IntentRecognitionService.Intent.GENERATE_REPORT
                || intentType == IntentRecognitionService.Intent.STATISTICS) {
            return true;
        }

        String lowerMsg = userMessage.toLowerCase();
        return lowerMsg.contains("查询") || lowerMsg.contains("诊断")
                || lowerMsg.contains("报告") || lowerMsg.contains("分析")
                || lowerMsg.contains("统计") || lowerMsg.contains("最近")
                || lowerMsg.contains("错误") || lowerMsg.contains("异常");
    }

    private String executeToolWithMemory(String sessionId,
                                         IntentRecognitionService.RecognitionResult intent,
                                         String userMessage) {
        try {
            IntentRecognitionService.Intent intentType = intent.getIntent();
            Map<String, Object> params = extractParameters(intent, userMessage);

            AiTool tool = null;
            String toolName = null;

            switch (intentType) {
                case QUERY_ANOMALIES:
                case QUERY_ERRORS:
                case QUERY_BY_TIME:
                case STATISTICS:
                    tool = toolRegistry.getTool("query_logs");
                    toolName = "query_logs";
                    break;
                case DIAGNOSE_ISSUE:
                    tool = toolRegistry.getTool("diagnose_system");
                    toolName = "diagnose_system";
                    break;
                case GENERATE_REPORT:
                    tool = toolRegistry.getTool("generate_report");
                    toolName = "generate_report";
                    break;
                default:
                    break;
            }

            if (tool == null) {
                Matcher matcher = TOOL_CALL_PATTERN.matcher(userMessage);
                if (matcher.find()) {
                    toolName = matcher.group(1);
                    tool = toolRegistry.getTool(toolName);
                }
            }

            if (tool != null) {
                AiTool.ToolResult result = tool.execute(params);
                if (result.isSuccess()) {
                    MemoryEntry memoryEntry = new MemoryEntry("tool", result.getContent());
                    memoryEntry.setIntent(intentType.name());
                    memoryEntry.setToolUsed(toolName);
                    memoryEntry.setAnalysis(true);
                    memoryService.addToMemory(sessionId, memoryEntry);
                    return result.getContent();
                }
                return "工具执行失败：" + result.getError();
            }

        } catch (Exception e) {
            log.error("工具执行失败 session={}", sessionId, e);
            return null;
        }
        return null;
    }

    private Map<String, Object> extractParameters(IntentRecognitionService.RecognitionResult intent, String userMessage) {
        Map<String, Object> params = new HashMap<>();

        if (intent.getTimeRange() != null) {
            params.put("timeRange", intent.getTimeRange().getDuration());
        }

        if (intent.getKeywords() != null && !intent.getKeywords().isEmpty()) {
            params.put("keywords", String.join(" ", intent.getKeywords()));
        }

        if (intent.getTargetService() != null) {
            params.put("service", intent.getTargetService());
        }

        String lowerMsg = userMessage.toLowerCase();
        if (lowerMsg.contains("fatal") || intent.getKeywords().contains("FATAL")) {
            params.put("severity", "FATAL");
        } else if (lowerMsg.contains("error") || intent.getKeywords().contains("ERROR")) {
            params.put("severity", "ERROR");
        } else if (lowerMsg.contains("warning") || intent.getKeywords().contains("WARNING")) {
            params.put("severity", "WARNING");
        }

        return params;
    }

    private String generateAiResponse(String sessionId,
                                     String userMessage,
                                     IntentRecognitionService.RecognitionResult intent,
                                     String analysisContext,
                                     String memoryContext) {
        String systemPrompt = buildEnhancedSystemPrompt();

        String userPrompt;
        if (analysisContext != null && !analysisContext.isEmpty()) {
            userPrompt = "用户问题：「" + userMessage + "」" + memoryContext + "\n\n" +
                        "工具执行结果：\n" + analysisContext + "\n\n" +
                        "请基于上述工具执行结果和专业背景知识，用专业但友好的语气回答用户的问题。\n" +
                        "如果工具结果中包含日志详情，请引用关键信息并给出分析。\n" +
                        "严格遵循系统提示中的 Markdown 输出格式规范.";
        } else {
            userPrompt = "用户问题：「" + userMessage + "」" + memoryContext + "\n\n" +
                        "请根据你的专业知识，帮助用户解答问题。\n" +
                        "严格遵循系统提示中的 Markdown 输出格式规范.";
        }

        try {
            Message systemMessage = new SystemMessage(systemPrompt);
            Message userMessageObj = new UserMessage(userPrompt);

            List<Message> messages = new ArrayList<>();
            messages.add(systemMessage);
            messages.addAll(getConversationHistoryMessages(sessionId));
            messages.add(userMessageObj);

            Prompt prompt = new Prompt(messages);

            String response = CompletableFuture.supplyAsync(() -> chatModel.call(prompt))
                    .orTimeout(60, TimeUnit.SECONDS)
                    .thenApply(AiChatResponseSupport::textFrom)
                    .exceptionally(ex -> {
                        log.warn("AI调用超时或失败，进入降级: {}", ex.getMessage());
                        return null;
                    })
                    .join();
            return response != null ? response : "抱歉，AI暂时无法回答这个问题。";
        } catch (Exception e) {
            log.error("AI调用失败 session={}", sessionId, e);
            if (analysisContext != null && !analysisContext.isEmpty()) {
                return buildDirectResponse(intent, analysisContext);
            }
            return "抱歉，AI服务暂时不可用。但我可以帮你使用日志查询工具来分析你的问题。";
        }
    }

    private List<Message> getConversationHistoryMessages(String sessionId) {
        List<ChatMessage> history = conversationHistories.getOrDefault(sessionId, List.of());
        List<Message> messages = new ArrayList<>();

        int startIndex = Math.max(0, history.size() - 10);
        for (ChatMessage msg : history.subList(startIndex, history.size())) {
            if ("user".equals(msg.getRole())) {
                messages.add(new UserMessage(msg.getContent()));
            } else {
                messages.add(new AssistantMessage(msg.getContent()));
            }
        }

        return messages;
    }

    private String buildEnhancedSystemPrompt() {
        StringBuilder prompt = new StringBuilder();
        prompt.append("## 角色定义\n");
        prompt.append("你是 **日志情报分析官（Log Intelligence Analyst）**，任职于企业 SOC/NOC 与 SRE 的交叉职能：熟练 syslog、结构化日志、分布式链路语义；能将原始日志事件映射到 **服务等级目标（SLO）** 与 **错误预算（Error Budget）** 语境。\n\n");

        prompt.append("## 分析方法论（须内化）\n");
        prompt.append("1. **分诊（Triage）**：按 severity / facility / 时间窗口聚合；标注是否与已知变更窗口重叠。\n");
        prompt.append("2. **关联（Correlation）**：区分 **相关性** 与 **因果性**；对每条推断给出置信度（高/中/低）及所需佐证。\n");
        prompt.append("3. **根因假设（RCA Hypothesis）**：至少给出 competing hypotheses，按 Occam 剃刀与观测强度排序。\n");
        prompt.append("4. **处置分层**：即时止血 → 短期修复 → 长期防护（监控、告警阈值、自动化）。\n\n");

        prompt.append("## 对话准则\n");
        prompt.append("- 术语精确；必要时附 **一行** 通俗释义，避免冗长科普。\n");
        prompt.append("- **Information-gap handling**：缺字段时列出 **Minimum Log Attributes** 清单请用户补充，禁止臆造 IP/TraceId/版本号。\n");
        prompt.append("- **时间与数量**：禁止编造日志中未出现的时刻、条数或时段；须直接引用用户粘贴日志里的时间戳与异常原文；多时间点问题分开叙述，勿混为一谈；不确定根因时写「可能原因」，禁止虚构「集中爆发」。\n");
        prompt.append("- **回答范围**：结论与建议只能围绕用户提供的日志/上下文；禁止引用、编造或与当前片段无关的外部事件、系统或数据。\n");
        prompt.append("- 对 FATAL / ERROR / CRITICAL：默认提示 **上报时效与升级路径**（叙述层面）。\n\n");

        prompt.append(OpsReportFormat.markdownOutputSpecForPrompt()).append("\n");
        prompt.append("- 日志片段置于 fenced code block 并注明来源组件.\n\n");

        if (toolRegistry.getToolCount() > 0) {
            prompt.append("## Registered Tools（JSON Schema 摘要）\n");
            prompt.append(toolRegistry.getToolsSchema()).append("\n");
        }

        return prompt.toString();
    }

    private String buildDirectResponse(IntentRecognitionService.RecognitionResult intent, String analysisContext) {
        StringBuilder response = new StringBuilder();
        IntentRecognitionService.Intent intentType = intent.getIntent();

        switch (intentType) {
            case QUERY_ANOMALIES:
                response.append("根据您的查询，我已经找到了一些异常日志：\n\n");
                break;
            case QUERY_ERRORS:
                response.append("以下是您查询的错误日志：\n\n");
                break;
            case DIAGNOSE_ISSUE:
                response.append("我已经完成了系统诊断分析：\n\n");
                break;
            case GENERATE_REPORT:
                response.append("这是您请求的分析报告：\n\n");
                break;
            case STATISTICS:
                response.append("统计结果如下：\n\n");
                break;
            default:
                response.append("分析结果：\n\n");
        }

        response.append(analysisContext);

        return response.toString();
    }

    private void addToHistory(String sessionId, ChatMessage message) {
        List<ChatMessage> history = conversationHistories.computeIfAbsent(sessionId, k -> new CopyOnWriteArrayList<>());
        history.add(message);
        while (history.size() > MAX_HISTORY_SIZE) {
            history.remove(0);
        }
    }

    @Override
    public AnalysisResult analyze(String userMessage) {
        AnalysisResult result = new AnalysisResult();
        result.setSummary("该方法已废弃，请使用 chat() 方法进行对话式分析");
        return result;
    }

    @Override
    public Flux<String> analyzeStream(String sessionId, String userMessage) {
        return chat(sessionId, userMessage);
    }

    @Override
    public List<ChatMessage> getConversationHistory(String sessionId) {
        return List.copyOf(conversationHistories.getOrDefault(requireSessionId(sessionId), List.of()));
    }

    @Override
    public void clearHistory(String sessionId) {
        String sid = requireSessionId(sessionId);
        conversationHistories.remove(sid);
        memoryService.clearMemory(sid);
    }

    @Scheduled(fixedDelay = 600_000, initialDelay = 240_000)
    public void evictStaleConversationHistories() {
        sessionRetention.evictExpired(sessionId -> {
            conversationHistories.remove(sessionId);
            memoryService.clearMemory(sessionId);
        });
    }
}
