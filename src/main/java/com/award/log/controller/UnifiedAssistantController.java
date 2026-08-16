package com.award.log.controller;

import com.award.log.common.Result;
import com.award.log.service.AiModelRouter;
import com.award.log.service.impl.UnifiedAssistantService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Tag(name = "Unified Assistant")
@RestController
@RequestMapping("/api/assistant")
public class UnifiedAssistantController {

    @Autowired
    private UnifiedAssistantService unifiedAssistantService;

    @Autowired
    private AiModelRouter aiModelRouter;

    @Autowired
    @Qualifier("sseTaskExecutor")
    private ThreadPoolTaskExecutor sseExecutor;

    @Operation(summary = "Get assistant context")
    @GetMapping("/context")
    public Result<Map<String, Object>> context() {
        return Result.success(unifiedAssistantService.getAssistantContext());
    }

    @Operation(summary = "Get configured assistant model profiles")
    @GetMapping("/models")
    public Result<Map<String, Object>> models() {
        return Result.success(aiModelRouter.snapshot());
    }

    @Operation(summary = "Preview assistant execution state")
    @PostMapping("/state/preview")
    public Result<Map<String, Object>> statePreview(@RequestBody Map<String, Object> payload) {
        String rawMessage = String.valueOf(payload.getOrDefault("message", "")).trim();
        List<UnifiedAssistantService.ChatTurn> history = parseHistory(payload.get("history"));
        boolean useToolAgent = parseUseToolAgent(payload.get("useToolAgent"));
        boolean confirmRemediation = parseConfirmRemediation(payload.get("confirmRemediation"));
        return Result.success(unifiedAssistantService.previewAgentState(
                rawMessage, history, useToolAgent, confirmRemediation));
    }

    @Operation(summary = "Unified assistant streaming chat")
    @PostMapping(value = "/chat/stream", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter chatStream(@RequestBody Map<String, Object> payload) {
        String rawMessage = String.valueOf(payload.getOrDefault("message", "")).trim();
        if (rawMessage.isBlank()) {
            SseEmitter emitter = new SseEmitter(1000L);
            try {
                emitter.send("请填写问题");
                emitter.complete();
            } catch (Exception ignored) {
            }
            return emitter;
        }
        final String message = rawMessage.length() > 4000 ? rawMessage.substring(0, 4000) : rawMessage;
        List<UnifiedAssistantService.ChatTurn> history = parseHistory(payload.get("history"));
        boolean useToolAgent = parseUseToolAgent(payload.get("useToolAgent"));
        boolean confirmRemediation = parseConfirmRemediation(payload.get("confirmRemediation"));
        String modelProfile = parseModelProfile(payload.get("modelProfile"));
        SseEmitter emitter = new SseEmitter(300000L);
        sseExecutor.execute(() -> {
            try {
                Flux<String> stream = modelProfile == null
                        ? unifiedAssistantService.chatStream(message, history, useToolAgent, confirmRemediation)
                        : unifiedAssistantService.chatStream(message, history, useToolAgent, confirmRemediation, modelProfile);
                stream
                        .doOnNext(chunk -> {
                            try {
                                emitter.send(chunk);
                            } catch (Exception e) {
                                emitter.completeWithError(e);
                            }
                        })
                        .doOnComplete(emitter::complete)
                        .doOnError(emitter::completeWithError)
                        .subscribe();
            } catch (Exception e) {
                log.error("Unified assistant stream failed", e);
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }

    @SuppressWarnings("unchecked")
    private static List<UnifiedAssistantService.ChatTurn> parseHistory(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<UnifiedAssistantService.ChatTurn> turns = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            Object role = map.get("role");
            Object content = map.get("content");
            if (role == null || content == null) {
                continue;
            }
            String r = String.valueOf(role).trim();
            String c = String.valueOf(content).trim();
            if (!c.isEmpty()) {
                turns.add(new UnifiedAssistantService.ChatTurn(r, c));
            }
        }
        return turns;
    }

    private static boolean parseUseToolAgent(Object raw) {
        if (raw == null) {
            return true;
        }
        String s = String.valueOf(raw).trim();
        if (s.isEmpty()) {
            return true;
        }
        return !("false".equalsIgnoreCase(s) || "0".equals(s));
    }

    private static boolean parseConfirmRemediation(Object raw) {
        if (raw == null) {
            return false;
        }
        String s = String.valueOf(raw).trim();
        return "true".equalsIgnoreCase(s) || "1".equals(s);
    }

    private static String parseModelProfile(Object raw) {
        if (raw == null) return null;
        String value = String.valueOf(raw).trim();
        return value.length() > 64 ? value.substring(0, 64) : value;
    }
}
