package com.award.log.util;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Normalizes provider usage and supplies a conservative estimate when streaming metadata is absent. */
public final class AiUsageSupport {

    private AiUsageSupport() {
    }

    public static int estimatePromptTokens(List<? extends Message> messages) {
        if (messages == null || messages.isEmpty()) return 0;
        int chars = 0;
        for (Message message : messages) {
            if (message != null && message.getText() != null) {
                chars += message.getText().length();
            }
        }
        return estimateTokens(chars);
    }

    public static int estimateTokens(String text) {
        return estimateTokens(text == null ? 0 : text.length());
    }

    public static Map<String, Object> usage(ChatResponse response,
                                             String profile,
                                             String model,
                                             int contextWindow,
                                             int estimatedPromptTokens,
                                             int estimatedCompletionTokens) {
        Usage usage = response == null || response.getMetadata() == null
                ? null : response.getMetadata().getUsage();
        Integer prompt = usage == null ? null : usage.getPromptTokens();
        Integer completion = usage == null ? null : usage.getCompletionTokens();
        Integer total = usage == null ? null : usage.getTotalTokens();
        int inputTokens = positiveOr(prompt, estimatedPromptTokens);
        int outputTokens = positiveOr(completion, estimatedCompletionTokens);
        int totalTokens = positiveOr(total, inputTokens + outputTokens);
        int window = Math.max(1, contextWindow);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("profile", profile == null ? "default" : profile);
        out.put("model", model == null ? "" : model);
        out.put("promptTokens", inputTokens);
        out.put("completionTokens", outputTokens);
        out.put("totalTokens", totalTokens);
        out.put("contextWindow", window);
        out.put("utilizationPct", Math.round(totalTokens * 10000.0 / window) / 100.0);
        out.put("source", prompt != null || completion != null || total != null ? "provider" : "estimated");
        return out;
    }

    private static int positiveOr(Integer value, int fallback) {
        return value == null || value < 0 ? Math.max(0, fallback) : value;
    }

    private static int estimateTokens(int chars) {
        if (chars <= 0) return 0;
        // Mixed Chinese/English operational text is usually between 1 and 4 chars/token.
        return Math.max(1, (int) Math.ceil(chars / 2.5));
    }
}
