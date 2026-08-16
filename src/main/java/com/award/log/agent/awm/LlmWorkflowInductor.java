package com.award.log.agent.awm;

import com.award.log.util.AiChatResponseSupport;
import com.award.log.util.TraceIdGenerator;
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * AWM 论文 §B：用 LLM 从成功轨迹归纳可复用 workflow（参数抽象为占位符）。
 * 失败时由 {@link WorkflowInductionService} 回退 rule-based。
 */
@Slf4j
@Component
public class LlmWorkflowInductor {

    private static final Pattern JSON_FENCE = Pattern.compile("```(?:json)?\\s*([\\s\\S]*?)```", Pattern.CASE_INSENSITIVE);

    private final ObjectMapper objectMapper;
    private final TraceIdGenerator traceIdGenerator;
    private ChatModel chatModel;

    @Value("${agent.awm.llm-induction-enabled:false}")
    private boolean enabled;

    public LlmWorkflowInductor(ObjectMapper objectMapper, TraceIdGenerator traceIdGenerator) {
        this.objectMapper = objectMapper;
        this.traceIdGenerator = traceIdGenerator;
    }

    @Autowired(required = false)
    public void setChatModel(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    public boolean isAvailable() {
        return enabled && chatModel != null;
    }

    public Optional<OpsWorkflow> induce(OpsExperience exp, String domainTag, List<String> findingKinds) {
        if (!isAvailable() || exp == null || domainTag == null || domainTag.isBlank()) {
            return Optional.empty();
        }
        try {
            String trajectory = formatTrajectory(exp);
            if (trajectory.isBlank()) {
                return Optional.empty();
            }
            String system = """
                    你是运维 Agent Workflow Memory（AWM）归纳器。任务：从一次**已成功执行**的审计轨迹中，
                    提炼可复用的处置子流程（workflow），供后续 Agent 参考。
                    
                    硬性约束：
                    1. 只输出一个 JSON 对象，不要 markdown 说明。
                    2. steps 至少 2 步；toolName 必须来自允许列表（见用户消息）。
                    3. argsTemplate 的值必须是占位符（如 {log-path}、{dryRun}），禁止写真实绝对路径或 shell 命令。
                    4. 不得包含删除系统目录、关闭防火墙、提权等危险意图。
                    5. envDesc 描述触发该步的环境条件；reason 说明为何调用该工具。
                    
                    JSON  schema:
                    {
                      "title": "简短中文标题",
                      "description": "一句话说明适用场景",
                      "steps": [
                        {"envDesc":"...", "reason":"...", "toolName":"DiskTool", "argsTemplate": {}}
                      ]
                    }
                    """;
            String user = buildUserPrompt(exp, domainTag, trajectory);
            var response = chatModel.call(new Prompt(List.of(new SystemMessage(system), new UserMessage(user))));
            String raw = AiChatResponseSupport.textFrom(response);
            return parseAndValidate(raw, exp.traceId(), domainTag, findingKinds);
        } catch (Exception e) {
            log.warn("AWM LLM 归纳失败 trace={}: {}", exp.traceId(), e.getMessage());
            return Optional.empty();
        }
    }

    Optional<OpsWorkflow> parseAndValidate(
            String llmText,
            String traceId,
            String domainTag,
            List<String> findingKinds
    ) {
        String json = extractJsonBlock(llmText);
        if (json.isBlank()) {
            return Optional.empty();
        }
        try {
            Map<String, Object> root = objectMapper.readValue(json, new TypeReference<>() {
            });
            String title = str(root.get("title"));
            String description = str(root.get("description"));
            Object stepsObj = root.get("steps");
            if (title.isBlank() || !(stepsObj instanceof List<?> rawSteps) || rawSteps.size() < 2) {
                return Optional.empty();
            }
            List<OpsWorkflowStep> steps = new ArrayList<>();
            for (Object item : rawSteps) {
                if (!(item instanceof Map<?, ?> stepMap)) {
                    return Optional.empty();
                }
                OpsWorkflowStep step = parseStep(stepMap);
                if (step == null) {
                    return Optional.empty();
                }
                steps.add(step);
            }
            String id = "llm-" + domainTag + "-" + shortId();
            List<String> kinds = findingKinds == null || findingKinds.isEmpty()
                    ? defaultFindingKinds(domainTag)
                    : findingKinds;
            OpsWorkflow wf = new OpsWorkflow(
                    id,
                    domainTag,
                    kinds,
                    title.length() > 120 ? title.substring(0, 120) : title,
                    description,
                    steps,
                    "llm",
                    traceId,
                    0,
                    true
            );
            return Optional.of(wf);
        } catch (Exception e) {
            log.debug("AWM LLM JSON 解析失败: {}", e.getMessage());
            return Optional.empty();
        }
    }

    static String extractJsonBlock(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        Matcher m = JSON_FENCE.matcher(text.trim());
        if (m.find()) {
            return m.group(1).trim();
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1).trim();
        }
        return text.trim();
    }

