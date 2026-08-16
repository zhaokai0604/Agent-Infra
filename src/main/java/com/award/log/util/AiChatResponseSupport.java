package com.award.log.util;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

/**
 * Spring AI 1.1+ 流式帧解析：优先聚合 {@link ChatResponse#getResults()}，避免仅依赖 {@link ChatResponse#getResult()} 在增量帧上为 null 导致 NPE。
 */
public final class AiChatResponseSupport {

    private AiChatResponseSupport() {
    }

    public static String textFrom(ChatResponse response) {
        if (response == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        try {
            if (response.getResults() != null) {
                for (Generation g : response.getResults()) {
                    if (g == null) {
                        continue;
                    }
                    AssistantMessage out = g.getOutput();
                    if (out == null) {
                        continue;
                    }
                    String t = out.getText();
                    if (t != null && !t.isEmpty()) {
                        sb.append(t);
                    }
                }
            }
        } catch (Exception ignored) {
            // 保持与调用方 onErrorResume 一致：此处仅吞掉解析异常，由上游决定是否降级
        }
        if (sb.length() > 0) {
            return sb.toString();
        }
        try {
            if (response.getResult() != null && response.getResult().getOutput() != null) {
                String t = response.getResult().getOutput().getText();
                return t != null ? t : "";
            }
        } catch (Exception ignored) {
        }
        return "";
    }
}
