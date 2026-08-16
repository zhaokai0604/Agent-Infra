package com.award.log.agent;

import com.award.log.util.AiChatResponseSupport;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 规则未命中时的语义 Playbook 兜底：LLM 只输出标签与置信度，
 * 不直接执行工具；写操作仍过安全门与二次确认。
 */
@Slf4j
@Component
public class LlmPlaybookClassifier {

    private static final Pattern JSON_FENCE = Pattern.compile("```(?:json)?\\s*([\\s\\S]*?)```", Pattern.CASE_INSENSITIVE);

    private static final String SYSTEM = """
            你是运维助手的意图分类器。根据用户话术（可含简短对话上下文）选择唯一 playbook。
            只输出 JSON 对象，不要解释、不要 markdown。

            playbook 枚举（大小写不敏感）：
            - DISK_CLEANUP：磁盘空间不足、清理临时/日志、扫描盘占用、释放空间
            - CPU_PRESSURE：CPU/负载高、卡顿、排查高占用进程（非磁盘主诉）
            - PATROL_AUTOMATION：全面体检、一键巡检、系统健康检查、服务失败排查
            - PATROL_CONTINUATION：继续处理已有巡检待办/确认方案
            - NONE：寒暄、纯知识问答、日志分析闲聊、无法判断、或明确要破坏系统/删库/关防火墙等非法意图

            规则：
            1. 有歧义或置信不足时选 NONE（勿强行归类）。
            2. 同时提 CPU 与磁盘且磁盘是主诉 → DISK_CLEANUP。
            3. 破坏性/恶意改写（删整个系统、格式化、rm -rf /）→ NONE。
            4. confidence 为 0~1 小数。

            JSON schema:
            {"playbook":"DISK_CLEANUP|CPU_PRESSURE|PATROL_AUTOMATION|PATROL_CONTINUATION|NONE","confidence":0.0,"reason":"一句中文"}
            """;

    private final ObjectMapper objectMapper;
    private ChatModel chatModel;

    @Value("${agent.intent.llm-routing-enabled:true}")
    private boolean enabled;

    @Value("${agent.intent.llm-routing-min-confidence:0.62}")
    private double minConfidence;

    @Value("${agent.intent.llm-routing-timeout-ms:4500}")
    private long timeoutMs;

    public LlmPlaybookClassifier(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Autowired(required = false)
    public void setChatModel(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    public boolean isAvailable() {
        return enabled && chatModel != null;
    }

    /**
     * @param userMessage 当前用户输入
     * @param contextHint 可选近期对话摘要（可空）
     */
    public Optional<OpsIntentRouter.Playbook> classify(String userMessage, String contextHint) {
        if (!isAvailable() || userMessage == null || userMessage.isBlank()) {
            return Optional.empty();
        }
        String trimmed = userMessage.trim();
        if (trimmed.length() > 1200) {
            trimmed = trimmed.substring(0, 1200);
        }
        final String promptUser = buildUserPrompt(trimmed, contextHint);
        try {
            CompletableFuture<Optional<OpsIntentRouter.Playbook>> fut = CompletableFuture.supplyAsync(() -> {
                try {
                    var response = chatModel.call(new Prompt(List.of(
                            new SystemMessage(SYSTEM),
                            new UserMessage(promptUser))));
                    String raw = AiChatResponseSupport.textFrom(response);
                    return parsePlaybook(raw);
                } catch (Exception e) {
                    log.debug("LLM playbook 调用失败: {}", e.getMessage());
                    return Optional.empty();
                }
            });
            return fut.get(Math.max(800, timeoutMs), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.info("LLM playbook 分类超时 ({}ms)，回退 NONE", timeoutMs);
            return Optional.empty();
        } catch (Exception e) {
            log.debug("LLM playbook 分类异常: {}", e.getMessage());
            return Optional.empty();
        }
    }

    Optional<OpsIntentRouter.Playbook> parsePlaybook(String llmText) {
        String json = extractJsonBlock(llmText);
        if (json.isBlank()) {
            return Optional.empty();
        }
        try {
            Map<String, Object> root = objectMapper.readValue(json, new TypeReference<>() {
            });
            String label = str(root.get("playbook")).toUpperCase(Locale.ROOT);
            double confidence = toDouble(root.get("confidence"), 0);
            if (confidence < minConfidence) {
                log.debug("LLM playbook 置信不足 conf={} min={} label={}", confidence, minConfidence, label);
                return Optional.empty();
            }
            OpsIntentRouter.Playbook pb = switch (label) {
                case "DISK_CLEANUP" -> OpsIntentRouter.Playbook.DISK_CLEANUP;
                case "CPU_PRESSURE" -> OpsIntentRouter.Playbook.CPU_PRESSURE;
                case "PATROL_AUTOMATION" -> OpsIntentRouter.Playbook.PATROL_AUTOMATION;
                case "PATROL_CONTINUATION" -> OpsIntentRouter.Playbook.PATROL_CONTINUATION;
                case "NONE", "" -> OpsIntentRouter.Playbook.NONE;
                default -> null;
            };
            if (pb == null || pb == OpsIntentRouter.Playbook.NONE) {
                return Optional.empty();
            }
            log.info("LLM playbook 兜底命中={} conf={} reason={}", pb, confidence, str(root.get("reason")));
            return Optional.of(pb);
        } catch (Exception e) {
            log.debug("解析 LLM playbook JSON 失败: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private static String buildUserPrompt(String userMessage, String contextHint) {
        StringBuilder sb = new StringBuilder();
        if (contextHint != null && !contextHint.isBlank()) {
            sb.append("近期对话上下文：\n").append(contextHint.trim()).append("\n\n");
        }
        sb.append("当前用户消息：\n").append(userMessage);
        return sb.toString();
    }

    static String extractJsonBlock(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        Matcher m = JSON_FENCE.matcher(text);
        if (m.find()) {
            return m.group(1).trim();
        }
        String t = text.trim();
        int start = t.indexOf('{');
        int end = t.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return t.substring(start, end + 1);
        }
        return t;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    private static double toDouble(Object o, double def) {
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        if (o == null) {
            return def;
        }
        try {
            return Double.parseDouble(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    // package-visible for tests
    void setEnabledForTest(boolean enabled) {
        this.enabled = enabled;
    }

    void setMinConfidenceForTest(double minConfidence) {
        this.minConfidence = minConfidence;
    }
}