    private OpsWorkflowStep parseStep(Map<?, ?> stepMap) {
        String toolName = str(stepMap.get("toolName")).trim();
        if (!AwmToolProfile.isInductionAllowed(toolName)) {
            return null;
        }
        String envDesc = str(stepMap.get("envDesc"));
        String reason = str(stepMap.get("reason"));
        Map<String, String> args = parseArgsTemplate(stepMap.get("argsTemplate"));
        if (args == null) {
            return null;
        }
        return OpsWorkflowStep.of(
                envDesc.isBlank() ? "环境状态见当次感知" : envDesc,
                reason.isBlank() ? "执行工具 " + toolName : reason,
                toolName,
                args
        );
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> parseArgsTemplate(Object raw) {
        if (raw == null) {
            return Map.of();
        }
        if (!(raw instanceof Map<?, ?> map)) {
            return null;
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : map.entrySet()) {
            String key = str(e.getKey());
            if (key.isBlank()) {
                continue;
            }
            String val = str(e.getValue());
            if (looksLikeDangerousLiteral(val)) {
                return null;
            }
            out.put(key, val);
        }
        return out;
    }

    private static boolean looksLikeDangerousLiteral(String val) {
        if (val == null || val.isBlank()) {
            return false;
        }
        if (val.contains("{") && val.contains("}")) {
            return false;
        }
        String lower = val.toLowerCase(Locale.ROOT);
        if (val.startsWith("/") || val.matches("^[A-Za-z]:\\\\.*")) {
            return true;
        }
        return lower.contains("rm -rf") || lower.contains("format ") || lower.contains("shutdown");
    }

    private String buildUserPrompt(OpsExperience exp, String domainTag, String trajectory) {
        String tools = AwmToolProfile.supportedTools().stream()
                .sorted()
                .collect(Collectors.joining(", "));
        StringBuilder sb = new StringBuilder();
        sb.append("domain=").append(domainTag).append("\n");
        if (exp.userInput() != null && !exp.userInput().isBlank()) {
            sb.append("用户诉求: ").append(exp.userInput()).append("\n");
        }
        sb.append("安全结论: ").append(exp.securityOutcome()).append("\n");
        sb.append("允许 toolName: ").append(tools).append("\n\n");
        sb.append("审计轨迹:\n").append(trajectory);
        return sb.toString();
    }

    private static String formatTrajectory(OpsExperience exp) {
        if (exp.steps() == null || exp.steps().isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int i = 1;
        for (Map<String, Object> step : exp.steps()) {
            String toolName = step.get("toolName") != null ? String.valueOf(step.get("toolName")) : "";
            Object parameters = step.get("parameters");
            sb.append(i++).append(". phase=")
                    .append(step.getOrDefault("phase", "?"))
                    .append(" tool=")
                    .append(toolName)
                    .append("\n   detail=")
                    .append(step.getOrDefault("detail", ""))
                    .append("\n   params=")
                    .append(parameters == null ? "{}" : parameters)
                    .append("\n");
        }
        return sb.toString().trim();
    }

    private static List<String> defaultFindingKinds(String domainTag) {
        return switch (domainTag) {
            case "cpu" -> List.of("CPU_HIGH");
            case "service" -> List.of("FAILED_SERVICE");
            default -> List.of("DISK_PRESSURE");
        };
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    private String shortId() {
        String id = traceIdGenerator.nextId();
        if (id == null || id.isBlank()) {
            return "generated";
        }
        String normalized = id.replace("-", "");
        return normalized.length() <= 8 ? normalized : normalized.substring(0, 8);
    }
}
